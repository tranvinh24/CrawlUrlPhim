package org.CrawlUrlPhim.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.CrawlUrlPhim.cache.CacheTTL;
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
 *
 * <p>Results are served from an in-memory {@link CacheTTL} to avoid repeated
 * database hits for the same URL.  The cache is configured with:
 * <ul>
 *   <li>Idle TTL  = {@value #CACHE_IDLE_TTL_SECONDS} s – entry evicted if not read for this long</li>
 *   <li>Write TTL = {@value #CACHE_WRITE_TTL_SECONDS} s – entry evicted this long after first write</li>
 * </ul>
 *
 * <p>An additional endpoint <b>GET /movie/cache-stats</b> returns a JSON
 * snapshot of cache metrics (size, hit-rate, live entries).
 */
public class MovieHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(MovieHandler.class);

    // -----------------------------------------------------------------------
    // Cache configuration
    // -----------------------------------------------------------------------
    /** Seconds of inactivity before an entry is evicted (idle TTL = n). */
    private static final int CACHE_IDLE_TTL_SECONDS  = 30;

    /** Seconds after write before an entry is forcibly evicted (write TTL = m). */
    private static final int CACHE_WRITE_TTL_SECONDS = 120;

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------
    private final DatabaseManager db;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Movie cache: key = movie URL, value = Movie object.
     * Constructed with (idleTtl=n, writeTtl=m).
     */
    private final CacheTTL<String, Movie> cache =
            new CacheTTL<>(CACHE_IDLE_TTL_SECONDS, CACHE_WRITE_TTL_SECONDS);

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------
    public MovieHandler(DatabaseManager db) {
        this.db = db;
        logger.info("MovieHandler started – cache idleTTL={}s writeTTL={}s",
                CACHE_IDLE_TTL_SECONDS, CACHE_WRITE_TTL_SECONDS);
    }

    // -----------------------------------------------------------------------
    // HTTP handler
    // -----------------------------------------------------------------------
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, buildError("Method not allowed"));
            return;
        }

        String path = exchange.getRequestURI().getPath();

        // Sub-route: GET /movie/cache-stats
        if (path != null && path.endsWith("/cache-stats")) {
            handleCacheStats(exchange);
            return;
        }

        // Main route: GET /movie?url=...
        String movieUrl = parseUrlParam(exchange.getRequestURI());
        if (movieUrl == null || movieUrl.isBlank()) {
            sendResponse(exchange, 400, buildError("Missing required parameter: url"));
            return;
        }

        logger.info("Request: GET /movie?url={}", movieUrl);

        // --- Cache lookup ---
        Movie movie = cache.get(movieUrl);
        if (movie != null) {
            logger.info("Cache HIT  for url={} (hitRate={}%)", movieUrl, cache.getHitRate());
            sendResponse(exchange, 200, gson.toJson(movie));
            return;
        }

        // --- Cache miss: query the database ---
        logger.info("Cache MISS for url={} – querying DB", movieUrl);
        try {
            movie = db.getMovieByUrl(movieUrl);
            if (movie == null) {
                sendResponse(exchange, 404, buildError("Movie not found for URL: " + movieUrl));
            } else {
                cache.put(movieUrl, movie);   // populate cache for next request
                logger.info("Cached movie '{}' (idleTTL={}s writeTTL={}s)",
                        movie.getTitle(), CACHE_IDLE_TTL_SECONDS, CACHE_WRITE_TTL_SECONDS);
                sendResponse(exchange, 200, gson.toJson(movie));
            }
        } catch (SQLException e) {
            logger.error("Database error: {}", e.getMessage(), e);
            sendResponse(exchange, 500, buildError("Internal server error: " + e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------
    // Cache stats endpoint
    // -----------------------------------------------------------------------

    /**
     * Handles GET /movie/cache-stats – returns a JSON report of cache state.
     *
     * <p>Example response:
     * <pre>{@code
     * {
     *   "cacheSize": 5,
     *   "hitRate": "73.33%",
     *   "idleTtlSeconds": 30,
     *   "writeTtlSeconds": 120,
     *   "liveEntries": { "https://...": { ...movie... } }
     * }
     * }</pre>
     */
    private void handleCacheStats(HttpExchange exchange) throws IOException {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("cacheSize",       cache.size());
        stats.put("hitRate",         cache.getHitRate() + "%");
        stats.put("idleTtlSeconds",  CACHE_IDLE_TTL_SECONDS);
        stats.put("writeTtlSeconds", CACHE_WRITE_TTL_SECONDS);
        stats.put("liveEntries",     cache.getMap());
        sendResponse(exchange, 200, gson.toJson(stats));
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

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
