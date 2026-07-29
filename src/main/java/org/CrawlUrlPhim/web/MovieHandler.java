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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handles HTTP GET /movie?url={movieUrl}
 * Looks up the movie in the local SQLite database and returns formatted JSON.
 */
public class MovieHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(MovieHandler.class);

    private final DatabaseManager db;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public MovieHandler(DatabaseManager db) {
        this.db = db;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, buildError("Method not allowed"));
            return;
        }

        String movieUrl = parseUrlParam(exchange.getRequestURI());
        if (movieUrl == null || movieUrl.isBlank()) {
            sendResponse(exchange, 400, buildError("Missing required parameter: url"));
            return;
        }

        logger.info("Request: GET /movie?url={}", movieUrl);

        try {
            Movie movie = db.getMovieByUrl(movieUrl);
            if (movie == null) {
                sendResponse(exchange, 404, buildError("Movie not found for URL: " + movieUrl));
            } else {
                sendResponse(exchange, 200, gson.toJson(movie));
            }
        } catch (SQLException e) {
            logger.error("Database error: {}", e.getMessage(), e);
            sendResponse(exchange, 500, buildError("Internal server error: " + e.getMessage()));
        }
    }

    /**
     * Parses the "url" query parameter from the request URI.
     */
    private String parseUrlParam(URI requestUri) {
        String query = requestUri.getRawQuery();
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && "url".equals(kv[0])) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * Sends a JSON response with the given status code and body.
     */
    private void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Builds a simple JSON error object.
     */
    private String buildError(String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("error", message);
        return gson.toJson(error);
    }
}
