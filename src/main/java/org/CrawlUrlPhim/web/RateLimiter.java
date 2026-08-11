package org.CrawlUrlPhim.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window rate limiter that enforces two limits per user:
 * <ul>
 *   <li><b>Short window</b>: at most {@value #SHORT_LIMIT} requests within any
 *       {@value #SHORT_WINDOW_MS} ms window (i.e. 2 req / 5 s)</li>
 *   <li><b>Long window</b>: at most {@value #LONG_LIMIT} requests within any
 *       {@value #LONG_WINDOW_MS} ms window (i.e. 10 req / 60 s)</li>
 * </ul>
 *
 * <p>All state is in-memory and thread-safe via per-user {@code synchronized} blocks.
 * Old timestamps are evicted lazily when {@link #isAllowed(String)} is called.
 */
public class RateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimiter.class);

    /** Max requests allowed in the short window (5 seconds). */
    private static final int SHORT_LIMIT = 2;
    /** Short window duration in milliseconds (5 seconds). */
    private static final long SHORT_WINDOW_MS = 5_000L;

    /** Max requests allowed in the long window (1 minute). */
    private static final int LONG_LIMIT = 10;
    /** Long window duration in milliseconds (60 seconds). */
    private static final long LONG_WINDOW_MS = 60_000L;

    /**
     * Stores request timestamps per user.
     * We keep a single deque and evict entries older than LONG_WINDOW_MS.
     */
    private final Map<String, Deque<Long>> requestHistory = new ConcurrentHashMap<>();

    /**
     * Checks whether the given user is allowed to make another request right now.
     *
     * <p>If allowed, the current timestamp is recorded for future checks.
     * If denied, no timestamp is recorded.
     *
     * @param username the authenticated username
     * @return {@code true} if the request is within the allowed rate, {@code false} otherwise
     */
    public boolean isAllowed(String username) {
        Deque<Long> history = requestHistory.computeIfAbsent(username, k -> new ArrayDeque<>());

        synchronized (history) {
            long now = Instant.now().toEpochMilli();

            // Evict timestamps older than the long window (keeps deque small)
            while (!history.isEmpty() && now - history.peekFirst() > LONG_WINDOW_MS) {
                history.pollFirst();
            }

            // Count requests in short window
            long shortWindowStart = now - SHORT_WINDOW_MS;
            long countInShortWindow = history.stream()
                    .filter(ts -> ts >= shortWindowStart)
                    .count();

            if (countInShortWindow >= SHORT_LIMIT) {
                logger.warn("Rate limit (short window) exceeded for user '{}': {} req in last {}ms",
                        username, countInShortWindow, SHORT_WINDOW_MS);
                return false;
            }

            // Count requests in long window (entire deque after eviction)
            long countInLongWindow = history.size();
            if (countInLongWindow >= LONG_LIMIT) {
                logger.warn("Rate limit (long window) exceeded for user '{}': {} req in last {}ms",
                        username, countInLongWindow, LONG_WINDOW_MS);
                return false;
            }

            // Both checks passed — record this request
            history.addLast(now);
            return true;
        }
    }

    /**
     * Returns the number of requests the user has made in the last 60 seconds.
     * Useful for diagnostics / logging.
     *
     * @param username the authenticated username
     * @return count of requests in the last minute
     */
    public int getRequestCountLastMinute(String username) {
        Deque<Long> history = requestHistory.get(username);
        if (history == null) return 0;
        synchronized (history) {
            long cutoff = Instant.now().toEpochMilli() - LONG_WINDOW_MS;
            return (int) history.stream().filter(ts -> ts >= cutoff).count();
        }
    }
}
