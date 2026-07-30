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
 * Query parameters:
 *   page  (int, default 1)   — page number (1-based)
 *   limit (int, default 20)  — items per page (max 100)
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
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public MoviesListHandler(DatabaseManager db) {
        this.db = db;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, gson.toJson(Map.of("error", "Method not allowed")));
            return;
        }

        // Parse query params: page, limit
        int page  = parseIntParam(exchange.getRequestURI(), "page",  1);
        int limit = parseIntParam(exchange.getRequestURI(), "limit", DEFAULT_LIMIT);
        limit = Math.min(limit, MAX_LIMIT);
        int offset = (page - 1) * limit;

        logger.info("Request: GET /movies?page={}&limit={}", page, limit);

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