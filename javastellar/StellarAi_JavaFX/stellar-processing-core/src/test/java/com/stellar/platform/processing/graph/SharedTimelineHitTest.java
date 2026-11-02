package com.stellar.platform.processing.graph;

import com.stellar.platform.processing.time.SharedTimeSelectionModel;
import com.stellar.platform.processing.time.TimeSelection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedTimelineHitTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void twoComparableFilesKeepTheirOwnUtcIntervals() {
        List<SharedTimelineHit.Segment> files = List.of(
                segment("mf4-a", "VehicleSpeed", 0, 10 * SECOND, 1_000 * SECOND),
                segment("mf4-b", "VehicleSpeed", 0, 10 * SECOND, 5_000 * SECOND));
        SharedTimelineHit.Hit early = SharedTimelineHit.select(1_000 * SECOND + 4 * SECOND, files).orElseThrow();
        SharedTimelineHit.Hit later = SharedTimelineHit.select(5_000 * SECOND + 4 * SECOND, files).orElseThrow();
        assertEquals("mf4-a", early.measurementId());
        assertEquals(4 * SECOND, early.storedTimestampNanos());
        assertEquals(1_004 * SECOND, early.absoluteTimestampNanos());
        assertEquals("mf4-b", later.measurementId());
        assertEquals(4 * SECOND, later.storedTimestampNanos());
        assertEquals("VehicleSpeed", later.signalName());
        assertTrue(SharedTimelineHit.select(3_000 * SECOND, files).isEmpty());
    }

    @Test
    void overlappingUtcUsesTheHoveredSeriesNotTheFirstFile() {
        List<SharedTimelineHit.Segment> files = List.of(
                segment("mf4-a", "VehicleSpeed", 0, 20 * SECOND, 1_000 * SECOND),
                segment("mf4-b", "VehicleSpeed", 0, 20 * SECOND, 1_010 * SECOND));
        long absolute = 1_015 * SECOND;
        SharedTimelineHit.Hit front = SharedTimelineHit.select(absolute, files).orElseThrow();
        assertEquals("mf4-b", front.measurementId());
        assertEquals(1, front.segmentIndex());
        double[] values = {10.0, 80.0};
        SharedTimelineHit.Hit hovered = SharedTimelineHit.select(absolute, files, 12.0, values).orElseThrow();
        assertEquals("mf4-a", hovered.measurementId());
        assertEquals(15 * SECOND, hovered.storedTimestampNanos());
        assertEquals(80.0, values[1]);
    }

    @Test
    void identicalStoredTimestampsStayOnTheRecordingThatOwnsTheAbsoluteInstant() {
        List<SharedTimelineHit.Segment> files = List.of(
                segment("mf4-a", "Speed", 5 * SECOND, 5 * SECOND, 0L),
                segment("mf4-b", "Speed", 5 * SECOND, 5 * SECOND, 100 * SECOND));
        assertEquals("mf4-a", SharedTimelineHit.select(5 * SECOND, files).orElseThrow().measurementId());
        assertEquals("mf4-b", SharedTimelineHit.select(105 * SECOND, files).orElseThrow().measurementId());
        assertTrue(SharedTimelineHit.select(5 * SECOND, List.of(
                segment("mdf3-a", "Speed", 5 * SECOND, 5 * SECOND, null),
                segment("mdf3-b", "Speed", 5 * SECOND, 5 * SECOND, null))).isEmpty());
    }

    @Test
    void zoomKeepsTheSameOwnerForAnAbsoluteInstant() {
        List<SharedTimelineHit.Segment> files = List.of(
                segment("mf4-a", "Speed", 0, 10 * SECOND, 1_000 * SECOND),
                segment("mf4-b", "Speed", 0, 10 * SECOND, 5_000 * SECOND));
        long fullStart = 1_000 * SECOND;
        long fullEnd = 5_010 * SECOND;
        long zoomedStart = 1_500 * SECOND;
        long zoomedEnd = 5_500 * SECOND;
        long instant = 5_004 * SECOND;
        double fullFraction = (instant - fullStart) / (double) (fullEnd - fullStart);
        double zoomedFraction = (instant - zoomedStart) / (double) (zoomedEnd - zoomedStart);
        long fromFull = SharedTimelineHit.absoluteAt(fullStart, fullEnd, fullFraction);
        long fromZoom = SharedTimelineHit.absoluteAt(zoomedStart, zoomedEnd, zoomedFraction);
        assertEquals("mf4-b", SharedTimelineHit.select(fromFull, files).orElseThrow().measurementId());
        assertEquals("mf4-b", SharedTimelineHit.select(fromZoom, files).orElseThrow().measurementId());
        assertEquals(4 * SECOND, SharedTimelineHit.select(fromZoom, files).orElseThrow().storedTimestampNanos());
    }

    @Test
    void unknownTimezonePanesAreIdentifiedByThePaneUnderThePointer() {
        List<GraphTrackArrangement.Pane> panes = GraphTrackArrangement.panes(
                GraphTrackArrangement.Mode.INDEPENDENT_RELATIVE,
                List.of(
                        new GraphTrackArrangement.Input(0, SECOND, null),
                        new GraphTrackArrangement.Input(0, SECOND, null)),
                0, 200);
        assertEquals(0, GraphTrackArrangement.paneContaining(panes, panes.get(0).x0() + 1));
        assertEquals(1, GraphTrackArrangement.paneContaining(panes, panes.get(1).x1() - 1));
        double gap = (panes.get(0).x1() + panes.get(1).x0()) / 2.0;
        assertEquals(-1, GraphTrackArrangement.paneContaining(panes, gap));
        List<GraphTrackArrangement.Pane> shared = GraphTrackArrangement.panes(
                GraphTrackArrangement.Mode.SHARED_ABSOLUTE,
                List.of(
                        new GraphTrackArrangement.Input(0, SECOND, 0L),
                        new GraphTrackArrangement.Input(0, SECOND, 50 * SECOND)),
                0, 200);
        assertEquals(-1, GraphTrackArrangement.paneContaining(shared, 100));
    }

    @Test
    void gapBetweenComparableFilesNamesNoMeasurement() {
        Optional<SharedTimelineHit.Hit> hit = SharedTimelineHit.select(50 * SECOND, List.of(
                segment("with-gps", "Speed", 0, 10 * SECOND, 0L),
                segment("no-gps", "Speed", 0, 10 * SECOND, 100 * SECOND)));
        assertTrue(hit.isEmpty());
        assertEquals("no-gps", SharedTimelineHit.select(104 * SECOND, List.of(
                segment("with-gps", "Speed", 0, 10 * SECOND, 0L),
                segment("no-gps", "Speed", 0, 10 * SECOND, 100 * SECOND))).orElseThrow().measurementId());
    }

    @Test
    void rapidMovementAndGpsClickKeepStoredTimeAndAbsolutePosition() {
        SharedTimeSelectionModel model = new SharedTimeSelectionModel();
        assertTrue(model.selectMeasurementPoint("mf4-a", 5L, 1_005L, true, true, TimeSelection.Origin.WAVEFORM));
        assertTrue(model.selectMeasurementPoint("mf4-b", 5L, 2_005L, true, true, TimeSelection.Origin.WAVEFORM));
        assertEquals("mf4-b", model.measurementCursor().measurementId());
        assertEquals(5L, model.measurementCursor().relativeTimestampNanos());
        assertEquals(2_005L, model.measurementCursor().absoluteTimestampNanos());
        long token = model.measurementCursor().token();
        assertTrue(model.selectMeasurementPoint("mf4-a", 7L, 1_007L, true, true, TimeSelection.Origin.MAP));
        assertTrue(model.measurementCursor().token() > token);
        assertEquals(7L, model.measurementCursor().relativeTimestampNanos());
        assertEquals(1_007L, model.measurementCursor().absoluteTimestampNanos());
        assertEquals(1_007L, GraphTrackArrangement.addSaturating(1_000L, 7L));
    }

    @Test
    void oneCandidateWhenTheOtherTraceIsOutsideThePointerTolerance() {
        List<SharedTimelineHit.Segment> files = overlap();
        List<SharedTimelineHit.Candidate> hits = SharedTimelineHit.withinTolerance(
                15 * SECOND, files, 113.0, new double[] {113.0, 331.0, 221.0}, 8);
        assertEquals(1, hits.size());
        assertEquals("utc-a", hits.getFirst().measurementId());
        assertEquals(15 * SECOND, hits.getFirst().storedTimestampNanos());
        assertEquals(0.0, hits.getFirst().distance());
    }

    @Test
    void coincidentPixelsKeepEveryRecording() {
        List<SharedTimelineHit.Segment> files = overlap();
        double[] pixels = {40.0, 40.0, 40.0};
        List<SharedTimelineHit.Candidate> hits = SharedTimelineHit.withinTolerance(15 * SECOND, files, 40.0, pixels, 8);
        assertEquals(List.of("utc-c", "utc-b", "utc-a"), hits.stream().map(SharedTimelineHit.Candidate::measurementId).toList());
        assertEquals(5 * SECOND, hits.getFirst().storedTimestampNanos());
        assertEquals(15 * SECOND, hits.get(2).storedTimestampNanos());
        assertEquals(15 * SECOND, hits.getFirst().absoluteTimestampNanos());
        assertTrue(hits.stream().allMatch(hit -> hit.distance() == 0.0));
        assertEquals(2, hits.getFirst().segmentIndex());
        assertEquals(0, hits.get(2).segmentIndex());
    }

    @Test
    void twoCoincidentValuesStayDistinctCandidates() {
        List<SharedTimelineHit.Candidate> hits = SharedTimelineHit.withinTolerance(
                15 * SECOND,
                List.of(overlap().get(0), overlap().get(2)),
                40.0,
                new double[] {40.0, 40.0},
                8);
        assertEquals(2, hits.size());
        assertEquals("utc-c", hits.get(0).measurementId());
        assertEquals("utc-a", hits.get(1).measurementId());
        assertEquals("VehicleSpeed", hits.get(0).signalName());
        assertEquals(5 * SECOND, hits.get(0).storedTimestampNanos());
        assertEquals(15 * SECOND, hits.get(1).storedTimestampNanos());
    }

    @Test
    void differentPhysicalValuesAtTheSameInstantStaySelectableInsideTolerance() {
        List<SharedTimelineHit.Candidate> wide = SharedTimelineHit.withinTolerance(
                15 * SECOND, overlap(), 200.0, new double[] {113.0, 331.0, 221.0}, 150);
        assertEquals(3, wide.size());
        List<SharedTimelineHit.Candidate> nearRear = SharedTimelineHit.withinTolerance(
                15 * SECOND, overlap(), 331.0, new double[] {113.0, 331.0, 221.0}, 8);
        assertEquals("utc-b", nearRear.getFirst().measurementId());
        assertEquals(1, nearRear.size());
    }

    @Test
    void unknownClocksAreNotSharedCandidates() {
        List<SharedTimelineHit.Candidate> hits = SharedTimelineHit.withinTolerance(5 * SECOND, List.of(
                segment("mdf3-a", "Speed", 0, 20 * SECOND, null),
                segment("mdf3-b", "Speed", 0, 20 * SECOND, null)), 1.0, new double[] {1.0, 1.0}, 8);
        assertTrue(hits.isEmpty());
    }

    private static List<SharedTimelineHit.Segment> overlap() {
        return List.of(
                segment("utc-a", "VehicleSpeed", 0, 20 * SECOND, 0L),
                segment("utc-b", "VehicleSpeed", 0, 20 * SECOND, 10 * SECOND),
                segment("utc-c", "VehicleSpeed", 0, 20 * SECOND, 10 * SECOND));
    }

    private static SharedTimelineHit.Segment segment(
            String id, String signal, long start, long end, Long origin) {
        return new SharedTimelineHit.Segment(id, signal, start, end, origin);
    }

}
