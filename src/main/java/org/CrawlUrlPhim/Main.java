package org.example;

import org.example.crawler.MovieCrawler;
import org.example.crawler.UrlRepository;
import org.example.db.DatabaseManager;
import org.example.model.Movie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;

/**
 * Main entry point for the ToiVote Movie Crawler.
 *
 * Workflow:
 * 1. Load ~100 movie URLs from toivote.com
 * 2. For each URL, crawl and extract: title, year, country, genres, directors, actors
 * 3. Save data to SQLite database (movies.db) - which also serves as the disk backup
 * 4. Print a summary
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /** Delay between requests (ms) to be respectful to the server */
    private static final long REQUEST_DELAY_MS = 1000;

    public static void main(String[] args) {
        logger.info("=== ToiVote Movie Crawler Starting ===");

        // 1. Initialize database
        DatabaseManager db = new DatabaseManager();
        try {
            db.init();
        } catch (SQLException e) {
            logger.error("Failed to initialize database: {}", e.getMessage(), e);
            System.exit(1);
        }

        // 2. Load URL list
        List<String> urls = UrlRepository.getUrls();
        logger.info("Loaded {} URLs to crawl.", urls.size());

        // 3. Crawl each URL
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

        // 4. Summary
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