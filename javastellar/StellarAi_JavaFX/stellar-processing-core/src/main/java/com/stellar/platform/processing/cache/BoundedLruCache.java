package com.stellar.platform.processing.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * Bounded access-order LRU. Used for query pages and graph LOD tiles.
 * Not a dump of ConcurrentHashMap.
 */
public final class BoundedLruCache<K, V> {

    private final int maxEntries;
    private final LinkedHashMap<K, V> map;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();

    public BoundedLruCache(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
        this.map = new LinkedHashMap<>(this.maxEntries, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                boolean evict = size() > BoundedLruCache.this.maxEntries;
                if (evict) {
                    evictions.increment();
                }
                return evict;
            }
        };
    }

    public synchronized Optional<V> get(K key) {
        V value = map.get(key);
        if (value == null) {
            misses.increment();
            return Optional.empty();
        }
        hits.increment();
        return Optional.of(value);
    }

    public synchronized V computeIfAbsent(K key, Function<K, V> loader) {
        Objects.requireNonNull(loader, "loader");
        V existing = map.get(key);
        if (existing != null) {
            hits.increment();
            return existing;
        }
        misses.increment();
        V created = loader.apply(key);
        if (created != null) {
            map.put(key, created);
        }
        return created;
    }

    public synchronized void put(K key, V value) {
        if (key == null || value == null) {
            return;
        }
        map.put(key, value);
    }

    public synchronized void invalidate(K key) {
        map.remove(key);
    }

    public synchronized void invalidateGeneration(long generation) {
        map.entrySet().removeIf(entry -> entry.getKey() instanceof GenerationKeyed keyed
                && keyed.generation() != generation);
    }

    public synchronized void clear() {
        map.clear();
    }

    public synchronized int size() {
        return map.size();
    }

    public long hits() {
        return hits.sum();
    }

    public long misses() {
        return misses.sum();
    }

    public long evictions() {
        return evictions.sum();
    }

    public record Stats(long hits, long misses, long evictions, int size) {
    }

    public synchronized Stats stats() {
        return new Stats(hits.sum(), misses.sum(), evictions.sum(), map.size());
    }

    /** Cache keys that include an artifact/query generation so reprocess cannot serve stale pages. */
    public interface GenerationKeyed {
        long generation();
    }
}
