package com.stellar.platform.processing.align;

import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlignedSignalWorkspaceQueryTest {

    @Test
    void oneFiveAndTwentySignalsStayBounded() {
        for (int signalCount : List.of(1, 5, 20)) {
            CountingStore store = CountingStore.dense(signalCount, 50_000);
            List<SignalCatalogKey> keys = store.keys();
            AlignedSignalWorkspaceQuery.Result page = AlignedSignalWorkspaceQuery.query(
                    store, AlignedSignalWorkspaceQuery.Request.first(keys, 500));
            assertEquals(500, page.rows().size(), "signals=" + signalCount);
            assertTrue(page.samplesRead() <= signalCount * 500,
                    "read " + page.samplesRead() + " for " + signalCount + " signals");
            assertTrue(store.maxPageLimit() <= 500,
                    "store issued a page larger than the workspace window");
            assertTrue(store.fullSeriesLoads() == 0, "full series must never be copied");
        }
    }

    @Test
    void goToTimeAndIndexDoNotScanTheWholeSeries() {
        CountingStore store = CountingStore.dense(3, 80_000);
        List<SignalCatalogKey> keys = store.keys();
        AlignedSignalWorkspaceQuery.Result atTime = AlignedSignalWorkspaceQuery.query(
                store, AlignedSignalWorkspaceQuery.Request.atTimestamp(keys, 200, 40_000L * 1_000_000L));
        assertEquals(200, atTime.rows().size());
        assertTrue(atTime.rows().getFirst().timestampNanos() >= 40_000L * 1_000_000L);
        assertTrue(store.samplesReturned() < 20_000, "seek must not stream tens of thousands of samples");

        store.resetCounts();
        AlignedSignalWorkspaceQuery.Result atIndex = AlignedSignalWorkspaceQuery.query(
                store, AlignedSignalWorkspaceQuery.Request.atIndex(keys, 200, 60_000));
        assertEquals(200, atIndex.rows().size());
        assertTrue(store.samplesReturned() < 20_000);
        assertEquals(0, store.fullSeriesLoads());
    }

    @Test
    void pageSizeStepsTheFullWindowWhenTheStoreCapsReadsAtOneThousand() {
        int total = 18_007;
        for (int pageSize : List.of(100, 1_000, 5_000, 10_000)) {
            CappedStore store = CappedStore.uniform(total, 1_000);
            List<SignalCatalogKey> keys = List.of(store.key());
            AlignedSignalWorkspaceQuery.Result page = AlignedSignalWorkspaceQuery.query(
                    store, AlignedSignalWorkspaceQuery.Request.first(keys, pageSize));
            int expected = Math.min(pageSize, total);
            assertEquals(expected, page.rows().size(), "pageSize=" + pageSize);
            assertEquals(0, page.windowStartIndex(), "pageSize=" + pageSize);
            assertEquals(0L, page.rows().getFirst().index());
            assertEquals(expected - 1L, page.rows().getLast().index());

            int seen = page.rows().size();
            int guard = 0;
            while (page.hasMoreAfter()) {
                assertTrue(++guard < 500, "pagination did not reach the end for pageSize=" + pageSize);
                long lastTs = page.rows().getLast().timestampNanos();
                int origin = page.windowStartIndex();
                int count = page.rows().size();
                page = AlignedSignalWorkspaceQuery.query(
                        store, AlignedSignalWorkspaceQuery.Request.next(keys, pageSize, lastTs, origin, count));
                assertFalse(page.rows().isEmpty(), "pageSize=" + pageSize);
                assertEquals(origin + count, page.windowStartIndex(), "pageSize=" + pageSize);
                assertEquals(origin + count, page.rows().getFirst().index());
                assertEquals(page.rows().getFirst().index() + page.rows().size() - 1L, page.rows().getLast().index());
                seen += page.rows().size();
            }
            assertEquals(total, seen, "pageSize=" + pageSize);
            assertEquals(total - 1L, page.rows().getLast().index());
        }
    }

    @Test
    void directIndexAndPreviousUseTheSamePageSize() {
        CappedStore store = CappedStore.uniform(18_007, 1_000);
        List<SignalCatalogKey> keys = List.of(store.key());
        AlignedSignalWorkspaceQuery.Result first = AlignedSignalWorkspaceQuery.query(
                store, AlignedSignalWorkspaceQuery.Request.first(keys, 5_000));
        AlignedSignalWorkspaceQuery.Result second = AlignedSignalWorkspaceQuery.query(
                store, AlignedSignalWorkspaceQuery.Request.next(
                        keys, 5_000, first.rows().getLast().timestampNanos(),
                        first.windowStartIndex(), first.rows().size()));
        assertEquals(5_000, second.windowStartIndex());
        assertEquals(5_000, second.rows().size());
        assertEquals(9_999L, second.rows().getLast().index());

        AlignedSignalWorkspaceQuery.Result back = AlignedSignalWorkspaceQuery.query(
                store, AlignedSignalWorkspaceQuery.Request.previous(
                        keys, 5_000, second.rows().getFirst().timestampNanos(), second.windowStartIndex()));
        assertEquals(0, back.windowStartIndex());
        assertEquals(0L, back.rows().getFirst().index());
        assertEquals(4_999L, back.rows().getLast().index());

        AlignedSignalWorkspaceQuery.Result last = AlignedSignalWorkspaceQuery.query(
                store, AlignedSignalWorkspaceQuery.Request.atIndex(keys, 5_000, 18_006));
        assertEquals(15_000, last.windowStartIndex());
        assertEquals(15_000L, last.rows().getFirst().index());
        assertEquals(18_006L, last.rows().getLast().index());
        assertEquals(3_007, last.rows().size());
    }

    @Test
    void nextAdvancesByAlignedRowsWhenThePrimarySignalIsSparser() {
        SignalCatalogKey primary = new SignalCatalogKey(
                "Primary", SignalSourceType.DBC_CAN, "Slow", 1L, 1, "run.mf4", "veh.dbc");
        SignalCatalogKey dense = new SignalCatalogKey(
                "Dense", SignalSourceType.DBC_CAN, "Fast", 2L, 2, "run.mf4", "veh.dbc");
        Map<SignalCatalogKey, List<SignalSamplePoint>> series = new LinkedHashMap<>();
        List<SignalSamplePoint> slow = new ArrayList<>();
        for (int i = 0; i < 4_000; i++) {
            slow.add(new SignalSamplePoint(i * 5L, i, (long) i, null));
        }
        List<SignalSamplePoint> fast = new ArrayList<>();
        for (int t = 0; t < 18_000; t++) {
            fast.add(new SignalSamplePoint(t, t, (long) t, null));
        }
        series.put(primary, List.copyOf(slow));
        series.put(dense, List.copyOf(fast));
        CappedStore store = new CappedStore(series, 1_000);
        List<SignalCatalogKey> keys = List.of(primary, dense);

        AlignedSignalWorkspaceQuery.Result first = AlignedSignalWorkspaceQuery.query(
                store, AlignedSignalWorkspaceQuery.Request.first(keys, 5_000));
        assertEquals(5_000, first.rows().size());
        assertEquals(0, first.windowStartIndex());
        assertEquals(4_999L, first.rows().getLast().timestampNanos());

        AlignedSignalWorkspaceQuery.Result second = AlignedSignalWorkspaceQuery.query(
                store, AlignedSignalWorkspaceQuery.Request.next(
                        keys, 5_000, first.rows().getLast().timestampNanos(),
                        first.windowStartIndex(), first.rows().size()));
        assertEquals(5_000, second.windowStartIndex());
        assertEquals(5_000L, second.rows().getFirst().index());
        assertEquals(5_000L, second.rows().getFirst().timestampNanos());
        assertEquals(9_999L, second.rows().getLast().index());
        AlignedSignalRow missing = second.rows().get(1);
        assertEquals(5_001L, missing.timestampNanos());
        assertNull(missing.physicalAt(0));
        assertEquals(5_001.0, missing.physicalAt(1));
    }

    @Test
    void typedIndexUsesAlignedRowNotPrimarySampleIndex() {
        SignalCatalogKey primary = new SignalCatalogKey(
                "Primary", SignalSourceType.DBC_CAN, "Slow", 1L, 1, "run.mf4", "veh.dbc");
        SignalCatalogKey dense = new SignalCatalogKey(
                "Dense", SignalSourceType.DBC_CAN, "Fast", 2L, 2, "run.mf4", "veh.dbc");
        Map<SignalCatalogKey, List<SignalSamplePoint>> series = new LinkedHashMap<>();
        List<SignalSamplePoint> slow = new ArrayList<>();
        for (int i = 0; i < 4_000; i++) {
            slow.add(new SignalSamplePoint(i * 5L, i, (long) i, null));
        }
        List<SignalSamplePoint> fast = new ArrayList<>();
        for (int t = 0; t < 18_000; t++) {
            fast.add(new SignalSamplePoint(t, t, (long) t, null));
        }
        series.put(primary, List.copyOf(slow));
        series.put(dense, List.copyOf(fast));
        CappedStore store = new CappedStore(series, 1_000);

        AlignedSignalWorkspaceQuery.Result jumped = AlignedSignalWorkspaceQuery.query(
                store, AlignedSignalWorkspaceQuery.Request.atIndex(List.of(primary, dense), 5_000, 5_001));
        assertEquals(5_000, jumped.windowStartIndex());
        assertEquals(5_001L, jumped.rows().get(1).index());
        assertEquals(5_001L, jumped.rows().get(1).timestampNanos());
        assertNull(jumped.rows().get(1).physicalAt(0));
        assertEquals(5_001.0, jumped.rows().get(1).physicalAt(1));
    }

    @Test
    void goToTimeKeepsUnionIndexWhenAnotherSignalStartsEarlier() {
        SignalCatalogKey primary = new SignalCatalogKey(
                "Primary", SignalSourceType.DBC_CAN, "Slow", 1L, 1, "run.mf4", "veh.dbc");
        SignalCatalogKey earlier = new SignalCatalogKey(
                "Earlier", SignalSourceType.DBC_CAN, "Gps", 2L, 2, "run.mf4", "veh.dbc");
        Map<SignalCatalogKey, List<SignalSamplePoint>> series = new LinkedHashMap<>();
        series.put(primary, List.of(new SignalSamplePoint(20L, 2.0, 2L, null)));
        series.put(earlier, List.of(
                new SignalSamplePoint(10L, 1.0, 1L, null),
                new SignalSamplePoint(30L, 3.0, 3L, null)));
        CappedStore store = new CappedStore(series, 1_000);

        AlignedSignalWorkspaceQuery.Result jumped = AlignedSignalWorkspaceQuery.query(
                store, AlignedSignalWorkspaceQuery.Request.atTimestamp(List.of(primary, earlier), 10, 20L));
        assertEquals(1, jumped.windowStartIndex());
        assertEquals(1L, jumped.rows().getFirst().index());
        assertEquals(20L, jumped.rows().getFirst().timestampNanos());
        assertEquals(2.0, jumped.rows().getFirst().physicalAt(0));
        assertNull(jumped.rows().getFirst().physicalAt(1));

        AlignedSignalWorkspaceQuery.Result last = AlignedSignalWorkspaceQuery.query(
                store, AlignedSignalWorkspaceQuery.Request.atTimestamp(List.of(primary, earlier), 10, 30L));
        assertEquals(2L, last.rows().getFirst().index());
        assertEquals(30L, last.rows().getFirst().timestampNanos());
        assertNull(last.rows().getFirst().physicalAt(0));
    }

    /** In-memory store that records every page request so a full-series copy is visible. */
    static final class CountingStore implements SignalSeriesStore {
        private final List<SignalCatalogKey> keys;
        private final List<List<SignalSamplePoint>> series;
        private final AtomicInteger samplesReturned = new AtomicInteger();
        private final AtomicInteger maxPageLimit = new AtomicInteger();
        private final AtomicInteger fullSeriesLoads = new AtomicInteger();

        private CountingStore(List<SignalCatalogKey> keys, List<List<SignalSamplePoint>> series) {
            this.keys = keys;
            this.series = series;
        }

        static CountingStore dense(int signalCount, int samplesPerSignal) {
            List<SignalCatalogKey> keys = new ArrayList<>(signalCount);
            List<List<SignalSamplePoint>> series = new ArrayList<>(signalCount);
            for (int s = 0; s < signalCount; s++) {
                SignalCatalogKey key = new SignalCatalogKey(
                        "S" + s, SignalSourceType.DBC_CAN, "M", s + 1L, "src.mf4", "def.dbc");
                keys.add(key);
                List<SignalSamplePoint> points = new ArrayList<>(samplesPerSignal);
                for (int i = 0; i < samplesPerSignal; i++) {
                    points.add(new SignalSamplePoint((i + 1L) * 1_000_000L, i, (long) i, null));
                }
                series.add(List.copyOf(points));
            }
            return new CountingStore(List.copyOf(keys), List.copyOf(series));
        }

        List<SignalCatalogKey> keys() {
            return keys;
        }

        int samplesReturned() {
            return samplesReturned.get();
        }

        int maxPageLimit() {
            return maxPageLimit.get();
        }

        int fullSeriesLoads() {
            return fullSeriesLoads.get();
        }

        void resetCounts() {
            samplesReturned.set(0);
            maxPageLimit.set(0);
            fullSeriesLoads.set(0);
        }

        @Override
        public SignalCatalog snapshot() {
            return SignalCatalog.empty();
        }

        @Override
        public List<SignalSamplePoint> querySamples(SignalCatalogKey key, int offset, int limit) {
            int idx = keys.indexOf(key);
            if (idx < 0) {
                return List.of();
            }
            List<SignalSamplePoint> all = series.get(idx);
            maxPageLimit.accumulateAndGet(limit, Math::max);
            if (limit >= all.size() && offset == 0) {
                fullSeriesLoads.incrementAndGet();
            }
            int from = Math.min(all.size(), Math.max(0, offset));
            int to = Math.min(all.size(), from + Math.max(1, limit));
            List<SignalSamplePoint> page = all.subList(from, to);
            samplesReturned.addAndGet(page.size());
            return List.copyOf(page);
        }

        @Override
        public int retainedSampleCount(SignalCatalogKey key) {
            int idx = keys.indexOf(key);
            return idx < 0 ? 0 : series.get(idx).size();
        }

        @Override
        public long retainedWindowStartIndex(SignalCatalogKey key) {
            return 0L;
        }
    }

    /** Store that refuses to return more than {@code cap} samples in one read. */
    private static final class CappedStore implements SignalSeriesStore {
        private final Map<SignalCatalogKey, List<SignalSamplePoint>> series;
        private final int cap;

        private CappedStore(Map<SignalCatalogKey, List<SignalSamplePoint>> series, int cap) {
            this.series = series;
            this.cap = cap;
        }

        static CappedStore uniform(int samples, int cap) {
            SignalCatalogKey key = new SignalCatalogKey(
                    "Speed", SignalSourceType.DBC_CAN, "Vehicle", 0x100L, 1, "run.mf4", "veh.dbc");
            List<SignalSamplePoint> points = new ArrayList<>(samples);
            for (int i = 0; i < samples; i++) {
                points.add(new SignalSamplePoint((i + 1L) * 1_000_000L, i, (long) i, null));
            }
            return new CappedStore(Map.of(key, List.copyOf(points)), cap);
        }

        SignalCatalogKey key() {
            return series.keySet().iterator().next();
        }

        @Override
        public SignalCatalog snapshot() {
            return SignalCatalog.empty();
        }

        @Override
        public List<SignalSamplePoint> querySamples(SignalCatalogKey key, int offset, int limit) {
            List<SignalSamplePoint> all = series.get(key);
            if (all == null || limit <= 0) {
                return List.of();
            }
            int from = Math.min(all.size(), Math.max(0, offset));
            int to = Math.min(all.size(), from + Math.min(Math.max(1, limit), cap));
            return List.copyOf(all.subList(from, to));
        }

        @Override
        public int retainedSampleCount(SignalCatalogKey key) {
            List<SignalSamplePoint> all = series.get(key);
            return all == null ? 0 : all.size();
        }

        @Override
        public long retainedWindowStartIndex(SignalCatalogKey key) {
            return 0L;
        }
    }
}
