package com.stellar.platform.processing.map;

import com.stellar.platform.processing.graph.GraphTrackArrangement;
import com.stellar.platform.processing.graph.SharedTimelineHit;
import com.stellar.platform.processing.map.MapRoute;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.processing.map.MeasurementGpsSync;
import com.stellar.platform.processing.map.ProjectRoute;
import com.stellar.platform.processing.map.RouteSegment;
import com.stellar.platform.processing.map.time.ClockStatus;
import com.stellar.platform.processing.map.time.RecordingClock;
import com.stellar.platform.processing.map.time.RecordingClockReader;
import com.stellar.platform.processing.time.MeasurementCursor;
import com.stellar.platform.processing.time.SharedTimeSelectionModel;
import com.stellar.platform.processing.time.TimeSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two synthetic MF4 headers with verified UTC starts. These are not customer recordings.
 */
class ComparableUtcCursorTest {

    private static final long SECOND = 1_000_000_000L;

    @TempDir
    Path temp;

    @Test
    void twoMf4ClocksDriveGapOverlapAndGpsSync() throws Exception {
        Path fileA = temp.resolve("overlap-a.mf4");
        Path fileB = temp.resolve("overlap-b.mf4");
        Files.write(fileA, mf4Header(1_000 * SECOND));
        Files.write(fileB, mf4Header(1_010 * SECOND));
        RecordingClock clockA = RecordingClockReader.read(fileA, "mf4-a");
        RecordingClock clockB = RecordingClockReader.read(fileB, "mf4-b");
        assertEquals(ClockStatus.COMPARABLE, clockA.status());
        assertEquals(ClockStatus.COMPARABLE, clockB.status());
        assertTrue(clockA.comparable());
        assertTrue(clockB.comparable());

        List<SharedTimelineHit.Segment> stacked = List.of(
                new SharedTimelineHit.Segment("mf4-a", "VehicleSpeed", 0, 20 * SECOND, clockA.utcStartNanos()),
                new SharedTimelineHit.Segment("mf4-b", "VehicleSpeed", 0, 20 * SECOND, clockB.utcStartNanos()));
        long overlap = clockA.utcStartNanos() + 15 * SECOND;
        SharedTimelineHit.Hit hovered = SharedTimelineHit.select(overlap, stacked, 12.0, new double[] {10.0, 80.0}).orElseThrow();
        assertEquals("mf4-a", hovered.measurementId());
        assertEquals(15 * SECOND, hovered.storedTimestampNanos());
        SharedTimelineHit.Hit front = SharedTimelineHit.select(overlap, stacked).orElseThrow();
        assertEquals("mf4-b", front.measurementId());
        assertEquals("VehicleSpeed", front.signalName());

        long onlyB = clockB.utcStartNanos() + 4 * SECOND;
        SharedTimelineHit.Hit later = SharedTimelineHit.select(onlyB, stacked).orElseThrow();
        assertEquals("mf4-b", later.measurementId());
        assertEquals(4 * SECOND, later.storedTimestampNanos());
        long gap = clockB.utcStartNanos() + 25 * SECOND;
        assertTrue(SharedTimelineHit.select(gap, stacked).isEmpty());

        double[] overlayPixels = {40.0, 120.0};
        SharedTimelineHit.Hit overlay = SharedTimelineHit.select(overlap, stacked, 42.0, overlayPixels).orElseThrow();
        assertEquals("mf4-a", overlay.measurementId());

        ProjectRoute route = new ProjectRoute(List.of(
                gps("mf4-a", "overlap-a.mf4", 15 * SECOND, 18.1, 73.2),
                new RouteSegment("mf4-b", "overlap-b.mf4", MapRoute.emptyRoute())), 2, 1);
        MeasurementGpsSync.Match matched = MeasurementGpsSync.resolve(route, hovered.measurementId(), hovered.storedTimestampNanos());
        assertEquals(18.1, matched.latitude());
        assertEquals(73.2, matched.longitude());
        MeasurementGpsSync.Match missing = MeasurementGpsSync.resolve(route, "mf4-b", 4 * SECOND);
        assertEquals(MeasurementGpsSync.Status.MISSING_GPS, missing.status());
        assertTrue(Double.isNaN(missing.latitude()));

        SharedTimeSelectionModel model = new SharedTimeSelectionModel();
        String[] marker = {"previous-file"};
        model.addListener(selection -> {
            if (model.explicitNoOwner()) {
                marker[0] = MeasurementCursor.NO_MEASUREMENT_AT_TIME;
            } else if (model.measurementCursor().hasMeasurement() && model.measurementCursor().placeMarker()) {
                MeasurementGpsSync.Match gps = MeasurementGpsSync.resolve(
                        route, model.measurementCursor().measurementId(), model.measurementCursor().relativeTimestampNanos());
                marker[0] = gps.status() == MeasurementGpsSync.Status.MATCHED
                        ? gps.measurementId() + ":" + gps.latitude()
                        : MeasurementCursor.NO_MEASUREMENT_AT_TIME;
            }
        });
        assertTrue(model.selectMeasurementPoint(
                hovered.measurementId(), hovered.storedTimestampNanos(), hovered.absoluteTimestampNanos(),
                true, true, TimeSelection.Origin.WAVEFORM));
        assertEquals("mf4-a:18.1", marker[0]);
        assertTrue(model.selectNoOwner(gap, TimeSelection.Origin.WAVEFORM));
        assertEquals(MeasurementCursor.NO_MEASUREMENT_AT_TIME, marker[0]);
        assertFalse(model.measurementCursor().hasMeasurement());
        long stored = 4 * SECOND;
        long absolute = GraphTrackArrangement.addSaturating(clockB.utcStartNanos(), stored);
        assertTrue(model.selectMeasurementPoint("mf4-a", stored, absolute, true, true, TimeSelection.Origin.MAP));
        assertEquals(stored, model.measurementCursor().relativeTimestampNanos());
        assertEquals(absolute, model.measurementCursor().absoluteTimestampNanos());
        assertEquals("mf4-a:18.1", marker[0]);

        long zoomed = SharedTimelineHit.absoluteAt(clockA.utcStartNanos(), clockB.utcStartNanos() + 20 * SECOND, 0.2);
        assertEquals("mf4-a", SharedTimelineHit.select(zoomed, stacked).orElseThrow().measurementId());
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

    /** MF4 ID plus HD block. Flags 0 mark the start as UTC. No signal samples are written. */
    private static byte[] mf4Header(long startNanos) {
        ByteBuffer buffer = ByteBuffer.allocate(64 + 104).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("MDF     ".getBytes(StandardCharsets.US_ASCII));
        buffer.put("4.11    ".getBytes(StandardCharsets.US_ASCII));
        buffer.position(64);
        buffer.put("##HD".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(0);
        buffer.putLong(104);
        buffer.putLong(6);
        for (int i = 0; i < 6; i++) {
            buffer.putLong(0);
        }
        buffer.putLong(startNanos);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.put((byte) 0x02);
        return buffer.array();
    }
}
