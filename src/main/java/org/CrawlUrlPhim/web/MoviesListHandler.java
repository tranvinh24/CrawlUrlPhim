package org.CrawlUrlPhim.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.CrawlUrlPhim.db.DatabaseManager;
import org.CrawlUrlPhim.model.Movie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles GET /movies — returns a paginated list of all movies in the database.
 *
 * <h3>Authentication</h3>
 * Requires header: {@code Authorization: Bearer <token>}
 * Obtain a token via {@code POST /login}.
 *
 * <h3>Rate Limiting</h3>
 * Per authenticated user: ≤ 2 requests per 5 s, ≤ 10 requests per 1 min.
 * Violations return HTTP 429.
 *
 * <h3>Query parameters</h3>
 * <ul>
 *   <li>{@code page}  (int, default 1)  — page number (1-based)</li>
 *   <li>{@code limit} (int, default 20) — items per page (max 100)</li>
 * </ul>
 *
 * Example:
 *   GET /movies              → first 20 movies
 *   GET /movies?page=2       → next 20 movies
 *   GET /movies?limit=50     → first 50 movies
 */
public class MoviesListHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(MoviesListHandler.class);
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT     = 100;

    private final DatabaseManager db;
    private final AuthManager authManager;
    private final RateLimiter rateLimiter;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public MoviesListHandler(DatabaseManager db, AuthManager authManager, RateLimiter rateLimiter) {
        this.db = db;
        this.authManager = authManager;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, gson.toJson(Map.of("error", "Method not allowed")));
            return;
        }

        // --- Authentication check ---
        String username = resolveUser(exchange);
        if (username == null) {
            sendResponse(exchange, 401, gson.toJson(Map.of(
                    "error", "Unauthorized. Please login via POST /login and provide 'Authorization: Bearer <token>' header.")));
            return;
        }

        // --- Rate limit check ---
        if (!rateLimiter.isAllowed(username)) {
            sendResponse(exchange, 429, gson.toJson(Map.of(
                    "error", "Too Many Requests. Limit: 2 requests per 5s and 10 requests per 1 minute.")));
            return;
        }

        // Parse query params: page, limit
        int page  = parseIntParam(exchange.getRequestURI(), "page",  1);
        int limit = parseIntParam(exchange.getRequestURI(), "limit", DEFAULT_LIMIT);
        limit = Math.min(limit, MAX_LIMIT);
        int offset = (page - 1) * limit;

        logger.info("Request: GET /movies?page={}&limit={} (user={})", page, limit, username);

        try {
            int total          = db.getMovieCount();
            List<Movie> movies = db.getAllMovies(offset, limit);

            int totalPages = (int) Math.ceil((double) total / limit);

            // Build response envelope
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("total",      total);
            response.put("page",       page);
            response.put("limit",      limit);
            response.put("totalPages", totalPages);
            response.put("movies",     movies);

            sendResponse(exchange, 200, gson.toJson(response));

        } catch (SQLException e) {
            logger.error("Database error: {}", e.getMessage(), e);
            sendResponse(exchange, 500, gson.toJson(Map.of("error", "Internal server error: " + e.getMessage())));
        }
    }

    // -----------------------------------------------------------------------
    // Auth helper
    // -----------------------------------------------------------------------

    /**
     * Extracts and validates the bearer token from the Authorization header.
     *
     * @return the username associated with the token, or {@code null} if invalid/missing
     */
    private String resolveUser(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String token = authHeader.substring("Bearer ".length()).trim();
        return authManager.validateToken(token);
    }

    /** Parses an integer query param; returns defaultValue if absent or invalid. */
    private int parseIntParam(URI uri, String name, int defaultValue) {
        String query = uri.getRawQuery();
        if (query == null) return defaultValue;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && name.equals(kv[0])) {
                try { return Math.max(1, Integer.parseInt(kv[1])); }
                catch (NumberFormatException ignored) {}
            }
        }
        return defaultValue;
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}