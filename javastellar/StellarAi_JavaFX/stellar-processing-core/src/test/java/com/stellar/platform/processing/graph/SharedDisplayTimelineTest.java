package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.cache.GraphQueryCache;
import com.stellar.platform.processing.time.SharedTimeSelectionModel;
import com.stellar.platform.processing.time.TimeSelection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedDisplayTimelineTest {

    private static final long S = 1_000_000_000L;
    private static final long ORIGIN = 1_674_000_000_000_000_000L;

    @Test
    void threeUtcRecordingsShareSixtySecondsWithOverlapAndGap() {
        SharedDisplayTimeline timeline = recordings();
        assertEquals(0L, timeline.displayStart());
        assertEquals(60 * S, timeline.displayEnd());
        assertEquals(0L, timeline.displayOffsetNanos(timeline.member("utc-a")));
        assertEquals(10 * S, timeline.displayOffsetNanos(timeline.member("utc-c")));
        assertEquals(40 * S, timeline.displayOffsetNanos(timeline.member("utc-b")));

        assertEquals(15 * S, timeline.toDisplay(timeline.member("utc-a"), 15 * S));
        assertEquals(15 * S, timeline.toDisplay(timeline.member("utc-c"), 5 * S));
        assertEquals(45 * S, timeline.toDisplay(timeline.member("utc-b"), 5 * S));
        assertEquals(ORIGIN + 15 * S, timeline.toEpoch(15 * S));
        assertEquals(ORIGIN + 45 * S, timeline.toEpoch(45 * S));

        long[] overlapA = timeline.storedQuery(timeline.member("utc-a"), 15 * S, 15 * S);
        long[] overlapC = timeline.storedQuery(timeline.member("utc-c"), 15 * S, 15 * S);
        assertEquals(15 * S, overlapA[0]);
        assertEquals(5 * S, overlapC[0]);
        assertNull(timeline.storedQuery(timeline.member("utc-b"), 15 * S, 15 * S));
        assertNull(timeline.storedQuery(timeline.member("utc-a"), 35 * S, 35 * S));
        assertNull(timeline.storedQuery(timeline.member("utc-c"), 35 * S, 35 * S));
        assertNull(timeline.storedQuery(timeline.member("utc-b"), 35 * S, 35 * S));

        long[] onlyB = timeline.storedQuery(timeline.member("utc-b"), 45 * S, 45 * S);
        assertEquals(5 * S, onlyB[0]);
        assertEquals(5 * S, onlyB[1]);
        assertTrue(onlyB[1] < ORIGIN, "stored query must not be an epoch timestamp");
    }

    @Test
    void zoomPanAndFitStayInsideTheSharedDisplayRange() {
        SharedDisplayTimeline timeline = recordings();
        long fullStart = timeline.displayStart();
        long fullEnd = timeline.displayEnd();
        long[] zoomed = zoom(fullStart, fullEnd, 0.5, 15 * S, fullStart, fullEnd);
        assertTrue(zoomed[0] >= fullStart && zoomed[1] <= fullEnd);
        assertTrue(zoomed[1] - zoomed[0] < fullEnd - fullStart);
        long[] panned = pan(zoomed[0], zoomed[1], 40 * S, fullStart, fullEnd);
        assertTrue(panned[1] <= fullEnd);
        assertEquals(fullStart, timeline.displayStart());
        assertEquals(fullEnd, timeline.displayEnd());
        long[] fitted = new long[] {timeline.displayStart(), timeline.displayEnd()};
        assertEquals(60 * S, fitted[1] - fitted[0]);
    }

    @Test
    void queryUsesStoredTimeAndBucketsKeepTheirPhysicalValues() {
        SharedDisplayTimeline timeline = recordings();
        AtomicLong requestedStart = new AtomicLong();
        AtomicLong requestedEnd = new AtomicLong();
        SignalCatalogKey key = new SignalCatalogKey(
                "VehicleSpeed", SignalSourceType.MF4_NATIVE, "DIRECT_MEASUREMENT", 0L, "utc-a", "");
        GraphSeriesQuery query = new GraphSeriesQuery() {
            @Override
            public List<SignalSamplePoint> queryRange(SignalCatalogKey signal, long startNanos, long endNanos) {
                requestedStart.set(startNanos);
                requestedEnd.set(endNanos);
                return List.of(new SignalSamplePoint(15 * S, 113.0, 113L, ""));
            }

            @Override
            public String unit(SignalCatalogKey signal) {
                return "km/h";
            }

            @Override
            public long sampleCount(SignalCatalogKey signal) {
                return 1L;
            }
        };
        long[] stored = timeline.storedQuery(timeline.member("utc-a"), 10 * S, 20 * S);
        GraphViewportEngine engine = new GraphViewportEngine(query, 2, new GraphQueryCache(4));
        List<GraphRenderSeries> series = engine.render(new GraphViewportRequest(
                List.of(key), stored[0], stored[1], 32, timeline.displayOffsetNanos(timeline.member("utc-a"))));
        assertEquals(10 * S, requestedStart.get());
        assertEquals(20 * S, requestedEnd.get());
        GraphBucket bucket = series.getFirst().buckets().getFirst();
        assertEquals(15 * S, bucket.lastTimestampNanos());
        assertEquals(113.0, bucket.lastValue(), 0.0);
        assertEquals(SignalValidity.VALID, bucket.lastValidity());
        long display = timeline.toDisplay(timeline.member("utc-a"), bucket.lastTimestampNanos());
        assertEquals(15 * S, display);
        double fraction = (display - timeline.displayStart()) / (double) (timeline.displayEnd() - timeline.displayStart());
        assertEquals(15.0 / 60.0, fraction, 1e-9);
    }

    @Test
    void cacheKeySeparatesOriginFromTheStoredWindow() {
        SignalCatalogKey key = new SignalCatalogKey(
                "VehicleSpeed", SignalSourceType.MF4_NATIVE, "DIRECT_MEASUREMENT", 0L, "utc-c", "");
        GraphQueryCache.Key stored = new GraphQueryCache.Key(3L, key, 5 * S, 5 * S, 32, 0L);
        GraphQueryCache.Key aligned = new GraphQueryCache.Key(3L, key, 5 * S, 5 * S, 32, 10 * S);
        assertNotEquals(stored, aligned);
        assertEquals(3L, aligned.generation());
        assertEquals("utc-c", aligned.signal().sourceFile());
    }

    @Test
    void overlapPicksThePointedValueAndAGapClearsTheOwner() {
        SharedDisplayTimeline timeline = recordings();
        List<SharedTimelineHit.Segment> segments = List.of(
                segment(timeline, "utc-c", "VehicleSpeed"),
                segment(timeline, "utc-b", "VehicleSpeed"),
                segment(timeline, "utc-a", "VehicleSpeed"));
        double[] values = {331.0, Double.NaN, 113.0};
        SharedTimelineHit.Hit pointedA = SharedTimelineHit.select(15 * S, segments, 113.0, values).orElseThrow();
        SharedTimelineHit.Hit pointedC = SharedTimelineHit.select(15 * S, segments, 331.0, values).orElseThrow();
        assertEquals("utc-a", pointedA.measurementId());
        assertEquals(15 * S, pointedA.storedTimestampNanos());
        assertEquals("utc-c", pointedC.measurementId());
        assertEquals(5 * S, pointedC.storedTimestampNanos());
        assertTrue(SharedTimelineHit.select(35 * S, segments).isEmpty());

        SharedTimeSelectionModel model = new SharedTimeSelectionModel();
        model.selectMeasurementPoint("utc-a", 15 * S, timeline.toEpoch(15 * S), true, true, TimeSelection.Origin.WAVEFORM);
        assertTrue(model.selectNoOwner(timeline.toEpoch(35 * S), TimeSelection.Origin.WAVEFORM));
        assertTrue(model.explicitNoOwner());
        assertFalse(model.measurementCursor().placeMarker());
        assertEquals("", model.measurementCursor().measurementId());
        model.selectMeasurementPoint("utc-b", 5 * S, timeline.toEpoch(45 * S), true, true, TimeSelection.Origin.MAP);
        assertFalse(model.explicitNoOwner());
        assertEquals("utc-b", model.measurementCursor().measurementId());
        assertEquals(5 * S, model.measurementCursor().relativeTimestampNanos());
        assertEquals(45 * S, timeline.toDisplay(timeline.member("utc-b"), model.measurementCursor().relativeTimestampNanos()));
    }

    @Test
    void unknownClockKeepsIndependentFileFocus() {
        List<GraphTrackArrangement.Input> independent = List.of(
                new GraphTrackArrangement.Input(0L, 20 * S, null),
                new GraphTrackArrangement.Input(0L, 20 * S, null));
        assertEquals(GraphTrackArrangement.Mode.INDEPENDENT_RELATIVE,
                GraphTrackArrangement.mode(false, independent.size()));
        List<GraphTrackArrangement.Pane> panes = GraphTrackArrangement.panes(
                GraphTrackArrangement.Mode.INDEPENDENT_RELATIVE, independent, 0, 600);
        assertEquals(0L, panes.get(0).originNanos());
        assertEquals(0L, panes.get(1).originNanos());
        assertTrue(panes.get(0).x1() <= panes.get(1).x0());
    }

    @Test
    void sharedAxisIsSixtySecondsAndOneColourSlot() {
        SharedDisplayTimeline timeline = recordings();
        List<GraphTrackArrangement.Input> inputs = new ArrayList<>();
        for (String id : List.of("utc-a", "utc-c", "utc-b")) {
            SharedDisplayTimeline.Member member = timeline.member(id);
            inputs.add(new GraphTrackArrangement.Input(
                    member.storedStartNanos(), member.storedEndNanos(), timeline.displayOffsetNanos(member)));
        }
        List<GraphTrackArrangement.Pane> panes = GraphTrackArrangement.panes(
                GraphTrackArrangement.Mode.SHARED_ABSOLUTE, inputs, 0, 600);
        assertEquals(3, panes.size());
        assertEquals(0L, panes.getFirst().axisStart());
        assertEquals(60 * S, panes.getFirst().axisEnd());
        assertEquals(panes.get(0).x0(), panes.get(2).x0(), 0.0);
        assertEquals(15.0 / 60.0, fraction(panes.get(0), 15 * S), 1e-9);
        assertEquals(15.0 / 60.0, fraction(panes.get(1), 5 * S), 1e-9);
        assertEquals(45.0 / 60.0, fraction(panes.get(2), 5 * S), 1e-9);
        double gap = fraction(panes.getFirst(), 35 * S);
        assertTrue(gap > 30.0 / 60.0 && gap < 40.0 / 60.0);
    }

    private static SharedTimelineHit.Segment segment(SharedDisplayTimeline timeline, String id, String signal) {
        SharedDisplayTimeline.Member member = timeline.member(id);
        return new SharedTimelineHit.Segment(
                id, signal, member.storedStartNanos(), member.storedEndNanos(), timeline.displayOffsetNanos(member));
    }

    private static double fraction(GraphTrackArrangement.Pane pane, long stored) {
        long display = GraphTrackArrangement.addSaturating(pane.originNanos(), stored);
        return (display - pane.axisStart()) / (double) (pane.axisEnd() - pane.axisStart());
    }

    private static SharedDisplayTimeline recordings() {
        return SharedDisplayTimeline.of(List.of(
                new SharedDisplayTimeline.Member("utc-a", ORIGIN, 0L, 20 * S),
                new SharedDisplayTimeline.Member("utc-c", ORIGIN + 10 * S, 0L, 20 * S),
                new SharedDisplayTimeline.Member("utc-b", ORIGIN + 40 * S, 0L, 20 * S)));
    }

    private static long[] zoom(long start, long end, double factor, long anchor, long dataStart, long dataEnd) {
        long span = end - start;
        long target = (long) Math.floor(span * factor);
        long newStart = anchor - target / 2;
        if (newStart < dataStart) {
            newStart = dataStart;
        }
        long newEnd = newStart + target;
        if (newEnd > dataEnd) {
            newEnd = dataEnd;
            newStart = newEnd - target;
        }
        return new long[] {newStart, newEnd};
    }

    private static long[] pan(long start, long end, long shift, long dataStart, long dataEnd) {
        long span = end - start;
        long next = start + shift;
        if (next < dataStart) {
            next = dataStart;
        }
        if (next + span > dataEnd) {
            next = dataEnd - span;
        }
        return new long[] {next, next + span};
    }
}
