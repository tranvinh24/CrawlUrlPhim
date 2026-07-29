package org.CrawlUrlPhim;

import org.CrawlUrlPhim.crawler.MovieCrawler;
import org.CrawlUrlPhim.crawler.UrlRepository;
import org.CrawlUrlPhim.db.DatabaseManager;
import org.CrawlUrlPhim.model.Movie;
import org.CrawlUrlPhim.web.WebServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * Main entry point for the ToiVote Movie Crawler.
 *
 * Modes:
 *   (no args)  — crawl mode: fetch ~100 movie URLs and save to SQLite
 *   --server   — server mode: start HTTP web service on port 8080
 *
 * Server endpoint:
 *   GET http://localhost:8080/movie?url={movieUrl}
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /** Delay between requests (ms) to be respectful to the server */
    private static final long REQUEST_DELAY_MS = 1000;

    public static void main(String[] args) {
        boolean serverMode = Arrays.asList(args).contains("--server");

        // Initialize database (shared between both modes)
        DatabaseManager db = new DatabaseManager();
        try {
            db.init();
        } catch (SQLException e) {
            logger.error("Failed to initialize database: {}", e.getMessage(), e);
            System.exit(1);
        }

        if (serverMode) {
            runServer(db);
        } else {
            runCrawler(db);
        }
    }

    // -----------------------------------------------------------------------
    // Server mode
    // -----------------------------------------------------------------------

    private static void runServer(DatabaseManager db) {
        logger.info("=== Starting Web Server ===");
        try {
            WebServer server = new WebServer(db);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop();
                db.close();
            }));
            server.start();
        } catch (Exception e) {
            logger.error("Failed to start web server: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    // -----------------------------------------------------------------------
    // Crawl mode
    // -----------------------------------------------------------------------

    private static void runCrawler(DatabaseManager db) {
        logger.info("=== ToiVote Movie Crawler Starting ===");

        // Load URL list
        List<String> urls = UrlRepository.getUrls();
        logger.info("Loaded {} URLs to crawl.", urls.size());

        // Crawl each URL
        MovieCrawler crawler = new MovieCrawler();
        int success = 0, failed = 0, skipped = 0;

        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            logger.info("[{}/{}] Processing: {}", i + 1, urls.size(), url);

            Movie movie = crawler.crawl(url);

            if (movie != null) {
                boolean saved = db.saveMovie(movie);
                if (saved) {
                    success++;
                    logger.info("  -> Saved: {} | Year: {} | Country: {} | Genres: {}",
                            movie.getTitle(), movie.getYear(), movie.getCountry(), movie.getGenres());
                } else {
                    skipped++;
                    logger.info("  -> Skipped (already in DB): {}", movie.getTitle());
                }
            } else {
                failed++;
                logger.warn("  -> Failed to crawl: {}", url);
            }

            // Polite delay between requests
            if (i < urls.size() - 1) {
                try {
                    Thread.sleep(REQUEST_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // Summary
        int totalInDb = db.getMovieCount();
        logger.info("=== Crawl Complete ===");
        logger.info("  URLs processed : {}", urls.size());
        logger.info("  Saved new      : {}", success);
        logger.info("  Already in DB  : {}", skipped);
        logger.info("  Failed/Empty   : {}", failed);
        logger.info("  Total in DB    : {}", totalInDb);
        logger.info("  Database file  : movies.db");

        db.close();
        logger.info("=== Done ===");
    }
}