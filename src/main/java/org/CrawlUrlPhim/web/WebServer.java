package org.CrawlUrlPhim.web;

import com.sun.net.httpserver.HttpServer;
import org.CrawlUrlPhim.db.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * Embedded HTTP web server that exposes movie data from the MySQL database.
 *
 * <h3>Endpoints</h3>
 * <pre>
 *   POST /login                 — obtain a bearer token (no auth required)
 *   GET  /movies                — paginated list of all movies (requires auth)
 *   GET  /movie?url={movieUrl}  — single movie by URL with cache (requires auth)
 *   GET  /movie/cache-stats     — cache metrics (requires auth)
 * </pre>
 *
 * <h3>Authentication</h3>
 * All endpoints except {@code POST /login} require the header:
 * <pre>
 *   Authorization: Bearer &lt;token&gt;
 * </pre>
 *
 * <h3>Rate Limiting</h3>
 * Per authenticated user:
 * <ul>
 *   <li>At most 2 requests per 5 seconds</li>
 *   <li>At most 10 requests per 1 minute</li>
 * </ul>
 * Violations return HTTP 429.
 *
 * Usage: run Main with argument {@code --server}
 */
public class WebServer {

    private static final Logger logger = LoggerFactory.getLogger(WebServer.class);
    private static final int PORT = 8080;

    private final HttpServer server;

    public WebServer(DatabaseManager db) throws Exception {
        // Shared auth & rate-limit components
        AuthManager authManager = new AuthManager();
        RateLimiter rateLimiter = new RateLimiter();

        server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // Public endpoint — login (no auth required)
        server.createContext("/login",  new AuthHandler(authManager));

        // Protected endpoints — require valid bearer token + rate limit
        server.createContext("/movies", new MoviesListHandler(db, authManager, rateLimiter));
        server.createContext("/movie",  new MovieHandler(db, authManager, rateLimiter));

        server.setExecutor(null);
    }

    /**
     * Starts the HTTP server and logs available endpoints.
     */
    public void start() {
        server.start();
        logger.info("Web server started on http://localhost:{}", PORT);
        logger.info("  POST /login              — obtain bearer token");
        logger.info("  GET  /movies             — list all movies (paginated) [auth required]");
        logger.info("  GET  /movie?url={{url}}    — single movie by URL          [auth required]");
        logger.info("  GET  /movie/cache-stats  — cache metrics                [auth required]");
        logger.info("Rate limits: 2 req/5s and 10 req/1min per user");
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
