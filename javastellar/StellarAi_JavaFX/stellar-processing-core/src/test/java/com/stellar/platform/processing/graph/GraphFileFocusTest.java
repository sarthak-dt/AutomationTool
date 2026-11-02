package com.stellar.platform.processing.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphFileFocusTest {

    @Test
    void navigationStaysInsideOneTrackAndNamesTheFile() {
        GraphFileFocus focus = new GraphFileFocus(0, 73);
        assertEquals("File 1 of 73", focus.position());
        assertEquals("File 1 of 73 · file-0.dat", focus.caption("file-0.dat"));
        assertFalse(focus.hasPrevious());
        assertTrue(focus.hasNext());
        GraphFileFocus twelfth = focus.next().next();
        for (int i = 0; i < 9; i++) {
            twelfth = twelfth.next();
        }
        assertEquals(11, twelfth.index());
        assertEquals("File 12 of 73", twelfth.position());
        assertEquals(72, new GraphFileFocus(80, 73).index());
        assertFalse(new GraphFileFocus(72, 73).hasNext());
    }

    @Test
    void zoomAndPanStayInsideTheFocusedFile() {
        long[] fitted = GraphFileFocus.queryWindow(0, 7_000_000_000_000L, 0, 1_000_000_000L);
        assertArrayEquals(new long[] { 0, 1_000_000_000L }, fitted);
        long[] zoomed = GraphFileFocus.queryWindow(100, 400, 0, 1_000);
        assertArrayEquals(new long[] { 100, 400 }, zoomed);
        long[] outside = GraphFileFocus.queryWindow(5_000, 6_000, 0, 1_000);
        assertArrayEquals(new long[] { 0, 1_000 }, outside);
    }
}
