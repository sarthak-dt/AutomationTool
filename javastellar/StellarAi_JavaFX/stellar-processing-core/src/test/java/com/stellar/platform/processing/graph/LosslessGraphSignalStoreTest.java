package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class LosslessGraphSignalStoreTest {
    @TempDir Path temp;

    @Test void persistsEverySampleAcrossSegmentsAndQueriesByTime() {
        try (LosslessGraphSignalStore store = new LosslessGraphSignalStore(temp, "run.mf4", "veh.dbc", 3)) {
            List<CanonicalSignal> batch = new ArrayList<>();
            for (int i = 0; i < 10; i++) batch.add(signal(i * 10L, i, i == 5 ? "STATE" : null));
            store.accept(batch);
            SignalCatalogKey key = InMemorySignalCatalogStore.keyOf(batch.get(0), "run.mf4", "veh.dbc");
            assertEquals(10, store.sampleCount(key));
            var range = store.queryRange(key, 20, 60);
            assertEquals(5, range.size());
            assertEquals(20, range.get(0).timestampNanos());
            assertEquals(60, range.get(4).timestampNanos());
            assertEquals("STATE", range.get(3).textualValue());
            assertEquals(5L, range.get(3).rawValue());
            var buckets = store.queryBuckets(key, 0, 90, 3);
            assertTrue(buckets.size() <= 3);
            assertEquals(10L, buckets.stream().mapToLong(GraphBucket::sampleCount).sum());
            GraphCursorSample nearest = store.nearest(key, 53, 20);
            assertNotNull(nearest);
            assertEquals(50, nearest.timestampNanos());
            assertEquals(7.5, nearest.physicalValue());
            assertEquals("rpm", nearest.unit());
        }
    }

    @Test void limitedRangeQueryStopsWithoutTreatingTheLimitAsCancellation() {
        try (LosslessGraphSignalStore store = new LosslessGraphSignalStore(temp.resolve("limited"), "run.mf4", "veh.dbc")) {
            List<CanonicalSignal> batch = new ArrayList<>();
            for (int i = 0; i < 1_100; i++) {
                batch.add(signal(i, i, null));
            }
            store.accept(batch);
            SignalCatalogKey key = InMemorySignalCatalogStore.keyOf(batch.getFirst(), "run.mf4", "veh.dbc");
            var page = store.queryRange(key, 0, 2_000, 8);
            assertEquals(8, page.size());
            assertEquals(0, page.getFirst().timestampNanos());
            assertEquals(7, page.get(7).timestampNanos());
            assertEquals(1_100, store.sampleCount(key));
            CancellationException cancelled = assertThrows(CancellationException.class,
                    () -> store.queryRange(key, 0, 2_000, 8, () -> false));
            assertEquals("Obsolete graph query", cancelled.getMessage());
            assertEquals(1_100, store.sampleCount(key));
        }
    }

    private static CanonicalSignal signal(long ts, long raw, String text) {
        return new CanonicalSignal("RPM", "Engine", ts, 0x100, 1, raw * 1.5, raw, "rpm", text,
                new SourceReference(-1L, ts));
    }
}
