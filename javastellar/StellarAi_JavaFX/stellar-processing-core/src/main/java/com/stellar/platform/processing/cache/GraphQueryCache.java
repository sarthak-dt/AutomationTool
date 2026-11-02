package com.stellar.platform.processing.cache;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.graph.GraphBucket;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Generation-aware, range-aware graph LOD cache. Lives in memory (and can sit in front of
 * disk LOD later). Reprocessing bumps {@code generation} so stale tiles cannot be reused.
 */
public final class GraphQueryCache {

    public record Key(
            long generation,
            SignalCatalogKey signal,
            long startNanos,
            long endNanos,
            int budget,
            long timeOriginNanos
    ) implements BoundedLruCache.GenerationKeyed {
        public Key {
            Objects.requireNonNull(signal, "signal");
            budget = Math.max(1, budget);
        }
    }

    private final BoundedLruCache<Key, List<GraphBucket>> cache;

    public GraphQueryCache(int maxEntries) {
        this.cache = new BoundedLruCache<>(maxEntries);
    }

    public Optional<List<GraphBucket>> get(Key key) {
        return cache.get(key);
    }

    public List<GraphBucket> computeIfAbsent(Key key, java.util.function.Function<Key, List<GraphBucket>> loader) {
        return cache.computeIfAbsent(key, loader);
    }

    public void put(Key key, List<GraphBucket> buckets) {
        cache.put(key, buckets == null ? null : List.copyOf(buckets));
    }

    public void invalidateGeneration(long generation) {
        cache.invalidateGeneration(generation);
    }

    public void clear() {
        cache.clear();
    }

    public BoundedLruCache.Stats stats() {
        return cache.stats();
    }
}
