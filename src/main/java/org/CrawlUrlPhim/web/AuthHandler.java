package org.CrawlUrlPhim.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handles {@code POST /login} — authenticates a user and returns a bearer token.
 *
 * <h3>Request</h3>
 * <pre>{@code
 * POST /login
 * Content-Type: application/json
 *
 * {"username": "admin", "password": "admin123"}
 * }</pre>
 *
 * <h3>Success response (200)</h3>
 * <pre>{@code
 * {"token": "<uuid>"}
 * }</pre>
 *
 * <h3>Failure response (401)</h3>
 * <pre>{@code
 * {"error": "Invalid username or password"}
 * }</pre>
 */
public class AuthHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(AuthHandler.class);

    private final AuthManager authManager;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public AuthHandler(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, buildError("Method not allowed. Use POST /login"));
            return;
        }

        // Read request body
        String body;
        try (InputStream is = exchange.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Parse JSON credentials
        String username = null;
        String password = null;
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("username")) username = json.get("username").getAsString();
            if (json.has("password")) password = json.get("password").getAsString();
        } catch (Exception e) {
            sendResponse(exchange, 400, buildError("Invalid JSON body"));
            return;
        }

        // Authenticate
        String token = authManager.authenticate(username, password);
        if (token == null) {
            logger.warn("Login failed for user '{}'", username);
            sendResponse(exchange, 401, buildError("Invalid username or password"));
            return;
        }

        // Return token
        Map<String, String> response = new LinkedHashMap<>();
        response.put("token", token);
        sendResponse(exchange, 200, gson.toJson(response));
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String buildError(String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("error", message);
        return gson.toJson(error);
    }
}
