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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GraphPipelineIntegrityTest {
    @TempDir Path temp;

    @Test void losslessStoreIsNotBoundByPreviewRetentionLimitAndPreservesSpike() {
        int total = 70_000;
        try (LosslessGraphSignalStore store = new LosslessGraphSignalStore(temp, "large.mf4", "vehicle.dbc", 4096)) {
            List<CanonicalSignal> batch = new ArrayList<>(1000);
            CanonicalSignal first = null;
            for (int i = 0; i < total; i++) {
                double value = i == total / 2 ? 99_999.0 : i % 5000;
                CanonicalSignal signal = signal(i * 1_000L, i, value);
                if (first == null) first = signal;
                batch.add(signal);
                if (batch.size() == 1000) {
                    store.accept(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) store.accept(batch);

            SignalCatalogKey key = InMemorySignalCatalogStore.keyOf(first, "large.mf4", "vehicle.dbc");
            assertEquals(total, store.sampleCount(key));
            var overview = store.queryBuckets(key, 0, (total - 1L) * 1_000L, 1200);
            assertEquals(total, overview.stream().mapToLong(GraphBucket::sampleCount).sum());
            assertEquals(99_999.0, overview.stream().mapToDouble(GraphBucket::maxValue).max().orElseThrow());
        }
    }

    @Test void diskScanCooperativelyCancelsObsoleteViewportWork() {
        try (LosslessGraphSignalStore store = new LosslessGraphSignalStore(temp, "run.mf4", "vehicle.dbc", 1024)) {
            List<CanonicalSignal> batch = new ArrayList<>();
            for (int i = 0; i < 20_000; i++) batch.add(signal(i, i, i));
            store.accept(batch);
            SignalCatalogKey key = InMemorySignalCatalogStore.keyOf(batch.get(0), "run.mf4", "vehicle.dbc");
            AtomicInteger probes = new AtomicInteger();
            assertThrows(CancellationException.class,
                    () -> store.queryBuckets(key, 0, 19_999, 1000, () -> probes.incrementAndGet() < 3));
            assertTrue(probes.get() >= 3);
        }
    }

    private static CanonicalSignal signal(long ts, long raw, double physical) {
        return new CanonicalSignal("EngineSpeed", "Engine", ts, 0x100, 1, physical, raw, "rpm", null,
                new SourceReference(-1L, ts));
    }
}
