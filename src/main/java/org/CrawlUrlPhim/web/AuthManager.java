package org.CrawlUrlPhim.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages user authentication with in-memory token store.
 *
 * <p>Hardcoded users (username → password):
 * <ul>
 *   <li>admin / admin123</li>
 *   <li>user1 / pass1</li>
 * </ul>
 *
 * <p>Tokens are UUID strings stored in memory. They do not expire in this
 * implementation (can be extended with TTL if needed).
 */
public class AuthManager {

    private static final Logger logger = LoggerFactory.getLogger(AuthManager.class);

    /** Hardcoded user credentials: username → password. */
    private static final Map<String, String> USERS = Map.of(
            "admin", "admin123",
            "user1", "pass1"
    );

    /** Active tokens: token → username. */
    private final Map<String, String> activeTokens = new ConcurrentHashMap<>();

    /**
     * Validates credentials and, if correct, generates and stores a new token.
     *
     * @param username the submitted username
     * @param password the submitted password
     * @return a new UUID token on success, or {@code null} if credentials are invalid
     */
    public String authenticate(String username, String password) {
        if (username == null || password == null) return null;
        String expected = USERS.get(username);
        if (expected == null || !expected.equals(password)) {
            logger.warn("Failed login attempt for user '{}'", username);
            return null;
        }
        String token = UUID.randomUUID().toString();
        activeTokens.put(token, username);
        logger.info("User '{}' logged in successfully, token issued.", username);
        return token;
    }

    /**
     * Validates a token and returns the associated username.
     *
     * @param token the bearer token from the Authorization header
     * @return the username owning this token, or {@code null} if invalid/unknown
     */
    public String validateToken(String token) {
        if (token == null || token.isBlank()) return null;
        return activeTokens.get(token);
    }

    /**
     * Revokes a token (logout).
     *
     * @param token the token to invalidate
     */
    public void revokeToken(String token) {
        String user = activeTokens.remove(token);
        if (user != null) {
            logger.info("Token revoked for user '{}'", user);
        }
    }
}
