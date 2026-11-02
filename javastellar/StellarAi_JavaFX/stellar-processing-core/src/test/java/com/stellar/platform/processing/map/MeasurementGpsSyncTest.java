package com.stellar.platform.processing.map;

import com.stellar.platform.processing.graph.SharedTimelineHit;
import com.stellar.platform.processing.time.MeasurementCursor;
import com.stellar.platform.processing.time.SharedTimeSelectionModel;
import com.stellar.platform.processing.time.TimeSelection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeasurementGpsSyncTest {

    @Test
    void oneGpsFileResolvesInsideThatFile() {
        ProjectRoute route = route(List.of(gps("file-1", "one.dat", 1_000_000_000L, 18.5, 73.8)));
        MeasurementGpsSync.Match match = MeasurementGpsSync.resolve(route, "file-1", 1_000_000_000L);
        assertEquals(MeasurementGpsSync.Status.MATCHED, match.status());
        assertEquals(18.5, match.latitude());
        assertEquals(73.8, match.longitude());
        assertEquals(1_000_000_000L, match.sampleTimestampNanos());
    }

    @Test
    void overlappingRelativeTimesStayOnTheNamedFile() {
        ProjectRoute route = route(List.of(
                gps("file-a", "a.dat", 0L, 19.0, 72.0),
                gps("file-b", "b.dat", 0L, 17.5, 74.0)));
        MeasurementGpsSync.Match match = MeasurementGpsSync.resolve(route, "file-b", 0L);
        assertEquals("file-b", match.measurementId());
        assertEquals("b.dat", match.displayName());
        assertEquals(17.5, match.latitude());
        assertEquals(74.0, match.longitude());
    }

    @Test
    void seventyThreeFilesFollowFileFocusIncludingTheLastFile() {
        List<RouteSegment> segments = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 73; i++) {
            String id = "m-" + i;
            ids.add(id);
            segments.add(gps(id, "file-" + i + ".dat", 5_000_000_000L, 10.0 + i, 70.0 + i));
        }
        ProjectRoute route = route(segments);
        assertEquals(0, MeasurementGpsSync.fileIndex(ids, "m-0"));
        assertEquals(1, MeasurementGpsSync.fileIndex(ids, "m-1"));
        assertEquals(72, MeasurementGpsSync.fileIndex(ids, "m-72"));
        assertEquals(10.0, MeasurementGpsSync.resolve(route, "m-0", 5_000_000_000L).latitude());
        assertEquals(11.0, MeasurementGpsSync.resolve(route, "m-1", 5_000_000_000L).latitude());
        MeasurementGpsSync.Match last = MeasurementGpsSync.resolve(route, "m-72", 5_000_000_000L);
        assertEquals(82.0, last.latitude());
        assertEquals(142.0, last.longitude());
        assertEquals("file-72.dat", last.displayName());
    }

    @Test
    void sharedTimelineOwnerWithoutGpsDoesNotReadTheOtherFile() {
        SharedTimelineHit.Hit owner = SharedTimelineHit.select(104L, java.util.List.of(
                new SharedTimelineHit.Segment("with-gps", "Speed", 0L, 10L, 0L),
                new SharedTimelineHit.Segment("no-gps", "Speed", 0L, 10L, 100L))).orElseThrow();
        assertEquals("no-gps", owner.measurementId());
        assertEquals(4L, owner.storedTimestampNanos());
        ProjectRoute route = route(List.of(
                gps("with-gps", "a.mf4", 4L, 11.0, 22.0),
                new RouteSegment("no-gps", "b.mf4", MapRoute.emptyRoute())));
        MeasurementGpsSync.Match missing = MeasurementGpsSync.resolve(
                route, owner.measurementId(), owner.storedTimestampNanos());
        assertEquals(MeasurementGpsSync.Status.MISSING_GPS, missing.status());
        assertEquals("b.mf4", missing.displayName());
        assertTrue(Double.isNaN(missing.latitude()));
        assertEquals(11.0, MeasurementGpsSync.resolve(route, "with-gps", 4L).latitude());
    }

    @Test
    void fileWithoutGpsDoesNotUseAnotherRoute() {
        ProjectRoute route = route(List.of(
                gps("file-1", "one.dat", 0L, 19.0, 72.0),
                new RouteSegment("file-2", "two.dat", MapRoute.emptyRoute())));
        MeasurementGpsSync.Match match = MeasurementGpsSync.resolve(route, "file-2", 0L);
        assertEquals(MeasurementGpsSync.Status.MISSING_GPS, match.status());
        assertEquals("two.dat", match.displayName());
        assertEquals(-1, match.sampleIndex());
        assertTrue(Double.isNaN(match.latitude()));
    }

    @Test
    void comparableAndUnknownClocksStillResolveByMeasurement() {
        ProjectRoute route = route(List.of(
                gps("utc-a", "a.dat", 0L, 1.0, 2.0),
                gps("utc-b", "b.dat", 0L, 3.0, 4.0)));
        MeasurementCursor comparable = new MeasurementCursor("utc-b", 0L, 3_600_000_000_000L, true, 1L, true);
        MeasurementCursor unknown = new MeasurementCursor("utc-a", 0L, null, false, 2L, true);
        MeasurementGpsSync.Match utc = MeasurementGpsSync.resolve(route, comparable.measurementId(), comparable.relativeTimestampNanos());
        MeasurementGpsSync.Match relative = MeasurementGpsSync.resolve(route, unknown.measurementId(), unknown.relativeTimestampNanos());
        assertEquals(3.0, utc.latitude());
        assertEquals(4.0, utc.longitude());
        assertEquals(1.0, relative.latitude());
        assertNotEquals(utc.latitude(), relative.latitude());
    }

    @Test
    void cursorMovementStaysInsideTheFocusedFile() {
        RouteSegment file = gpsSeries("file-2", "two.dat", 0L, 1_000_000_000L, 17.2, 73.4);
        ProjectRoute route = route(List.of(
                gps("file-1", "one.dat", 0L, 19.0, 72.0),
                file));
        for (long t = 0L; t <= 2_000_000_000L; t += 500_000_000L) {
            MeasurementGpsSync.Match match = MeasurementGpsSync.resolve(route, "file-2", t);
            assertEquals("file-2", match.measurementId());
            assertEquals(17.2, match.latitude());
            assertEquals(73.4, match.longitude());
        }
    }

    @Test
    void sameStoredTimeInAnotherFileIsNotDroppedAndStaleTokensLose() {
        SharedTimeSelectionModel model = new SharedTimeSelectionModel();
        List<Long> applied = new ArrayList<>();
        model.addListener(selection -> {
            MeasurementCursor cursor = model.measurementCursor();
            if (cursor.token() == model.measurementCursor().token()) {
                applied.add(cursor.token());
            }
        });
        assertTrue(model.selectMeasurementPoint("file-1", 5L, null, false, true, TimeSelection.Origin.WAVEFORM));
        long first = model.measurementCursor().token();
        assertTrue(model.selectMeasurementPoint("file-2", 5L, null, false, true, TimeSelection.Origin.WAVEFORM));
        assertEquals("file-2", model.measurementCursor().measurementId());
        assertTrue(model.measurementCursor().token() > first);
        assertFalse(model.selectMeasurementPoint("file-2", 5L, null, false, true, TimeSelection.Origin.MAP));
        MeasurementCursor stale = new MeasurementCursor("file-1", 5L, null, false, first, true);
        assertTrue(stale.token() != model.measurementCursor().token());
        assertEquals(2, applied.size());
    }

    private static ProjectRoute route(List<RouteSegment> segments) {
        int gps = 0;
        for (RouteSegment segment : segments) {
            if (segment.hasRoute()) {
                gps++;
            }
        }
        return new ProjectRoute(segments, segments.size(), gps);
    }

    private static RouteSegment gps(String id, String name, long timestamp, double latitude, double longitude) {
        MapRouteModel model = new MapRouteModel(
                new long[] {timestamp},
                new double[] {latitude},
                new double[] {longitude},
                new double[] {0},
                new double[] {0},
                new boolean[] {false});
        return new RouteSegment(id, name, new MapRoute(model));
    }

    private static RouteSegment gpsSeries(String id, String name, long start, long step, double latitude, double longitude) {
        long[] times = new long[5];
        double[] lat = new double[5];
        double[] lon = new double[5];
        double[] alt = new double[5];
        double[] distance = new double[5];
        boolean[] breaks = new boolean[5];
        for (int i = 0; i < 5; i++) {
            times[i] = start + step * i;
            lat[i] = latitude;
            lon[i] = longitude;
        }
        MapRouteModel model = new MapRouteModel(times, lat, lon, alt, distance, breaks);
        return new RouteSegment(id, name, new MapRoute(model));
    }
}
