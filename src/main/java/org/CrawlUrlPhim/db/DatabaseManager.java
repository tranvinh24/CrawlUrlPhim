package org.CrawlUrlPhim.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.CrawlUrlPhim.model.Movie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Handles MySQL database operations for storing crawled movie data.
 *
 * <p>Uses HikariCP as a connection pool so multiple threads (crawler, web
 * server) can safely acquire independent connections without contention.
 *
 * <p>Connection settings are resolved in this priority order:
 * <ol>
 *   <li>JVM system property  (e.g. {@code -Ddb.password=secret})</li>
 *   <li>{@code db.properties} on the classpath</li>
 *   <li>Built-in default values</li>
 * </ol>
 */
public class DatabaseManager {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);

    // -----------------------------------------------------------------------
    // Configuration â€“ loaded once at class init
    // -----------------------------------------------------------------------
    private static final Properties CONFIG = loadConfig();

    /** Reads db.properties from the classpath; missing keys stay empty. */
    private static Properties loadConfig() {
        Properties props = new Properties();
        try (InputStream is = DatabaseManager.class
                .getClassLoader().getResourceAsStream("db.properties")) {
            if (is != null) {
                props.load(is);
                LoggerFactory.getLogger(DatabaseManager.class)
                        .info("Loaded db.properties from classpath.");
            } else {
                LoggerFactory.getLogger(DatabaseManager.class)
                        .warn("db.properties not found on classpath â€“ using system properties / defaults.");
            }
        } catch (IOException e) {
            LoggerFactory.getLogger(DatabaseManager.class)
                    .error("Failed to read db.properties: {}", e.getMessage());
        }
        return props;
    }

    /**
     * Resolves a config key: JVM system property â†’ db.properties â†’ default.
     */
    private static String cfg(String key, String defaultValue) {
        String sysProp = System.getProperty(key);
        if (sysProp != null && !sysProp.isBlank()) return sysProp;
        String fileProp = CONFIG.getProperty(key);
        if (fileProp != null && !fileProp.isBlank()) return fileProp;
        return defaultValue;
    }

    // -----------------------------------------------------------------------
    // Derived JDBC URL (built lazily in init())
    // -----------------------------------------------------------------------
    private String jdbcUrl;

    // -----------------------------------------------------------------------
    // HikariCP pool
    // -----------------------------------------------------------------------
    private HikariDataSource dataSource;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Initialises the HikariCP connection pool and creates tables if they
     * do not already exist.
     *
     * @throws SQLException if the pool cannot connect or DDL fails
     */
    public void init() throws SQLException {
        String host = cfg("db.host",     "localhost");
        String port = cfg("db.port",     "3306");
        String name = cfg("db.name",     "movies");
        String user = cfg("db.user",     "root");
        String pass = cfg("db.password", "");

        jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + name
                + "?useUnicode=true&characterEncoding=UTF-8"
                + "&serverTimezone=UTC"
                + "&useSSL=false"
                + "&allowPublicKeyRetrieval=true";

        logger.info("Initialising MySQL database â€“ {}:{}/{}", host, port, name);

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(user);
        cfg.setPassword(pass);
        cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");

        // Pool sizing
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(2);
        cfg.setConnectionTimeout(30_000);   // 30 s
        cfg.setIdleTimeout(600_000);        // 10 min
        cfg.setMaxLifetime(1_800_000);      // 30 min

        // Validate connection on borrow
        cfg.setConnectionTestQuery("SELECT 1");
        cfg.setPoolName("MoviePool");

        dataSource = new HikariDataSource(cfg);
        logger.info("HikariCP pool initialised.");

        createTables();
        logger.info("Database initialised successfully.");
    }

    // -----------------------------------------------------------------------
    // DDL
    // -----------------------------------------------------------------------

    /**
     * Creates the four normalised tables if they do not already exist.
     * Uses VARCHAR(255) for primary keys so MySQL can index them efficiently.
     */
    private void createTables() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS movies (
                    id          VARCHAR(255) PRIMARY KEY,
                    url         VARCHAR(2083) NOT NULL,
                    title       TEXT,
                    year        VARCHAR(10),
                    country     VARCHAR(100),
                    crawled_at  DATETIME DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS movie_genres (
                    movie_id    VARCHAR(255) NOT NULL,
                    genre       VARCHAR(255) NOT NULL,
                    PRIMARY KEY (movie_id, genre),
                    FOREIGN KEY (movie_id) REFERENCES movies(id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS movie_directors (
                    movie_id    VARCHAR(255) NOT NULL,
                    director    VARCHAR(255) NOT NULL,
                    PRIMARY KEY (movie_id, director),
                    FOREIGN KEY (movie_id) REFERENCES movies(id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS movie_actors (
                    movie_id    VARCHAR(255) NOT NULL,
                    actor       VARCHAR(255) NOT NULL,
                    PRIMARY KEY (movie_id, actor),
                    FOREIGN KEY (movie_id) REFERENCES movies(id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

            logger.debug("Tables created / verified.");
        }
    }

    // -----------------------------------------------------------------------
    // Write operations
    // -----------------------------------------------------------------------

    /**
     * Inserts a movie and its related data inside a single transaction.
     * Skips silently if a movie with the same {@code id} already exists.
     *
     * @param movie the Movie object to persist
     * @return {@code true} if the row was inserted; {@code false} if skipped
     */
    public boolean saveMovie(Movie movie) {
        if (movie == null || movie.getId() == null) {
            logger.warn("Attempted to save null or ID-less movie, skipping.");
            return false;
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (movieExists(conn, movie.getId())) {
                    logger.info("Movie already exists in DB, skipping: {}", movie.getTitle());
                    return false;
                }

                // INSERT INTO ... ON DUPLICATE KEY UPDATE (no-op on conflict)
                String sql = """
                    INSERT INTO movies (id, url, title, year, country)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE id = id
                """;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, movie.getId());
                    ps.setString(2, movie.getUrl());
                    ps.setString(3, movie.getTitle());
                    ps.setString(4, movie.getYear());
                    ps.setString(5, movie.getCountry());
                    ps.executeUpdate();
                }

                insertList(conn, movie.getId(), movie.getGenres(),
                        "INSERT IGNORE INTO movie_genres (movie_id, genre) VALUES (?, ?)");

                insertList(conn, movie.getId(), movie.getDirectors(),
                        "INSERT IGNORE INTO movie_directors (movie_id, director) VALUES (?, ?)");

                insertList(conn, movie.getId(), movie.getActors(),
                        "INSERT IGNORE INTO movie_actors (movie_id, actor) VALUES (?, ?)");

                conn.commit();
                logger.info("Saved movie to DB: [{}] {}", movie.getId(), movie.getTitle());
                return true;

            } catch (SQLException e) {
                conn.rollback();
                logger.error("Failed to save movie {}: {}", movie.getTitle(), e.getMessage());
                return false;
            }
        } catch (SQLException e) {
            logger.error("Connection error while saving movie: {}", e.getMessage());
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Read operations
    // -----------------------------------------------------------------------

    /**
     * Checks whether a movie with the given ID already exists.
     *
     * @param conn    an active connection (within the caller's transaction)
     * @param movieId the UUID to look up
     */
    public boolean movieExists(Connection conn, String movieId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM movies WHERE id = ?")) {
            ps.setString(1, movieId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Convenience overload â€“ acquires its own connection.
     */
    public boolean movieExists(String movieId) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            return movieExists(conn, movieId);
        }
    }

    /**
     * Retrieves a fully populated {@link Movie} by its source URL.
     *
     * @param url the movie page URL
     * @return populated Movie, or {@code null} if not found
     */
    public Movie getMovieByUrl(String url) throws SQLException {
        String sql = "SELECT id, url, title, year, country FROM movies WHERE url = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                Movie movie = new Movie();
                movie.setId(rs.getString("id"));
                movie.setUrl(rs.getString("url"));
                movie.setTitle(rs.getString("title"));
                movie.setYear(rs.getString("year"));
                movie.setCountry(rs.getString("country"));

                movie.setGenres(fetchList(conn,
                        "SELECT genre    FROM movie_genres    WHERE movie_id = ?", movie.getId()));
                movie.setDirectors(fetchList(conn,
                        "SELECT director FROM movie_directors WHERE movie_id = ?", movie.getId()));
                movie.setActors(fetchList(conn,
                        "SELECT actor    FROM movie_actors    WHERE movie_id = ?", movie.getId()));
                return movie;
            }
        }
    }

    /**
     * Returns the total number of movies stored in the database.
     */
    public int getMovieCount() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM movies")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            logger.error("Error counting movies: {}", e.getMessage());
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Fetches a single-column list of strings via a parameterised query.
     */
    private List<String> fetchList(Connection conn, String sql, String movieId)
            throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, movieId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString(1));
            }
        }
        return result;
    }

    /**
     * Batch-inserts a list of string values into a (movie_id, value) table.
     * Uses {@code INSERT IGNORE} so duplicates are silently skipped.
     */
    private void insertList(Connection conn, String movieId,
                            List<String> items, String sql) throws SQLException {
        if (items == null || items.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String item : items) {
                if (item != null && !item.isBlank()) {
                    ps.setString(1, movieId);
                    ps.setString(2, item.trim());
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle â€“ shutdown
    // -----------------------------------------------------------------------

    /**
     * Closes the HikariCP pool and releases all connections.
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("HikariCP pool closed.");
        }
    }
}
