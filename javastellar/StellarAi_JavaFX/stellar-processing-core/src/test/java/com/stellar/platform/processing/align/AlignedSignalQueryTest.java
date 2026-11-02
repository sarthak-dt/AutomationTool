package com.stellar.platform.processing.align;

import com.stellar.platform.domain.catalog.SignalSamplePoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlignedSignalQueryTest {

    private static final long S = 1_000_000_000L;

    @Test
    void taskExample_twoSignalPartialOverlapUnion() {
        List<SignalSamplePoint> a = List.of(sample(1 * S, 10), sample(2 * S, 20), sample(4 * S, 40));
        List<SignalSamplePoint> b = List.of(sample(1 * S, 100), sample(3 * S, 300), sample(4 * S, 400));

        AlignedSignalPage page = AlignedSignalQuery.query(List.of(a, b), 100);
        assertEquals(4, page.totalAlignedRows());
        assertEquals(4, page.rows().size());

        assertRow(page.rows().get(0), 0, 1 * S, 10.0, 100.0);
        assertRow(page.rows().get(1), 1, 2 * S, 20.0, null);
        assertRow(page.rows().get(2), 2, 3 * S, null, 300.0);
        assertRow(page.rows().get(3), 3, 4 * S, 40.0, 400.0);
    }

    @Test
    void oneSelectedSignal_preservesSamplesAndOrder() {
        List<SignalSamplePoint> a = List.of(sample(1 * S, 10), sample(2 * S, 20), sample(4 * S, 40));
        AlignedSignalPage page = AlignedSignalQuery.query(List.of(a), 100);
        assertEquals(3, page.totalAlignedRows());
        assertEquals(10.0, page.rows().get(0).physicalAt(0));
        assertEquals(20.0, page.rows().get(1).physicalAt(0));
        assertEquals(40.0, page.rows().get(2).physicalAt(0));
        assertEquals(1 * S, page.rows().get(0).timestampNanos());
    }

    @Test
    void identicalTimestamps_exactMatchNoMissing() {
        List<SignalSamplePoint> a = List.of(sample(1 * S, 10), sample(2 * S, 20));
        List<SignalSamplePoint> b = List.of(sample(1 * S, 100), sample(2 * S, 200));
        AlignedSignalPage page = AlignedSignalQuery.query(List.of(a, b), 100);
        assertEquals(2, page.rows().size());
        assertEquals(10.0, page.rows().get(0).physicalAt(0));
        assertEquals(100.0, page.rows().get(0).physicalAt(1));
        assertEquals(20.0, page.rows().get(1).physicalAt(0));
        assertEquals(200.0, page.rows().get(1).physicalAt(1));
    }

    @Test
    void disjointTimestamps_allMissingCrossCells() {
        List<SignalSamplePoint> a = List.of(sample(1 * S, 10), sample(2 * S, 20));
        List<SignalSamplePoint> b = List.of(sample(10 * S, 100), sample(11 * S, 110));
        AlignedSignalPage page = AlignedSignalQuery.query(List.of(a, b), 100);
        assertEquals(4, page.rows().size());
        assertNull(page.rows().get(0).physicalAt(1));
        assertNull(page.rows().get(2).physicalAt(0));
    }

    @Test
    void threeSelectedSignals_unionAndColumnOrder() {
        List<SignalSamplePoint> engine = List.of(sample(1 * S, 1000), sample(3 * S, 3000));
        List<SignalSamplePoint> speed = List.of(sample(1 * S, 50), sample(2 * S, 60));
        List<SignalSamplePoint> coolant = List.of(sample(2 * S, 90), sample(4 * S, 95));
        // Column order = add order: Engine, Speed, Coolant
        AlignedSignalPage page = AlignedSignalQuery.query(List.of(engine, speed, coolant), 100);
        assertEquals(4, page.rows().size());
        assertEquals(3, page.signalCount());
        assertEquals(1000.0, page.rows().get(0).physicalAt(0));
        assertEquals(50.0, page.rows().get(0).physicalAt(1));
        assertNull(page.rows().get(0).physicalAt(2));
        assertNull(page.rows().get(1).physicalAt(0));
        assertEquals(60.0, page.rows().get(1).physicalAt(1));
        assertEquals(90.0, page.rows().get(1).physicalAt(2));
    }

    @Test
    void missingSampleIsNull_notZeroOrInterpolated() {
        List<SignalSamplePoint> a = List.of(sample(1 * S, 10), sample(3 * S, 30));
        List<SignalSamplePoint> b = List.of(sample(2 * S, 200));
        AlignedSignalPage page = AlignedSignalQuery.query(List.of(a, b), 100);
        assertNull(page.rows().get(1).physicalAt(0));
        assertEquals(200.0, page.rows().get(1).physicalAt(1));
        // No forward-fill of 10 into t=2, no backward-fill of 30
        assertEquals(10.0, page.rows().get(0).physicalAt(0));
        assertEquals(30.0, page.rows().get(2).physicalAt(0));
    }

    @Test
    void timestampsOrderedAscending() {
        List<SignalSamplePoint> a = List.of(sample(4 * S, 40), sample(1 * S, 10)); // unsorted input
        List<SignalSamplePoint> b = List.of(sample(3 * S, 300));
        AlignedSignalPage page = AlignedSignalQuery.query(List.of(a, b), 100);
        assertEquals(1 * S, page.rows().get(0).timestampNanos());
        assertEquals(3 * S, page.rows().get(1).timestampNanos());
        assertEquals(4 * S, page.rows().get(2).timestampNanos());
    }

    @Test
    void realZeroDistinctFromMissing() {
        List<SignalSamplePoint> a = List.of(sample(1 * S, 0));
        List<SignalSamplePoint> b = List.of(sample(2 * S, 5));
        AlignedSignalPage page = AlignedSignalQuery.query(List.of(a, b), 100);
        assertEquals(0.0, page.rows().get(0).physicalAt(0));
        assertNull(page.rows().get(0).physicalAt(1));
    }

    @Test
    void duplicateSameSignalSameTimestamp_lastWins() {
        List<SignalSamplePoint> a = List.of(sample(1 * S, 10), sample(1 * S, 11), sample(2 * S, 20));
        List<SignalSamplePoint> b = List.of(sample(1 * S, 100));
        AlignedSignalPage page = AlignedSignalQuery.query(List.of(a, b), 100);
        assertEquals(2, page.totalAlignedRows());
        assertEquals(11.0, page.rows().get(0).physicalAt(0));
        assertEquals(100.0, page.rows().get(0).physicalAt(1));
    }

    @Test
    void paging_totalIndependentOfPageSize() {
        List<SignalSamplePoint> a = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            a.add(sample((i + 1) * S, i));
        }
        AlignedSignalPage page0 = AlignedSignalQuery.query(List.of(a), 0, 3);
        AlignedSignalPage page1 = AlignedSignalQuery.query(List.of(a), 3, 3);
        assertEquals(10, page0.totalAlignedRows());
        assertEquals(10, page1.totalAlignedRows());
        assertEquals(3, page0.rows().size());
        assertEquals(0L, page0.rows().get(0).index());
        assertEquals(3L, page1.rows().get(0).index());
        assertTrue(page0.hasNext());
        assertTrue(page1.hasPrevious());
    }

    @Test
    void firstIndexAtOrAfter_ceilSemantics() {
        long[] ts = new long[] {100L, 200L, 300L};
        assertEquals(0, AlignedSignalQuery.firstIndexAtOrAfter(ts, 50L));
        assertEquals(1, AlignedSignalQuery.firstIndexAtOrAfter(ts, 150L));
        assertEquals(1, AlignedSignalQuery.firstIndexAtOrAfter(ts, 200L));
        assertEquals(3, AlignedSignalQuery.firstIndexAtOrAfter(ts, 400L));
    }

    @Test
    void exactNanosNotFloatingTolerance() {
        List<SignalSamplePoint> a = List.of(sample(S, 10));
        List<SignalSamplePoint> b = List.of(sample(S + 1, 20));
        AlignedSignalPage page = AlignedSignalQuery.query(List.of(a, b), 10);
        assertEquals(2, page.rows().size());
        assertNull(page.rows().get(0).physicalAt(1));
    }

    @Test
    void emptyAndZeroSampleSeries() {
        assertTrue(AlignedSignalQuery.query(List.of(), 10).rows().isEmpty());
        AlignedSignalPage page = AlignedSignalQuery.query(List.of(List.of(), List.of(sample(S, 1))), 10);
        assertEquals(1, page.totalAlignedRows());
        assertNull(page.rows().get(0).physicalAt(0));
        assertEquals(1.0, page.rows().get(0).physicalAt(1));
    }

    @Test
    void doesNotAlignByRowIndex() {
        List<SignalSamplePoint> a = List.of(sample(1 * S, 1), sample(2 * S, 2), sample(3 * S, 3));
        List<SignalSamplePoint> b = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            b.add(sample((10 + i) * S, 100 + i));
        }
        AlignedSignalPage page = AlignedSignalQuery.query(List.of(a, b), 500);
        assertEquals(23, page.totalAlignedRows());
        assertNull(page.rows().get(0).physicalAt(1));
        assertEquals(1.0, page.rows().get(0).physicalAt(0));
    }

    @Test
    void cachedTimelineQueryMatchesNormalQueryAndPreservesLastWinsDuplicates() {
        List<SignalSamplePoint> a = List.of(sample(1 * S, 10), sample(1 * S, 11), sample(4 * S, 40));
        List<SignalSamplePoint> b = List.of(sample(2 * S, 200), sample(4 * S, 400));
        List<List<SignalSamplePoint>> series = List.of(a, b);

        long[] timeline = AlignedSignalQuery.unionTimestamps(series);
        AlignedSignalPage normal = AlignedSignalQuery.query(series, 1, 2);
        AlignedSignalPage cached = AlignedSignalQuery.query(series, timeline, 1, 2);

        assertEquals(normal.totalAlignedRows(), cached.totalAlignedRows());
        assertEquals(normal.rows().size(), cached.rows().size());
        for (int i = 0; i < normal.rows().size(); i++) {
            assertEquals(normal.rows().get(i).index(), cached.rows().get(i).index());
            assertEquals(normal.rows().get(i).timestampNanos(), cached.rows().get(i).timestampNanos());
            assertEquals(normal.rows().get(i).physicalAt(0), cached.rows().get(i).physicalAt(0));
            assertEquals(normal.rows().get(i).physicalAt(1), cached.rows().get(i).physicalAt(1));
        }
    }

    @Test
    void deepPageStartsFromTimestampWithoutChangingExactAlignment() {
        List<SignalSamplePoint> a = new ArrayList<>();
        List<SignalSamplePoint> b = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            a.add(sample(i * 2L, i));
            b.add(sample(i * 2L + 1L, i + 100_000));
        }
        List<List<SignalSamplePoint>> series = List.of(a, b);
        long[] timeline = AlignedSignalQuery.unionTimestamps(series);
        AlignedSignalPage page = AlignedSignalQuery.query(series, timeline, 19_990, 10);

        assertEquals(20_000, page.totalAlignedRows());
        assertEquals(10, page.rows().size());
        assertEquals(19_990L, page.rows().getFirst().timestampNanos());
        assertEquals(19_999L, page.rows().getLast().timestampNanos());
    }

    private static void assertRow(
            AlignedSignalRow row,
            long index,
            long ts,
            Double a,
            Double b
    ) {
        assertEquals(index, row.index());
        assertEquals(ts, row.timestampNanos());
        if (a == null) {
            assertNull(row.physicalAt(0));
        } else {
            assertEquals(a, row.physicalAt(0));
        }
        if (b == null) {
            assertNull(row.physicalAt(1));
        } else {
            assertEquals(b, row.physicalAt(1));
        }
    }

    private static SignalSamplePoint sample(long ts, double phys) {
        return new SignalSamplePoint(ts, phys, (long) phys, null);
    }
}
