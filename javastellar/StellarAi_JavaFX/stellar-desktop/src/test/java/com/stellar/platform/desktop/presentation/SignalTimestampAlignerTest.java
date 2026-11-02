package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalSamplePoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalTimestampAlignerTest {

    private static final long S = 1_000_000_000L;

    @Test
    void emptySeriesYieldsEmptyRows() {
        assertTrue(SignalTimestampAligner.alignByTimestamp(List.of(), 100).isEmpty());
        assertTrue(SignalTimestampAligner.alignByTimestamp(List.of(List.of()), 100).isEmpty());
    }

    @Test
    void testA_sameTimestampsExactMatch() {
        List<SignalSamplePoint> a = List.of(sample(1 * S, 10), sample(2 * S, 20));
        List<SignalSamplePoint> b = List.of(sample(1 * S, 100), sample(2 * S, 200));
        List<AlignedSampleRow> rows = SignalTimestampAligner.alignByTimestamp(List.of(a, b), 100);
        assertEquals(2, rows.size());
        assertEquals(10.0, rows.get(0).physicalAt(0));
        assertEquals(100.0, rows.get(0).physicalAt(1));
    }

    @Test
    void testB_differentTimestampsUnionMissingDash() {
        List<SignalSamplePoint> a = List.of(sample(1 * S, 10), sample(3 * S, 30));
        List<SignalSamplePoint> b = List.of(sample(2 * S, 200), sample(3 * S, 300));
        List<AlignedSampleRow> rows = SignalTimestampAligner.alignByTimestamp(List.of(a, b), 100);
        assertEquals(3, rows.size());
        assertNull(rows.get(0).physicalAt(1));
        assertNull(rows.get(1).physicalAt(0));
    }

    @Test
    void taskExample_signalAAndB_partialOverlap() {
        List<SignalSamplePoint> a = List.of(sample(1 * S, 10), sample(2 * S, 20), sample(4 * S, 40));
        List<SignalSamplePoint> b = List.of(sample(1 * S, 100), sample(3 * S, 300), sample(4 * S, 400));
        List<AlignedSampleRow> rows = SignalTimestampAligner.alignByTimestamp(List.of(a, b), 100);
        assertEquals(4, rows.size());
        assertEquals(10.0, rows.get(0).physicalAt(0));
        assertEquals(100.0, rows.get(0).physicalAt(1));
        assertEquals(20.0, rows.get(1).physicalAt(0));
        assertNull(rows.get(1).physicalAt(1));
        assertNull(rows.get(2).physicalAt(0));
        assertEquals(300.0, rows.get(2).physicalAt(1));
        assertEquals(40.0, rows.get(3).physicalAt(0));
        assertEquals(400.0, rows.get(3).physicalAt(1));
    }

    @Test
    void testC_realZeroDistinctFromMissing() {
        List<SignalSamplePoint> a = List.of(sample(1 * S, 0));
        List<SignalSamplePoint> b = List.of(sample(2 * S, 5));
        List<AlignedSampleRow> rows = SignalTimestampAligner.alignByTimestamp(List.of(a, b), 100);
        assertEquals(0.0, rows.get(0).physicalAt(0));
        assertNull(rows.get(0).physicalAt(1));
        assertNull(rows.get(1).physicalAt(0));
        assertEquals(5.0, rows.get(1).physicalAt(1));
    }

    @Test
    void testD_differentSampleCountsDoNotAlignByIndex() {
        List<SignalSamplePoint> a = List.of(sample(1 * S, 1), sample(2 * S, 2), sample(3 * S, 3));
        List<SignalSamplePoint> b = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            b.add(sample((10 + i) * S, 100 + i));
        }
        List<AlignedSampleRow> rows = SignalTimestampAligner.alignByTimestamp(List.of(a, b), 500);
        assertEquals(23, rows.size());
        assertNull(rows.get(0).physicalAt(1));
    }

    @Test
    void testE_differentStartAndEndTimesNonOverlappingRegions() {
        List<SignalSamplePoint> a = List.of(sample(10 * S, 10), sample(15 * S, 15), sample(20 * S, 20));
        List<SignalSamplePoint> b = List.of(sample(15 * S, 150), sample(25 * S, 250), sample(30 * S, 300));
        List<AlignedSampleRow> rows = SignalTimestampAligner.alignByTimestamp(List.of(a, b), 100);
        assertEquals(5, rows.size());
        assertNull(rows.get(0).physicalAt(1));
        assertEquals(150.0, rows.get(1).physicalAt(1));
    }

    @Test
    void windowSkipPreservesGlobalIndexes() {
        List<SignalSamplePoint> a = List.of(
                sample(1, 1), sample(2, 2), sample(3, 3), sample(4, 4), sample(5, 5)
        );
        List<AlignedSampleRow> page0 = SignalTimestampAligner.alignByTimestamp(List.of(a), 0, 2);
        List<AlignedSampleRow> page1 = SignalTimestampAligner.alignByTimestamp(List.of(a), 2, 2);
        assertEquals(0L, page0.get(0).index());
        assertEquals(1L, page0.get(1).index());
        assertEquals(2L, page1.get(0).index());
        assertEquals(3L, page1.get(1).index());
    }

    @Test
    void firstIndexAtOrAfter_isCeilSemantics() {
        long[] ts = new long[] {100L, 200L, 300L};
        assertEquals(0, SignalTimestampAligner.firstIndexAtOrAfter(ts, 50L));
        assertEquals(1, SignalTimestampAligner.firstIndexAtOrAfter(ts, 150L));
        assertEquals(1, SignalTimestampAligner.firstIndexAtOrAfter(ts, 200L));
        assertEquals(3, SignalTimestampAligner.firstIndexAtOrAfter(ts, 400L));
    }

    @Test
    void exactLongNanosNotFloatingTolerance() {
        List<SignalSamplePoint> a = List.of(sample(S, 10));
        List<SignalSamplePoint> b = List.of(sample(S + 1, 20));
        List<AlignedSampleRow> rows = SignalTimestampAligner.alignByTimestamp(List.of(a, b), 10);
        assertEquals(2, rows.size());
    }

    private static SignalSamplePoint sample(long ts, double phys) {
        return new SignalSamplePoint(ts, phys, (long) phys, null);
    }
}
