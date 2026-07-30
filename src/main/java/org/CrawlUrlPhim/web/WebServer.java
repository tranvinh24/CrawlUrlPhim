package org.CrawlUrlPhim.web;

import com.sun.net.httpserver.HttpServer;
import org.CrawlUrlPhim.db.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.sql.SQLException;

/**
 * Embedded HTTP web server that exposes movie data from the MySQL database.
 *
 * Endpoints:
 *   GET /movies                 — paginated list of all movies (?page=1&limit=20)
 *   GET /movie?url={movieUrl}   — single movie by URL (with cache)
 *   GET /movie/cache-stats      — cache metrics
 *
 * Usage:
 *   Run Main with argument --server
 */
public class WebServer {

    private static final Logger logger = LoggerFactory.getLogger(WebServer.class);
    private static final int PORT = 8080;

    private final HttpServer server;

    public WebServer(DatabaseManager db) throws Exception {
        server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/movies", new MoviesListHandler(db));
        server.createContext("/movie",  new MovieHandler(db));
        server.setExecutor(null);
    }

    /**
     * Starts the HTTP server and blocks until the JVM exits.
     */
    public void start() {
        server.start();
        logger.info("Web server started on http://localhost:{}", PORT);
        logger.info("  GET /movies              — list all movies (paginated)");
        logger.info("  GET /movie?url={{url}}     — single movie by URL");
        logger.info("  GET /movie/cache-stats   — cache metrics");
        logger.info("Press Ctrl+C to stop.");
    }

    /**
     * Stops the server gracefully.
     */
    public void stop() {
        server.stop(1);
        logger.info("Web server stopped.");
    }
}
