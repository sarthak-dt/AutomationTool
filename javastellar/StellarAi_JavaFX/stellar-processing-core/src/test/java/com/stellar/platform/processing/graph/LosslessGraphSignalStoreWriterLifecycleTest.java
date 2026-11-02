package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Writer ownership / buffer lifecycle for {@link LosslessGraphSignalStore}.
 *
 * <p>Memory math: each open writer uses {@link LosslessGraphSignalStore#SEGMENT_BUFFER_BYTES}
 * (16 KiB). ~745 open writers ≈ 12 MB peak during a session; after
 * {@link LosslessGraphSignalStore#finishWriteSession()} activeWriterCount is 0.
 * The prior 256 KiB × 745 ≈ 190 MB retention was post-processing buffer retention, not samples.</p>
 */
class LosslessGraphSignalStoreWriterLifecycleTest {
    @TempDir Path temp;

    @Test
    void segmentBufferSizeIs16KiBNot256KiB() {
        assertEquals(16 * 1024, LosslessGraphSignalStore.SEGMENT_BUFFER_BYTES);
        assertTrue(LosslessGraphSignalStore.SEGMENT_BUFFER_BYTES < 64 * 1024);
    }

    @Test
    void activeWriterCountIsZeroAfterFinishWriteSession() {
        LosslessGraphSignalStore store = new LosslessGraphSignalStore(temp, "run.mf4", "veh.dbc", 64);
        try {
            store.accept(List.of(signal("RPM", 0, 1)));
            store.accept(List.of(signal("TEMP", 1, 2)));
            assertTrue(store.activeWriterCount() >= 1);
            store.finishWriteSession();
            assertEquals(0, store.activeWriterCount());
            assertTrue(store.hasIndex());
        } finally {
            store.close();
        }
    }

    @Test
    void queriesWorkAfterWritersAreClosed() {
        LosslessGraphSignalStore store = new LosslessGraphSignalStore(temp, "run.mf4", "veh.dbc", 64);
        try {
            List<CanonicalSignal> batch = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                batch.add(signal("RPM", i * 10L, i));
            }
            store.accept(batch);
            SignalCatalogKey key = InMemorySignalCatalogStore.keyOf(batch.get(0), "run.mf4", "veh.dbc");
            store.finishWriteSession();
            assertEquals(0, store.activeWriterCount());

            assertEquals(20, store.sampleCount(key));
            List<SignalSamplePoint> range = store.queryRange(key, 0, 190);
            assertEquals(20, range.size());
            assertEquals(0, range.get(0).timestampNanos());
            assertEquals(190, range.get(19).timestampNanos());
            assertFalse(store.queryBuckets(key, 0, 190, 4).isEmpty());
        } finally {
            store.close();
        }
    }

    @Test
    void incrementalAcceptReopensWritersInAppendMode() {
        Path root = temp.resolve("session");
        LosslessGraphSignalStore store = new LosslessGraphSignalStore(root, "run.mf4", "veh.dbc", 100);
        SignalCatalogKey key;
        try {
            store.accept(List.of(signal("RPM", 0, 1), signal("RPM", 10, 2)));
            key = InMemorySignalCatalogStore.keyOf(signal("RPM", 0, 1), "run.mf4", "veh.dbc");
            store.finishWriteSession();
            assertEquals(0, store.activeWriterCount());
            assertEquals(2, store.sampleCount(key));

            store.resetWriterIoStats();
            store.accept(List.of(signal("RPM", 20, 3), signal("RPM", 30, 4)));
            LosslessGraphSignalStore.WriterIoStats mid = store.writerIoStats();
            assertEquals(1, mid.opens(), "single logical writer should open once after session restart");
            assertEquals(1, mid.reopens());
            assertTrue(store.activeWriterCount() >= 1);
            store.finishWriteSession();
            assertEquals(0, store.activeWriterCount());
            assertEquals(4, store.sampleCount(key));
            assertEquals(4, store.queryRange(key, 0, 30).size());
        } finally {
            store.close();
        }

        try (LosslessGraphSignalStore reopened = LosslessGraphSignalStore.openExisting(root)) {
            assertEquals(0, reopened.activeWriterCount());
            assertEquals(4, reopened.sampleCount(key));
            reopened.accept(List.of(signal("RPM", 40, 5)));
            reopened.finishWriteSession();
            assertEquals(0, reopened.activeWriterCount());
            assertEquals(5, reopened.sampleCount(key));
        }
    }

    @Test
    void failurePathCloseAllWritersReleasesStreams() {
        LosslessGraphSignalStore store = new LosslessGraphSignalStore(temp, "run.mf4", "veh.dbc", 64);
        try {
            store.accept(List.of(signal("A", 0, 1), signal("B", 1, 2), signal("C", 2, 3)));
            assertTrue(store.activeWriterCount() > 0);
            store.closeAllWriters();
            assertEquals(0, store.activeWriterCount());
            assertEquals(3, store.keys().size());
            assertTrue(store.hasIndex());
        } finally {
            store.close();
        }
    }

    @Test
    void highCardinalityInterleavedWritesDoNotThrashWriters() {
        int signals = 1_100;
        int rounds = 20;
        LosslessGraphSignalStore store = new LosslessGraphSignalStore(temp, "run.mf4", "veh.dbc", 100_000);
        try {
            store.resetWriterIoStats();
            long t0 = System.nanoTime();
            long ts = 0;
            for (int round = 0; round < rounds; round++) {
                List<CanonicalSignal> batch = new ArrayList<>(signals);
                for (int i = 0; i < signals; i++) {
                    batch.add(signal("SIG_" + i, ts++, i + round));
                }
                store.accept(batch);
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            LosslessGraphSignalStore.WriterIoStats stats = store.writerIoStats();

            assertEquals(signals, store.keys().size());
            assertEquals(signals, store.activeWriterCount(), "all writers stay open for the session");
            assertEquals(0, stats.evictions());
            assertEquals(signals, stats.opens(),
                    "opens should equal logical signals, not samples; stats=" + stats);
            assertEquals(0, stats.reopens(), "no mid-session reopen/thrashing; stats=" + stats);
            assertEquals((long) signals * rounds, stats.appends());
            assertFalse(stats.indicatesThrashing(signals), "thrashing detected: " + stats);
            assertTrue(elapsedMs < 30_000,
                    "interleaved write session too slow (likely thrashing): " + elapsedMs + " ms");

            store.finishWriteSession();
            assertEquals(0, store.activeWriterCount());
            assertEquals((long) signals * rounds, store.totalSampleCount());

            SignalCatalogKey key = InMemorySignalCatalogStore.keyOf(
                    signal("SIG_0", 0, 0), "run.mf4", "veh.dbc");
            assertEquals(rounds, store.queryRange(key, 0, Long.MAX_VALUE).size());
        } finally {
            store.close();
        }
    }

    @Test
    void performanceRegressionInterleavedWritersStayFast() {
        int signals = 500;
        int rounds = 40;
        LosslessGraphSignalStore store = new LosslessGraphSignalStore(temp, "run.mf4", "veh.dbc", 100_000);
        try {
            long t0 = System.nanoTime();
            long ts = 0;
            for (int round = 0; round < rounds; round++) {
                List<CanonicalSignal> batch = new ArrayList<>(signals);
                for (int i = 0; i < signals; i++) {
                    batch.add(signal("P_" + i, ts++, i));
                }
                store.accept(batch);
            }
            store.finishWriteSession();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            LosslessGraphSignalStore.WriterIoStats stats = store.writerIoStats();

            assertEquals(signals, stats.opens());
            assertEquals(0, stats.reopens());
            assertEquals(0, stats.evictions());
            assertEquals(0, store.activeWriterCount());
            // 20k samples across 500 files should complete well under a few seconds without thrashing.
            assertTrue(elapsedMs < 15_000, "throughput regression: " + elapsedMs + " ms, stats=" + stats);
        } finally {
            store.close();
        }
    }

    @Test
    void sessionStoreFinishWriteSessionDelegates() {
        try (GraphSignalSessionStore session =
                     GraphSignalSessionStore.createPersistent(temp.resolve("p"), "m.mf4", "d.dbc")) {
            session.accept(List.of(signal("RPM", 0, 1)));
            assertTrue(session.activeWriterCount() >= 1);
            session.finishWriteSession();
            assertEquals(0, session.activeWriterCount());
            SignalCatalogKey key = InMemorySignalCatalogStore.keyOf(signal("RPM", 0, 1), "m.mf4", "d.dbc");
            assertEquals(1, session.retainedSampleCount(key));
            assertEquals(1, session.querySamples(key, 0, 10).size());
        }
    }

    @Test
    void reportsWriterIoCountersForNormalRun() {
        LosslessGraphSignalStore store = new LosslessGraphSignalStore(temp, "run.mf4", "veh.dbc", 1_000);
        try {
            store.resetWriterIoStats();
            int n = 50;
            for (int i = 0; i < n; i++) {
                store.accept(List.of(signal("S_" + i, i, i)));
            }
            for (int i = 0; i < n; i++) {
                store.accept(List.of(signal("S_" + i, 1_000 + i, i)));
            }
            LosslessGraphSignalStore.WriterIoStats before = store.writerIoStats();
            assertEquals(n, before.opens());
            assertEquals(0, before.reopens());
            assertEquals(0, before.closes());
            assertEquals(0, before.evictions());
            assertEquals(n * 2L, before.appends());
            assertTrue(before.bytesWritten() > 0);
            System.out.println("WriterIoStats(normalRun)=" + before);

            store.finishWriteSession();
            LosslessGraphSignalStore.WriterIoStats after = store.writerIoStats();
            assertEquals(n, after.closes());
            assertEquals(0, store.activeWriterCount());
            System.out.println("WriterIoStats(afterFinish)=" + after);
        } finally {
            store.close();
        }
    }

    private static CanonicalSignal signal(String name, long ts, long raw) {
        return new CanonicalSignal(name, "Engine", ts, 0x100, 1, raw * 1.5, raw, "rpm", null,
                new SourceReference(-1L, ts));
    }
}
