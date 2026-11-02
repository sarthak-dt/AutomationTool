package com.stellar.platform.api.local;

import com.stellar.platform.api.dto.SignalCatalogPageDto;
import com.stellar.platform.api.dto.SignalCatalogQuery;
import com.stellar.platform.api.dto.SignalSamplePageDto;
import com.stellar.platform.api.dto.SignalViewportRequestDto;
import com.stellar.platform.api.dto.SignalViewportResultDto;
import com.stellar.platform.api.support.ProcessSessionRegistry;
import com.stellar.platform.api.support.SignalKeyCodec;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STEP 5 large-data Signal Query API: catalog paging, indexed range, viewport LOD bounds.
 */
class LocalSignalQueryApiLargeDataTest {

    @TempDir Path temp;

    @Test
    void catalogPagesFiveThousandEntriesWithoutSilentCap() {
        ProcessSessionRegistry sessions = new ProcessSessionRegistry();
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("m.asc", "d.dbc", 8);
        List<CanonicalSignal> batch = new ArrayList<>(5000);
        for (int i = 0; i < 5000; i++) {
            batch.add(signal("Sig" + i, "Msg", i, 0L, 1.0));
        }
        store.accept(batch);
        sessions.put("p1", store);

        LocalSignalQueryApi api = new LocalSignalQueryApi(sessions);
        SignalCatalogPageDto page0 = api.catalog("p1", new SignalCatalogQuery("", 0, 2000));
        assertEquals(5000, page0.total());
        assertEquals(2000, page0.entries().size());

        SignalCatalogPageDto page1 = api.catalog("p1", new SignalCatalogQuery("", 2000, 2000));
        assertEquals(5000, page1.total());
        assertEquals(2000, page1.entries().size());

        SignalCatalogPageDto last = api.catalog("p1", new SignalCatalogQuery("", 4000, 2000));
        assertEquals(5000, last.total());
        assertEquals(1000, last.entries().size());
        assertFalse(last.entries().getFirst().key().isBlank());
        assertFalse(last.entries().getLast().name().isBlank());
    }

    @Test
    void catalogSearchAndSourceFilters() {
        ProcessSessionRegistry sessions = new ProcessSessionRegistry();
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("car.asc", "d.dbc", 8);
        store.seedCatalogMetadata(List.of(
                entry("EngineSpeed", SignalSourceType.DBC_CAN, "Msg", 0x100, "car.asc"),
                entry("NativeTemp", SignalSourceType.MF4_NATIVE,
                        InMemorySignalCatalogStore.MF4_MEASUREMENT_MESSAGE, 0, "car.mf4"),
                entry("Other", SignalSourceType.DBC_CAN, "Msg", 0x200, "truck.asc")
        ));
        sessions.put("p1", store);
        LocalSignalQueryApi api = new LocalSignalQueryApi(sessions);

        SignalCatalogPageDto search = api.catalog("p1", new SignalCatalogQuery("engine", 0, 50));
        assertEquals(1, search.total());
        assertEquals("EngineSpeed", search.entries().getFirst().name());

        SignalCatalogPageDto dbc = api.catalog("p1", new SignalCatalogQuery("", "DBC_CAN", "", 0, 50));
        assertEquals(2, dbc.total());

        SignalCatalogPageDto nativeOnly = api.catalog("p1", new SignalCatalogQuery("", "MF4_NATIVE", "", 0, 50));
        assertEquals(1, nativeOnly.total());
        assertEquals("NativeTemp", nativeOnly.entries().getFirst().name());

        SignalCatalogPageDto byFile = api.catalog("p1", new SignalCatalogQuery("", "", "truck", 0, 50));
        assertEquals(1, byFile.total());
        assertTrue(byFile.entries().getFirst().sourceFile().contains("truck"));
    }

    @Test
    void samplePagingIsBounded() throws Exception {
        ProcessSessionRegistry sessions = new ProcessSessionRegistry();
        GraphSignalSessionStore store = GraphSignalSessionStore.createPersistent(
                temp.resolve("samples"), "m.asc", "d.dbc");
        List<CanonicalSignal> points = new ArrayList<>(12_000);
        for (int i = 0; i < 12_000; i++) {
            points.add(signal("S", "Msg", 1, i * 1_000_000L, i));
        }
        store.accept(points);
        store.finishWriteSession();
        sessions.put("p1", store);

        LocalSignalQueryApi api = new LocalSignalQueryApi(sessions);
        String key = SignalKeyCodec.encode(store.snapshot().entries().getFirst().key());
        SignalSamplePageDto page = api.samples("p1", key, 0, 10_000);
        assertEquals(LocalSignalQueryApi.MAX_SAMPLE_PAGE, page.limit());
        assertEquals(LocalSignalQueryApi.MAX_SAMPLE_PAGE, page.samples().size());
        assertEquals(12_000, page.total());

        SignalSamplePageDto last = api.samples("p1", key, 11_990, 50);
        assertEquals(10, last.samples().size());
    }

    @Test
    void indexedRangeDoesNotRequireFullSeriesPreload() throws Exception {
        ProcessSessionRegistry sessions = new ProcessSessionRegistry();
        GraphSignalSessionStore store = GraphSignalSessionStore.createPersistent(
                temp.resolve("range"), "m.asc", "d.dbc");
        List<CanonicalSignal> points = new ArrayList<>(20_000);
        for (int i = 0; i < 20_000; i++) {
            points.add(signal("S", "Msg", 1, i * 1_000_000L, i));
        }
        store.accept(points);
        store.finishWriteSession();
        sessions.put("p1", store);

        LocalSignalQueryApi api = new LocalSignalQueryApi(sessions);
        String key = SignalKeyCodec.encode(store.snapshot().entries().getFirst().key());

        SignalSamplePageDto mid = api.range("p1", key, 9_000_000_000L, 9_005_000_000L, 100);
        assertFalse(mid.samples().isEmpty());
        assertTrue(mid.samples().size() <= 100);
        for (var s : mid.samples()) {
            assertTrue(s.timestampNanos() >= 9_000_000_000L);
            assertTrue(s.timestampNanos() <= 9_005_000_000L);
        }

        SignalSamplePageDto empty = api.range("p1", key, 50_000_000_000L, 50_001_000_000L, 100);
        assertTrue(empty.samples().isEmpty());

        SignalSamplePageDto single = api.range("p1", key, 100_000_000L, 100_000_000L, 10);
        assertEquals(1, single.samples().size());
        assertEquals(100_000_000L, single.samples().getFirst().timestampNanos());
    }

    @Test
    void inMemoryRangeUsesPagedFilterNeverRetainedCount() {
        CountingStore store = new CountingStore();
        for (int i = 0; i < 5000; i++) {
            store.add(i * 1000L, i);
        }
        List<SignalSamplePoint> page = LocalSignalQueryApi.pagedRangeFilter(
                store, store.key(), 1_000_000L, 1_050_000L, 25);
        assertEquals(25, page.size());
        assertTrue(store.maxLimitRequested.get() <= 1000,
                "must page with bounded limits, never retainedCount");
        assertTrue(store.maxOffsetRequested.get() < 5000);
    }

    @Test
    void multiSourceLogicalSignalRangeIsBounded() throws Exception {
        GraphSignalSessionStore a = GraphSignalSessionStore.createPersistent(
                temp.resolve("a"), "a.asc", "d.dbc");
        GraphSignalSessionStore b = GraphSignalSessionStore.createPersistent(
                temp.resolve("b"), "b.asc", "d.dbc");
        a.accept(List.of(
                signal("Shared", "Msg", 7, 0L, 1.0),
                signal("Shared", "Msg", 7, 2_000_000L, 2.0)));
        b.accept(List.of(
                signal("Shared", "Msg", 7, 1_000_000L, 1.5),
                signal("Shared", "Msg", 7, 3_000_000L, 2.5)));
        a.finishWriteSession();
        b.finishWriteSession();
        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(
                List.of(
                        new ProjectMergedSignalStore.Member(a, "a", "d", "a.asc", "d.dbc"),
                        new ProjectMergedSignalStore.Member(b, "b", "d", "b.asc", "d.dbc")
                ),
                "merged",
                "merged"
        );
        ProcessSessionRegistry sessions = new ProcessSessionRegistry();
        sessions.put("p1", merged);
        LocalSignalQueryApi api = new LocalSignalQueryApi(sessions);
        String key = SignalKeyCodec.encode(merged.snapshot().entries().getFirst().key());
        SignalSamplePageDto range = api.range("p1", key, 0L, 3_000_000L, 10);
        assertEquals(4, range.samples().size());
        assertEquals(0L, range.samples().get(0).timestampNanos());
        assertEquals(1_000_000L, range.samples().get(1).timestampNanos());
    }

    @Test
    void viewportLodKeepsResponseBounded() throws Exception {
        ProcessSessionRegistry sessions = new ProcessSessionRegistry();
        GraphSignalSessionStore store = GraphSignalSessionStore.createPersistent(
                temp.resolve("viewport"), "m.asc", "d.dbc");
        List<CanonicalSignal> points = new ArrayList<>(50_000);
        for (int i = 0; i < 50_000; i++) {
            points.add(signal("S", "Msg", 1, i * 1_000_000L, i));
        }
        store.accept(points);
        store.finishWriteSession();
        sessions.put("p1", store);

        LocalSignalQueryApi api = new LocalSignalQueryApi(sessions);
        String key = SignalKeyCodec.encode(store.snapshot().entries().getFirst().key());
        SignalViewportResultDto viewport = api.viewport("p1", new SignalViewportRequestDto(
                List.of(key), 0L, 49_999_000_000L, 200));
        assertEquals(1, viewport.series().size());
        assertTrue(viewport.series().getFirst().buckets().size() <= 200 * 2,
                "LOD bucket count must stay near pixel budget");
        assertFalse(viewport.series().getFirst().fullResolution());

        var stats = api.viewportStats("p1", new SignalViewportRequestDto(
                List.of(key), 0L, 49_999_000_000L, 200));
        assertFalse(stats.features().isEmpty());
        assertEquals("S", stats.features().getFirst().signal());
    }

    private static CanonicalSignal signal(String name, String message, long canId, long ts, double value) {
        return new CanonicalSignal(
                name, message, ts, canId, 0, value, null, "u", null, SourceReference.ofLine(1));
    }

    private static SignalCatalogEntry entry(
            String name, SignalSourceType type, String message, long canId, String sourceFile) {
        return new SignalCatalogEntry(
                new SignalCatalogKey(name, type, message, canId, 0, sourceFile, "d.dbc"),
                "u", 1, 0L, 1L, 0.0, 1.0, sourceFile, "d.dbc");
    }

    /** Counts querySamples bounds for regression against full-series preload. */
    private static final class CountingStore implements SignalSeriesStore {
        private final List<SignalSamplePoint> all = new ArrayList<>();
        private final AtomicInteger maxLimitRequested = new AtomicInteger();
        private final AtomicInteger maxOffsetRequested = new AtomicInteger();
        private final SignalCatalogKey key = new SignalCatalogKey(
                "S", SignalSourceType.DBC_CAN, "Msg", 1, 0, "m.asc", "d.dbc");

        void add(long ts, double value) {
            all.add(new SignalSamplePoint(ts, value, null, null));
        }

        SignalCatalogKey key() {
            return key;
        }

        @Override
        public SignalCatalog snapshot() {
            return new SignalCatalog(List.of(), "", "");
        }

        @Override
        public List<SignalSamplePoint> querySamples(SignalCatalogKey key, int offset, int limit) {
            maxOffsetRequested.accumulateAndGet(offset, Math::max);
            maxLimitRequested.accumulateAndGet(limit, Math::max);
            int from = Math.max(0, offset);
            int to = Math.min(all.size(), from + Math.max(1, limit));
            if (from >= all.size()) {
                return List.of();
            }
            return List.copyOf(all.subList(from, to));
        }

        @Override
        public int retainedSampleCount(SignalCatalogKey key) {
            return all.size();
        }

        @Override
        public long retainedWindowStartIndex(SignalCatalogKey key) {
            return 0L;
        }
    }
}
