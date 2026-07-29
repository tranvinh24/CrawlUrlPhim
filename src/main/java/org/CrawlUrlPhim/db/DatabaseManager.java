package org.example.db;

import org.example.model.Movie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.List;

/**
 * Handles SQLite database operations for storing crawled movie data.
 * Data is persisted in a local SQLite file (movies.db) which acts as the disk backup.
 */
public class DatabaseManager {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private static final String DB_FILE = "movies.db";
    private static final String DB_URL = "jdbc:sqlite:" + DB_FILE;

    private Connection connection;

    /**
     * Opens the SQLite connection and creates tables if they don't exist.
     */
    public void init() throws SQLException {
        logger.info("Initializing SQLite database: {}", DB_FILE);
        connection = DriverManager.getConnection(DB_URL);
        connection.setAutoCommit(false);
        createTables();
        logger.info("Database initialized successfully.");
    }

    /**
     * Creates the movies, genres, directors, actors tables and join tables.
     */
    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Main movies table
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS movies (
                    id          TEXT PRIMARY KEY,
                    url         TEXT NOT NULL,
                    title       TEXT,
                    year        TEXT,
                    country     TEXT,
                    crawled_at  DATETIME DEFAULT CURRENT_TIMESTAMP
                )
            """);

            // Genres stored as comma-separated in a separate normalized table
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS movie_genres (
                    movie_id    TEXT NOT NULL,
                    genre       TEXT NOT NULL,
                    PRIMARY KEY (movie_id, genre),
                    FOREIGN KEY (movie_id) REFERENCES movies(id)
                )
            """);

            // Directors
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS movie_directors (
                    movie_id    TEXT NOT NULL,
                    director    TEXT NOT NULL,
                    PRIMARY KEY (movie_id, director),
                    FOREIGN KEY (movie_id) REFERENCES movies(id)
                )
            """);

            // Actors
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS movie_actors (
                    movie_id    TEXT NOT NULL,
                    actor       TEXT NOT NULL,
                    PRIMARY KEY (movie_id, actor),
                    FOREIGN KEY (movie_id) REFERENCES movies(id)
                )
            """);

            connection.commit();
            logger.debug("Tables created/verified.");
        }
    }

    /**
     * Inserts or replaces a movie and its related data.
     *
     * @param movie the Movie object to persist
     * @return true if saved successfully
     */
    public boolean saveMovie(Movie movie) {
        if (movie == null || movie.getId() == null) {
            logger.warn("Attempted to save null or ID-less movie, skipping.");
            return false;
        }
        try {
            // Check if already exists
            if (movieExists(movie.getId())) {
                logger.info("Movie already exists in DB, skipping: {}", movie.getTitle());
                return false;
            }

            // Insert main record
            String insertMovie = "INSERT OR REPLACE INTO movies (id, url, title, year, country) VALUES (?,?,?,?,?)";
            try (PreparedStatement ps = connection.prepareStatement(insertMovie)) {
                ps.setString(1, movie.getId());
                ps.setString(2, movie.getUrl());
                ps.setString(3, movie.getTitle());
                ps.setString(4, movie.getYear());
                ps.setString(5, movie.getCountry());
                ps.executeUpdate();
            }

            // Insert genres
            insertList(movie.getId(), movie.getGenres(),
                    "INSERT OR IGNORE INTO movie_genres (movie_id, genre) VALUES (?,?)");

            // Insert directors
            insertList(movie.getId(), movie.getDirectors(),
                    "INSERT OR IGNORE INTO movie_directors (movie_id, director) VALUES (?,?)");

            // Insert actors
            insertList(movie.getId(), movie.getActors(),
                    "INSERT OR IGNORE INTO movie_actors (movie_id, actor) VALUES (?,?)");

            connection.commit();
            logger.info("Saved movie to DB: [{}] {}", movie.getId(), movie.getTitle());
            return true;

        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ex) { /* ignore */ }
            logger.error("Failed to save movie {}: {}", movie.getTitle(), e.getMessage());
            return false;
        }
    }

    /**
     * Checks whether a movie with the given ID already exists in the database.
     */
    public boolean movieExists(String movieId) throws SQLException {
        String sql = "SELECT 1 FROM movies WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, movieId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Returns the count of movies stored in the database.
     */
    public int getMovieCount() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM movies")) {
            return rs.getInt(1);
        } catch (SQLException e) {
            logger.error("Error counting movies: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Helper: inserts a list of string values into a table with (movie_id, value) columns.
     */
    private void insertList(String movieId, List<String> items, String sql) throws SQLException {
        if (items == null || items.isEmpty()) return;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
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

    /**
     * Closes the database connection.
     */
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                logger.info("Database connection closed.");
            } catch (SQLException e) {
                logger.warn("Error closing connection: {}", e.getMessage());
            }
        }
    }
}
