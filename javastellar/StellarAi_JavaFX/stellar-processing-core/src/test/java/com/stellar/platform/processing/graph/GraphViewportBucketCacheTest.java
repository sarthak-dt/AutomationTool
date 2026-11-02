package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.cache.GraphQueryCache;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphViewportBucketCacheTest {

    @Test
    void allMeasurementsUsesMemberBucketsAndReusesTheSessionCache() {
        SignalCatalogKey delegate = new SignalCatalogKey(
                "VehicleSpeed", SignalSourceType.DBC_CAN, "Vehicle", 0x100L, "raw.dat", "one.dbc");
        LodMember member = new LodMember(delegate);
        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(member, "src-1", "def", "file-a.dat", "one.dbc")
        ), "1 measurement", "one.dbc");
        SignalCatalogKey physical = merged.physicalCatalogAll().getFirst().key();
        GraphSeriesQuery query = new PhysicalIdentityGraphSeriesQuery(merged.graphQuery());
        GraphQueryCache cache = new GraphQueryCache(8);
        GraphViewportEngine engine = new GraphViewportEngine(query, 2, cache);
        GraphViewportRequest request = new GraphViewportRequest(List.of(physical), 0L, 50_000L, 100);

        List<GraphRenderSeries> first = engine.render(request);
        List<GraphRenderSeries> second = engine.render(request);

        assertEquals(1, member.bucketCalls.get());
        assertEquals(0, member.rangeCalls.get());
        assertEquals(1, first.size());
        assertEquals(-500.0, first.getFirst().buckets().getFirst().minValue(), 0);
        assertEquals(1_000.0, first.getFirst().buckets().getFirst().maxValue(), 0);
        assertEquals(first.getFirst().buckets(), second.getFirst().buckets());
        assertTrue(query instanceof CancellableLodGraphSeriesQuery);
    }

    @Test
    void aStaleViewportDoesNotQueryBuckets() {
        SignalCatalogKey delegate = new SignalCatalogKey(
                "VehicleSpeed", SignalSourceType.DBC_CAN, "Vehicle", 0x100L, "raw.dat", "one.dbc");
        LodMember member = new LodMember(delegate);
        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(member, "src-1", "def", "file-a.dat", "one.dbc")
        ), "1 measurement", "one.dbc");
        SignalCatalogKey physical = merged.physicalCatalogAll().getFirst().key();
        GraphViewportEngine engine = new GraphViewportEngine(
                new PhysicalIdentityGraphSeriesQuery(merged.graphQuery()), 2, new GraphQueryCache(4));
        assertThrows(java.util.concurrent.CancellationException.class, () -> engine.render(
                new GraphViewportRequest(List.of(physical), 0L, 50_000L, 100), () -> false));
        assertEquals(0, member.bucketCalls.get());
    }

    private static final class LodMember implements SignalSeriesStore, GraphQueryProvider {
        private final SignalCatalogKey key;
        private final SignalCatalog snapshot;
        private final AtomicInteger bucketCalls = new AtomicInteger();
        private final AtomicInteger rangeCalls = new AtomicInteger();

        private LodMember(SignalCatalogKey key) {
            this.key = key;
            this.snapshot = new SignalCatalog(List.of(new SignalCatalogEntry(
                    key, "km/h", 50_000L, 0L, 50_000L, -500.0, 1_000.0, "raw.dat", "one.dbc")),
                    "raw.dat", "one.dbc");
        }

        @Override
        public SignalCatalog snapshot() {
            return snapshot;
        }

        @Override
        public List<SignalSamplePoint> querySamples(SignalCatalogKey key, int offset, int limit) {
            return List.of();
        }

        @Override
        public int retainedSampleCount(SignalCatalogKey key) {
            return 50_000;
        }

        @Override
        public long retainedWindowStartIndex(SignalCatalogKey key) {
            return 0L;
        }

        @Override
        public GraphSeriesQuery graphQuery() {
            return new CancellableLodGraphSeriesQuery() {
                @Override
                public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
                    rangeCalls.incrementAndGet();
                    return List.of();
                }

                @Override
                public List<SignalSamplePoint> queryRange(
                        SignalCatalogKey key, long startNanos, long endNanos, BooleanSupplier continueQuery) {
                    rangeCalls.incrementAndGet();
                    return List.of();
                }

                @Override
                public List<GraphBucket> queryBuckets(
                        SignalCatalogKey key, long startNanos, long endNanos, int targetBuckets) {
                    return queryBuckets(key, startNanos, endNanos, targetBuckets, () -> true);
                }

                @Override
                public List<GraphBucket> queryBuckets(
                        SignalCatalogKey key, long startNanos, long endNanos, int targetBuckets, BooleanSupplier continueQuery) {
                    bucketCalls.incrementAndGet();
                    return List.of(new GraphBucket(
                            0L, 1, 0L, -500, 1L, 1_000, 1L, 2, 50_000L,
                            SignalValidity.VALID, SignalValidity.VALID, SignalValidity.VALID, SignalValidity.VALID));
                }

                @Override
                public String unit(SignalCatalogKey key) {
                    return "km/h";
                }

                @Override
                public long sampleCount(SignalCatalogKey key) {
                    return 50_000L;
                }
            };
        }
    }
}
