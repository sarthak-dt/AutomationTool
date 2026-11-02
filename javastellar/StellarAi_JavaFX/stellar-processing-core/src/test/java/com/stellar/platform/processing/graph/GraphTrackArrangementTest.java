package com.stellar.platform.processing.graph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphTrackArrangementTest {

    @Test
    void overlappingRelativeClocksStayInSeparatePanesInTheGivenOrder() {
        List<GraphTrackArrangement.Input> segments = List.of(
                new GraphTrackArrangement.Input(0L, 1_000_000_000L, null),
                new GraphTrackArrangement.Input(0L, 1_000_000_000L, null));
        List<GraphTrackArrangement.Pane> panes = GraphTrackArrangement.panes(
                GraphTrackArrangement.Mode.INDEPENDENT_RELATIVE, segments, 40, 840);
        assertEquals(2, panes.size());
        assertTrue(panes.get(0).x1() < panes.get(1).x0(), "panes must not share pixels");
        assertEquals(0L, panes.get(0).axisStart());
        assertEquals(0L, panes.get(1).axisStart());
        assertEquals(0L, panes.get(0).originNanos());
        assertEquals(0L, panes.get(1).originNanos());
    }

    @Test
    void comparableClocksShareOneAxisAndKeepAGapBetweenRecordings() {
        long secondOrigin = 5_000_000_000L;
        List<GraphTrackArrangement.Input> segments = List.of(
                new GraphTrackArrangement.Input(0L, 1_000_000_000L, 0L),
                new GraphTrackArrangement.Input(0L, 1_000_000_000L, secondOrigin));
        List<GraphTrackArrangement.Pane> panes = GraphTrackArrangement.panes(
                GraphTrackArrangement.Mode.SHARED_ABSOLUTE, segments, 0, 600);
        assertEquals(2, panes.size());
        assertEquals(panes.get(0).x0(), panes.get(1).x0(), 0);
        assertEquals(panes.get(0).x1(), panes.get(1).x1(), 0);
        assertEquals(0L, panes.get(0).axisStart());
        assertEquals(secondOrigin + 1_000_000_000L, panes.get(0).axisEnd());
        double firstEnd = fraction(panes.get(0), 1_000_000_000L);
        double secondStart = fraction(panes.get(1), 0L);
        assertTrue(secondStart > firstEnd, "absolute placement leaves the unrecorded gap empty");
        assertEquals(-1, GraphTrackArrangement.paneContaining(panes, 300));
    }

    private static double fraction(GraphTrackArrangement.Pane pane, long storedTimestamp) {
        long display = GraphTrackArrangement.addSaturating(pane.originNanos(), storedTimestamp);
        return (display - pane.axisStart()) / (double) (pane.axisEnd() - pane.axisStart());
    }
}
