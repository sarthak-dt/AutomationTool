package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatestViewportCoordinatorTest {

    @Test
    void staleViewportResultCannotOverwriteNewerViewport() throws Exception {
        CountingQuery query = new CountingQuery();
        GraphViewportEngine engine = new GraphViewportEngine(query);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LatestViewportCoordinator coordinator = new LatestViewportCoordinator(engine, executor);
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            query.blockUntil = releaseFirst;
            query.started = firstStarted;

            AtomicInteger accepted = new AtomicInteger();
            AtomicReference<Long> lastStart = new AtomicReference<>(-1L);
            SignalCatalogKey key = new SignalCatalogKey(
                    "S", SignalSourceType.DBC_CAN, "M", 1, 0, "a.mf4", "d.dbc");

            coordinator.submit(new GraphViewportRequest(List.of(key), 0, 100, 80), result -> {
                accepted.incrementAndGet();
                lastStart.set(0L);
            });
            assertTrue(firstStarted.await(3, TimeUnit.SECONDS));

            coordinator.submit(new GraphViewportRequest(List.of(key), 40, 60, 80), result -> {
                accepted.incrementAndGet();
                lastStart.set(40L);
            });
            releaseFirst.countDown();

            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, accepted.get(), "only the latest viewport consumer may run");
            assertEquals(40L, lastStart.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void panQueriesOnlyRequiredViewport() {
        CountingQuery query = new CountingQuery();
        GraphViewportEngine engine = new GraphViewportEngine(query, 1);
        SignalCatalogKey key = new SignalCatalogKey(
                "S", SignalSourceType.DBC_CAN, "M", 1, 0, "a.mf4", "d.dbc");
        engine.render(new GraphViewportRequest(List.of(key), 40, 60, 20));
        assertEquals(40, query.lastFrom);
        assertEquals(60, query.lastTo);
        assertTrue(query.rangeCalls.get() > 0);
        assertTrue(query.maxSpan() <= 20, "viewport query must not scan the full series");
    }

    @Test
    void cursorLookupUsesNearestExactSampleWithoutFullScan() {
        CountingQuery query = new CountingQuery();
        query.points = List.of(
                new SignalSamplePoint(10, 1.0, 1L, null),
                new SignalSamplePoint(20, 2.0, 2L, null),
                new SignalSamplePoint(30, 3.0, 3L, null));
        SignalCatalogKey key = new SignalCatalogKey(
                "S", SignalSourceType.DBC_CAN, "M", 1, 0, "a.mf4", "d.dbc");
        GraphCursorSample nearest = query.nearest(key, 21, 5);
        assertEquals(20, nearest.timestampNanos());
        assertEquals(2.0, nearest.physicalValue());
        assertEquals(21 - 5, query.lastFrom);
        assertEquals(21 + 5, query.lastTo);
    }

    private static final class CountingQuery implements GraphSeriesQuery {
        final AtomicInteger rangeCalls = new AtomicInteger();
        volatile long lastFrom;
        volatile long lastTo;
        volatile CountDownLatch blockUntil;
        volatile CountDownLatch started;
        List<SignalSamplePoint> points = List.of(
                new SignalSamplePoint(0, 0, 0L, null),
                new SignalSamplePoint(50, 1, 1L, null),
                new SignalSamplePoint(100, 2, 2L, null));

        @Override
        public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
            rangeCalls.incrementAndGet();
            lastFrom = startNanos;
            lastTo = endNanos;
            CountDownLatch startedLatch = started;
            if (startedLatch != null) {
                startedLatch.countDown();
            }
            CountDownLatch block = blockUntil;
            if (block != null) {
                try {
                    block.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            List<SignalSamplePoint> out = new ArrayList<>();
            for (SignalSamplePoint p : points) {
                if (p.timestampNanos() >= startNanos && p.timestampNanos() <= endNanos) {
                    out.add(p);
                }
            }
            return out;
        }

        @Override
        public String unit(SignalCatalogKey key) {
            return "u";
        }

        @Override
        public long sampleCount(SignalCatalogKey key) {
            return points.size();
        }

        long maxSpan() {
            return lastTo - lastFrom;
        }
    }
}
