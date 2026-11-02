package com.stellar.platform.processing.cache;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphBucket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphQueryCacheTest {

    @Test
    void hitMissEvictionAndGenerationInvalidation() {
        GraphQueryCache cache = new GraphQueryCache(2);
        SignalCatalogKey key = new SignalCatalogKey(
                "RPM", SignalSourceType.DBC_CAN, "ECM", 0x101, 1, "a.mf4", "a.dbc");
        GraphQueryCache.Key first = new GraphQueryCache.Key(1, key, 0L, 100L, 64, 0L);
        GraphQueryCache.Key second = new GraphQueryCache.Key(1, key, 100L, 200L, 64, 0L);
        GraphQueryCache.Key third = new GraphQueryCache.Key(1, key, 200L, 300L, 64, 0L);
        GraphQueryCache.Key otherOrigin = new GraphQueryCache.Key(1, key, 0L, 100L, 64, 40_000_000_000L);
        AtomicInteger loads = new AtomicInteger();
        cache.computeIfAbsent(first, k -> {
            loads.incrementAndGet();
            return List.of(new GraphBucket(0L, 1.0, 0L, 1.0, 0L, 1.0, 0L, 1.0, 1));
        });
        cache.computeIfAbsent(first, k -> {
            loads.incrementAndGet();
            return List.of();
        });
        cache.computeIfAbsent(second, k -> {
            loads.incrementAndGet();
            return List.of(new GraphBucket(100L, 2.0, 100L, 2.0, 100L, 2.0, 100L, 2.0, 1));
        });
        cache.computeIfAbsent(third, k -> {
            loads.incrementAndGet();
            return List.of(new GraphBucket(200L, 3.0, 200L, 3.0, 200L, 3.0, 200L, 3.0, 1));
        });
        cache.computeIfAbsent(otherOrigin, k -> {
            loads.incrementAndGet();
            return List.of();
        });
        assertEquals(4, loads.get());
        assertTrue(cache.stats().hits() >= 1);
        assertTrue(cache.stats().misses() >= 2);
        assertTrue(cache.stats().evictions() >= 1);
        cache.invalidateGeneration(2);
        assertEquals(0, cache.stats().size());
    }
}
