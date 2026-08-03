package org.CrawlUrlPhim.cache;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheStats;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * A generic, thread-safe TTL cache backed by <b>Guava {@link Cache}</b>.
 *
 * <p>Two independent expiry policies are applied simultaneously:
 * <ul>
 *   <li><b>Write TTL ({@code writeTtlSeconds})</b> – an entry is removed
 *       automatically after {@code writeTtlSeconds} seconds from insertion,
 *       regardless of reads (Guava {@code expireAfterWrite}).</li>
 *   <li><b>Idle TTL ({@code idleTtlSeconds})</b> – an entry is removed
 *       automatically after {@code idleTtlSeconds} seconds since the
 *       <em>last</em> successful {@link #get(Object)}.
 *       Each read resets this timer (Guava {@code expireAfterAccess}).</li>
 * </ul>
 *
 * <p>Hit-rate statistics are tracked natively by Guava and exposed via
 * {@link #getHitRate()}.
 *
 * <p>Example usage:
 * <pre>{@code
 *   // idle TTL = 10 s, write TTL = 20 s
 *   CacheTTL<String, Movie> cache = new CacheTTL<>(10, 20);
 *   cache.put("https://example.com/movie/1", movie);
 *   Movie m = cache.get("https://example.com/movie/1"); // cache hit
 * }</pre>
 *
 * @param <K> the type of keys
 * @param <V> the type of values
 */
public class CacheTTL<K, V> implements Map<K, V> {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /** Guava cache that enforces both expiry policies. */
    private final Cache<K, V> guavaCache;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Creates a new {@code CacheTTL} instance backed by Guava Cache.
     *
     * @param idleTtlSeconds  seconds without a read before an entry is evicted
     *                        (maps to {@code expireAfterAccess})
     * @param writeTtlSeconds seconds after write before an entry is evicted
     *                        (maps to {@code expireAfterWrite})
     * @throws IllegalArgumentException if either parameter is &lt;= 0
     */
    public CacheTTL(int idleTtlSeconds, int writeTtlSeconds) {
        if (idleTtlSeconds <= 0 || writeTtlSeconds <= 0) {
            throw new IllegalArgumentException(
                    "TTL values must be positive. Got: idle=" + idleTtlSeconds
                    + ", write=" + writeTtlSeconds);
        }

        guavaCache = CacheBuilder.newBuilder()
                .expireAfterAccess(idleTtlSeconds, TimeUnit.SECONDS)  // idle TTL
                .expireAfterWrite(writeTtlSeconds, TimeUnit.SECONDS)   // write TTL
                .recordStats()   // enables hit/miss statistics via guavaCache.stats()
                .build();
    }

    // -----------------------------------------------------------------------
    // Core cache API
    // -----------------------------------------------------------------------

    /**
     * Returns the value for {@code key}, or {@code null} if absent / expired.
     *
     * <p>On a hit the idle-TTL timer is reset automatically by Guava.
     *
     * @param key the key to look up
     * @return the cached value, or {@code null}
     */
    @Override
    @SuppressWarnings("unchecked")
    public V get(Object key) {
        return guavaCache.getIfPresent((K) key);
    }

    /**
     * Inserts (or replaces) a key-value pair and starts both TTL timers.
     *
     * @param key   the cache key
     * @param value the value to store
     * @return the previous value for this key, or {@code null}
     */
    @Override
    public V put(K key, V value) {
        V previous = guavaCache.getIfPresent(key);
        guavaCache.put(key, value);
        return previous;
    }

    /**
     * Returns an unmodifiable point-in-time snapshot of all live (non-expired)
     * entries as seen by Guava.
     *
     * @return read-only map of live entries
     */
    public Map<K, V> getMap() {
        return Collections.unmodifiableMap(guavaCache.asMap());
    }

    /**
     * Returns the cache hit-rate as a percentage (0–100) rounded to two
     * decimal places.
     *
     * <p>Formula: {@code hitCount / requestCount * 100}.
     * Returns {@code 0.0} if no {@link #get} calls have been made yet.
     *
     * @return hit-rate percentage, e.g. {@code 75.00} means 75 %
     */
    public double getHitRate() {
        CacheStats stats = guavaCache.stats();
        long total = stats.requestCount();
        if (total == 0) return 0.0;
        double rate = stats.hitRate() * 100.0;
        return Math.round(rate * 100.0) / 100.0;
    }

    /**
     * Invalidates all entries immediately.
     * Guava manages its own background cleanup threads internally;
     * no explicit scheduler shutdown is required.
     */
    public void shutdown() {
        guavaCache.invalidateAll();
    }

    // -----------------------------------------------------------------------
    // Map interface – remaining methods
    // -----------------------------------------------------------------------

    @Override public int     size()                          { return (int) guavaCache.size(); }
    @Override public boolean isEmpty()                       { return guavaCache.size() == 0; }
    @Override public boolean containsKey(Object key)        { return guavaCache.getIfPresent(key) != null; }
    @Override public boolean containsValue(Object value)    { return guavaCache.asMap().containsValue(value); }
    @Override public V       remove(Object key)             { V v = guavaCache.getIfPresent(key); guavaCache.invalidate(key); return v; }
    @Override public void    clear()                        { guavaCache.invalidateAll(); }
    @Override public void    putAll(Map<? extends K, ? extends V> m) { guavaCache.putAll(m); }
    @Override public Set<K>              keySet()           { return guavaCache.asMap().keySet(); }
    @Override public Collection<V>       values()           { return guavaCache.asMap().values(); }
    @Override public Set<Map.Entry<K,V>> entrySet()         { return guavaCache.asMap().entrySet(); }

    // -----------------------------------------------------------------------
    // toString (debugging)
    // -----------------------------------------------------------------------

    @Override
    public String toString() {
        CacheStats stats = guavaCache.stats();
        return "CacheTTL{entries=" + guavaCache.size()
               + ", hitRate=" + getHitRate() + "%"
               + ", hits=" + stats.hitCount()
               + ", misses=" + stats.missCount()
               + ", evictions=" + stats.evictionCount()
               + '}';
    }
}
