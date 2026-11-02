package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.processing.export.StreamingSignalCsvExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A page that starts near the end of a 65,536-record segment must continue into the next
 * segment. {@code readPage} is given the records still required and a list that already
 * holds the tail of the previous segment.
 */
class SegmentBoundaryPaginationTest {

    private static final int SEGMENT = LosslessGraphSignalStore.DEFAULT_SAMPLES_PER_SEGMENT;
    /** Two full segments plus a partial third, long enough for a 10,000-row page to cross both boundaries. */
    private static final int TOTAL = SEGMENT * 2 + 10_000;

    @TempDir
    Path temp;

    @Test
    void pagesCrossBothSegmentBoundariesWithoutGapsOrDuplicates() throws Exception {
        SignalCatalogKey key;
        try (LosslessGraphSignalStore store = new LosslessGraphSignalStore(
                temp, "run.mf4", "veh.dbc", SEGMENT)) {
            List<CanonicalSignal> batch = new ArrayList<>(4_096);
            for (int i = 0; i < TOTAL; i++) {
                batch.add(sample(i));
                if (batch.size() == 4_096) {
                    store.accept(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                store.accept(batch);
            }
            key = InMemorySignalCatalogStore.keyOf(sample(0), "run.mf4", "veh.dbc");
            assertEquals(TOTAL, store.sampleCount(key));

            assertPage(store, key, 1_000, 50);
            assertPage(store, key, SEGMENT + 100, 50);
            assertPage(store, key, SEGMENT * 2 + 10, 40);

            List<SignalSamplePoint> acrossFirst = store.querySamples(key, 56_422, 10_000);
            assertEquals(10_000, acrossFirst.size());
            assertEquals(56_422L, acrossFirst.getFirst().timestampNanos());
            assertEquals(66_421L, acrossFirst.getLast().timestampNanos());
            assertEquals(1, acrossFirst.stream().filter(point -> point.timestampNanos() == SEGMENT).count());
            assertEquals(SEGMENT, acrossFirst.get(SEGMENT - 56_422).timestampNanos());
            assertEquals((long) SEGMENT, acrossFirst.get(SEGMENT - 56_422).rawValue());

            List<SignalSamplePoint> onFirstBoundary = store.querySamples(key, SEGMENT, 1);
            assertEquals(1, onFirstBoundary.size());
            assertEquals(SEGMENT, onFirstBoundary.getFirst().timestampNanos());
            assertEquals(SEGMENT, onFirstBoundary.getFirst().physicalValue(), 0.0);

            int secondStart = SEGMENT * 2 - 4_000;
            List<SignalSamplePoint> acrossSecond = store.querySamples(key, secondStart, 10_000);
            assertEquals(10_000, acrossSecond.size());
            assertEquals(secondStart, acrossSecond.getFirst().timestampNanos());
            assertEquals(1, acrossSecond.stream().filter(point -> point.timestampNanos() == SEGMENT * 2L).count());

            List<SignalSamplePoint> onSecondBoundary = store.querySamples(key, SEGMENT * 2, 1);
            assertEquals(1, onSecondBoundary.size());
            assertEquals(SEGMENT * 2L, onSecondBoundary.getFirst().timestampNanos());

            int lastOffset = (TOTAL / 10_000) * 10_000;
            List<SignalSamplePoint> tail = store.querySamples(key, lastOffset, 10_000);
            assertEquals(TOTAL - lastOffset, tail.size());
            assertTruePartial(tail, lastOffset);

            for (int offset = 0; offset < TOTAL; offset += 10_000) {
                int expect = Math.min(10_000, TOTAL - offset);
                List<SignalSamplePoint> page = store.querySamples(key, offset, 10_000);
                assertEquals(expect, page.size(), "page at " + offset);
                assertEquals(offset, page.getFirst().timestampNanos());
                assertEquals(offset + expect - 1L, page.getLast().timestampNanos());
                assertEquals(offset, page.getFirst().rawValue());
                assertEquals(offset + expect - 1.0, page.getLast().physicalValue(), 0.0);
            }

            assertReusedListReadsOnlyAdditionalRecords(temp);

            Path csv = temp.resolve("export.csv");
            SignalSeriesStore series = new SignalSeriesStore() {
                @Override
                public SignalCatalog snapshot() {
                    return SignalCatalog.empty();
                }

                @Override
                public List<SignalSamplePoint> querySamples(SignalCatalogKey requested, int offset, int limit) {
                    return store.querySamples(requested, offset, limit);
                }

                @Override
                public int retainedSampleCount(SignalCatalogKey requested) {
                    return (int) store.sampleCount(requested);
                }

                @Override
                public long retainedWindowStartIndex(SignalCatalogKey requested) {
                    return 0L;
                }
            };
            new StreamingSignalCsvExporter().export(series, List.of(key), csv, new AtomicBoolean());
            List<String> lines = Files.readAllLines(csv);
            assertEquals(TOTAL + 1, lines.size());
            assertEquals("signal,timestamp_nanos,physical,raw,validity", lines.getFirst());
            for (int i = 0; i < TOTAL; i++) {
                String row = lines.get(i + 1);
                assertEquals("RPM," + i + "," + (double) i + "," + i + ",VALID", row, "csv row " + i);
            }
        }
    }

    private static void assertPage(LosslessGraphSignalStore store, SignalCatalogKey key, int offset, int limit) {
        List<SignalSamplePoint> page = store.querySamples(key, offset, limit);
        assertEquals(limit, page.size());
        for (int i = 0; i < limit; i++) {
            assertEquals(offset + i, page.get(i).timestampNanos());
            assertEquals(offset + i, page.get(i).rawValue());
            assertEquals(offset + i, page.get(i).physicalValue(), 0.0);
        }
    }

    private static void assertTruePartial(List<SignalSamplePoint> tail, int lastOffset) {
        assertFalse(tail.isEmpty());
        assertEquals(lastOffset, tail.getFirst().timestampNanos());
        assertEquals(TOTAL - 1L, tail.getLast().timestampNanos());
    }

    /**
     * The second segment read receives a list that already holds an unrelated record.
     * Only the requested additional records are appended.
     */
    private static void assertReusedListReadsOnlyAdditionalRecords(Path root) throws Exception {
        Path segment;
        try (var walk = Files.walk(root)) {
            segment = walk.filter(path -> path.getFileName().toString().equals("segment-000001.bin"))
                    .findFirst()
                    .orElseThrow();
        }
        Method readPage = LosslessGraphSignalStore.class.getDeclaredMethod(
                "readPage", Path.class, long.class, int.class, List.class, int.class);
        readPage.setAccessible(true);
        List<SignalSamplePoint> reused = new ArrayList<>();
        reused.add(new SignalSamplePoint(-1L, -1.0, -1L, null));
        readPage.invoke(null, segment, 0L, 2, reused, LosslessGraphSignalStore.INDEX_LAYOUT_VALIDITY);
        assertEquals(3, reused.size());
        assertEquals(-1L, reused.getFirst().timestampNanos());
        assertEquals(SEGMENT, reused.get(1).timestampNanos());
        assertEquals(SEGMENT + 1L, reused.get(2).timestampNanos());
        assertEquals((long) SEGMENT, reused.get(1).rawValue());
    }

    private static CanonicalSignal sample(int index) {
        return new CanonicalSignal(
                "RPM", "Engine", index, 0x100L, 1, index, (long) index, "rpm", null,
                new SourceReference(-1L, index));
    }
}
