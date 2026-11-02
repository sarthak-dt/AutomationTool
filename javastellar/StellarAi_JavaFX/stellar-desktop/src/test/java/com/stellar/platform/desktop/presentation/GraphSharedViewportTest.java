package com.stellar.platform.desktop.presentation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphSharedViewportTest {

    @Test
    void rejectsEqualAndInvertedRanges() {
        assertFalse(GraphSharedViewport.isStructurallyValid(10, 10));
        assertFalse(GraphSharedViewport.isStructurallyValid(20, 10));
        assertTrue(GraphSharedViewport.isStructurallyValid(10, 11));
    }

    @Test
    void rejectsNearZeroWindowAgainstMultiSecondData() {
        long fullStart = 0L;
        long fullEnd = 600_000_000_000L; // 600 s
        long collapsed = 338_960_000_000L;
        assertFalse(GraphSharedViewport.isUsable(collapsed, collapsed + 1L, fullStart, fullEnd));
        assertTrue(GraphSharedViewport.isUsable(60_000_000_000L, 80_000_000_000L, fullStart, fullEnd));
    }

    @Test
    void rejectsViewportOutsideFullRange() {
        assertFalse(GraphSharedViewport.isUsable(900L, 950L, 0L, 200L));
    }

    @Test
    void zoomInShrinksAroundTheCursorAndZoomOutStopsAtTheDataRange() {
        long start = 0L;
        long end = 10_000_000_000L;
        long anchor = 2_500_000_000L;
        long[] zoomed = GraphSharedViewport.zoomAround(start, end, 0.5, anchor, start, end);
        long span = zoomed[1] - zoomed[0];
        assertTrue(span < end - start);
        assertTrue(span >= GraphSharedViewport.MIN_USABLE_SPAN_NANOS);
        assertTrue(zoomed[0] <= anchor && anchor <= zoomed[1]);
        double before = (anchor - start) / (double) (end - start);
        double after = (anchor - zoomed[0]) / (double) span;
        assertEquals(before, after, 0.02);

        long[] wider = GraphSharedViewport.zoomAround(zoomed[0], zoomed[1], 2.0, anchor, start, end);
        assertTrue(wider[1] - wider[0] > span);
        long[] fitted = GraphSharedViewport.zoomAround(start, end, 2.0, anchor, start, end);
        assertEquals(start, fitted[0]);
        assertEquals(end, fitted[1]);
    }

    @Test
    void zoomInOnALongRecordingStopsAtOneMillisecondInsteadOfRejecting() {
        long fullEnd = 600_000_000_000L;
        long[] window = GraphSharedViewport.zoomAround(0L, 2_000_000L, 0.5, 1_000_000L, 0L, fullEnd);
        assertEquals(GraphSharedViewport.MIN_USABLE_SPAN_NANOS, window[1] - window[0]);
        assertTrue(GraphSharedViewport.isUsable(window[0], window[1], 0L, fullEnd));
    }

    @Test
    void panStopsAtTheDataEdges() {
        long[] left = GraphSharedViewport.panBy(0L, 1_000L, -500L, 0L, 5_000L);
        assertEquals(0L, left[0]);
        assertEquals(1_000L, left[1]);
        long[] right = GraphSharedViewport.panBy(4_000L, 5_000L, 800L, 0L, 5_000L);
        assertEquals(5_000L, right[1]);
        assertEquals(4_000L, right[0]);
        long[] inside = GraphSharedViewport.panBy(1_000L, 2_000L, 500L, 0L, 5_000L);
        assertEquals(1_500L, inside[0]);
        assertEquals(2_500L, inside[1]);
    }

    @Test
    void singleTimestampDisplayRangeIsNonZeroAndContainsPoint() {
        long t = 338_960_000_000L;
        long[] pad = GraphSharedViewport.singleTimestampDisplayRange(t);
        assertTrue(pad[1] > pad[0]);
        assertTrue(pad[0] <= t && t <= pad[1]);
        assertArrayEquals(pad, GraphSharedViewport.singleTimestampDisplayRange(t));
    }
}
