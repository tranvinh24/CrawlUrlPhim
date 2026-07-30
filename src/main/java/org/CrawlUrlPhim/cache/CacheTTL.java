package org.CrawlUrlPhim.cache;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A generic, thread-safe TTL (Time-To-Live) cache that implements {@link Map}.
 *
 * <p>Two independent expiry policies are applied simultaneously:
 * <ul>
 *   <li><b>Write TTL ({@code writeTtlSeconds} = m)</b> – an entry is removed
 *       automatically {@code m} seconds after it was inserted via
 *       {@link #put(Object, Object)}, regardless of reads.</li>
 *   <li><b>Idle TTL ({@code idleTtlSeconds} = n)</b> – an entry is removed
 *       automatically {@code n} seconds after the <em>last</em> successful
 *       {@link #get(Object)}.  Each read resets this timer.</li>
 * </ul>
 *
 * <p>Hit-rate statistics are tracked via {@link #getHitRate()}.
 *
 * <p>Example usage:
 * <pre>{@code
 *   // n = idle TTL (s), m = write TTL (s)
 *   CacheTTL<String, Movie> cache = new CacheTTL<>(30, 120);
 *   cache.put("https://example.com/movie/1", movie);
 *   Movie m = cache.get("https://example.com/movie/1"); // cache hit
 * }</pre>
 *
 * @param <K> the type of keys
 * @param <V> the type of values
 */
public class CacheTTL<K, V> implements Map<K, V> {

    // -----------------------------------------------------------------------
    // Inner entry wrapper
    // -----------------------------------------------------------------------

    /**
     * Wraps a cached value together with its two expiry timestamps.
     */
    private final class Entry {
        final V value;
        /** Absolute expiry time based on write-TTL (epoch ms). */
        final long writeExpireAt;
        /** Absolute expiry time based on idle-TTL (epoch ms). Reset on every read. */
        volatile long idleExpireAt;

        Entry(V value) {
            long now          = System.currentTimeMillis();
            this.value        = value;
            this.writeExpireAt = now + writeTtlMs;
            this.idleExpireAt  = now + idleTtlMs;
        }

        /** Returns {@code true} if this entry has passed either TTL deadline. */
        boolean isExpired() {
            long now = System.currentTimeMillis();
            return now >= writeExpireAt || now >= idleExpireAt;
        }

        /** Resets the idle-TTL countdown (called on every successful read). */
        void touch() {
            this.idleExpireAt = System.currentTimeMillis() + idleTtlMs;
        }
    }

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /** Idle TTL in milliseconds (= n * 1000). */
    private final long idleTtlMs;

    /** Write TTL in milliseconds (= m * 1000). */
    private final long writeTtlMs;

    /** Backing concurrent map – thread-safe without explicit synchronisation. */
    private final ConcurrentHashMap<K, Entry> store = new ConcurrentHashMap<>();

    /** Background scheduler that runs the periodic eviction sweep. */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "CacheTTL-eviction");
                t.setDaemon(true);   // won't prevent JVM shutdown
                return t;
            });

    // Hit-rate counters
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong cacheHits     = new AtomicLong(0);

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Creates a new {@code CacheTTL} instance.
     *
     * @param idleTtlSeconds  {@code n} – seconds without a read before entry is evicted
     * @param writeTtlSeconds {@code m} – seconds after write before entry is evicted
     * @throws IllegalArgumentException if either parameter is &lt;= 0
     */
    public CacheTTL(int idleTtlSeconds, int writeTtlSeconds) {
        if (idleTtlSeconds <= 0 || writeTtlSeconds <= 0) {
            throw new IllegalArgumentException(
                    "TTL values must be positive. Got: idle=" + idleTtlSeconds
                    + ", write=" + writeTtlSeconds);
        }
        this.idleTtlMs  = idleTtlSeconds  * 1_000L;
        this.writeTtlMs = writeTtlSeconds * 1_000L;

        // Schedule eviction sweep at half of the shorter TTL (min 500 ms)
        long sweepIntervalMs = Math.max(Math.min(idleTtlMs, writeTtlMs) / 2, 500L);
        scheduler.scheduleAtFixedRate(
                this::evict, sweepIntervalMs, sweepIntervalMs, TimeUnit.MILLISECONDS);
    }

    // -----------------------------------------------------------------------
    // Core cache API
    // -----------------------------------------------------------------------

    /**
     * Returns the value for {@code key}, or {@code null} if absent / expired.
     *
     * <p>On a hit the idle-TTL timer is reset.
     * On a miss (absent or expired) nothing is inserted.
     *
     * @param key the key to look up
     * @return the cached value, or {@code null}
     */
    @Override
    @SuppressWarnings("unchecked")
    public V get(Object key) {
        totalRequests.incrementAndGet();

        Entry entry = store.get(key);
        if (entry == null) {
            return null;           // miss – key not present
        }
        if (entry.isExpired()) {
            store.remove(key, entry);  // lazy eviction
            return null;               // miss – expired
        }

        // Cache hit: reset idle timer
        entry.touch();
        cacheHits.incrementAndGet();
        return entry.value;
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
        Entry previous = store.put(key, new Entry(value));
        return previous != null ? previous.value : null;
    }

    /**
     * Returns an unmodifiable point-in-time snapshot of all live (non-expired)
     * entries.  Triggers an eviction sweep first to maximise freshness.
     *
     * @return read-only map of live entries
     */
    public Map<K, V> getMap() {
        evict();
        Map<K, V> snapshot = new LinkedHashMap<>();
        store.forEach((k, e) -> {
            if (!e.isExpired()) snapshot.put(k, e.value);
        });
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Returns the cache hit-rate as a percentage (0–100) rounded to two
     * decimal places.
     *
     * <p>Formula: (cacheHits / totalGetCalls) * 100.
     * Returns {@code 0.0} if no {@link #get} calls have been made yet.
     *
     * @return hit-rate percentage, e.g. {@code 75.00} means 75 %
     */
    public double getHitRate() {
        long total = totalRequests.get();
        if (total == 0) return 0.0;
        double rate = (double) cacheHits.get() / total * 100.0;
        return Math.round(rate * 100.0) / 100.0;
    }

    /**
     * Shuts down the background eviction thread.
     * Call when the cache is no longer needed to avoid thread leaks.
     */
    public void shutdown() {
        scheduler.shutdownNow();
    }

    // -----------------------------------------------------------------------
    // Eviction
    // -----------------------------------------------------------------------

    /** Removes all expired entries from the backing map. */
    private void evict() {
        store.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    // -----------------------------------------------------------------------
    // Map interface – remaining methods
    // -----------------------------------------------------------------------

    @Override public int     size()                          { evict(); return store.size(); }
    @Override public boolean isEmpty()                       { evict(); return store.isEmpty(); }
    @Override public boolean containsKey(Object key)        { Entry e = store.get(key); return e != null && !e.isExpired(); }
    @Override public boolean containsValue(Object value)    { return getMap().containsValue(value); }
    @Override public V       remove(Object key)             { Entry e = store.remove(key); return e != null ? e.value : null; }
    @Override public void    clear()                        { store.clear(); }
    @Override public void    putAll(Map<? extends K, ? extends V> m) { m.forEach(this::put); }
    @Override public Set<K>              keySet()           { return getMap().keySet(); }
    @Override public Collection<V>       values()           { return getMap().values(); }
    @Override public Set<Map.Entry<K,V>> entrySet()         { return getMap().entrySet(); }

    // -----------------------------------------------------------------------
    // toString (debugging)
    // -----------------------------------------------------------------------

    @Override
    public String toString() {
        return "CacheTTL{entries=" + size()
               + ", idleTtl=" + (idleTtlMs / 1000) + "s"
               + ", writeTtl=" + (writeTtlMs / 1000) + "s"
               + ", hitRate=" + getHitRate() + "%" + '}';
    }
}
