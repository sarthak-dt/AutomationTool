package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.GraphSnapshotMetadata;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineTimeBasisTest {

    @Test
    void historicalSlotSnapshotKeepsItsBytesAndNamesTheLayoutSlot() throws Exception {
        byte[] png = new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3};
        Path image = Files.createTempFile("historical-slot", ".png");
        Files.write(image, png);
        GraphSnapshotMetadata captured = new GraphSnapshotMetadata(
                "old-shot", "old-inv", Instant.parse("2026-10-25T14:07:29Z"),
                0L, 1_000_000_000L, List.of(), List.of("CH01"), "STACKED", "",
                image.toString(), List.of("°C"), "Closure graph", "");
        String legacyJson = captured.toJson().replace(",\"timeBasis\":\"\"", "");
        GraphSnapshotMetadata reread = GraphSnapshotMetadata.fromJson(legacyJson);
        assertEquals("", reread.timeBasis());
        assertEquals(0L, reread.rangeStartNanos());
        assertEquals(1_000_000_000L, reread.rangeEndNanos());
        String text = GraphSnapshotLinks.describe(reread, false);
        assertTrue(text.contains("0.000 s → 1.000 s"), text);
        assertTrue(text.contains("layout slot"), text);
        assertTrue(text.contains("not elapsed recording time"), text);
        assertTrue(text.contains("not rewritten"), text);
        assertEquals(List.of(png[0], png[1], png[2]), List.of(Files.readAllBytes(image)[0],
                Files.readAllBytes(image)[1], Files.readAllBytes(image)[2]));
        assertEquals(png.length, Files.size(image));
        Files.deleteIfExists(image);
    }

    @Test
    void elapsedSnapshotStatesMeasurementTime() {
        GraphSnapshotMetadata live = new GraphSnapshotMetadata(
                "live", "inv", Instant.EPOCH, 84_940_000L, 1_799_786_920_000L,
                List.of(), List.of("CH01", "CH02"), "STACKED", "", "live.png",
                List.of("°C", "°C"), "Sierra graph", "MEASUREMENT_ELAPSED");
        String text = GraphSnapshotLinks.describe(live, false);
        assertTrue(text.contains("0.085 s → 1799.787 s"), text);
        assertTrue(text.contains("measurement-relative elapsed time"), text);
        assertFalse(text.contains("layout slot"), text);
    }

    @Test
    void pointAndWindowStayOnTheRecordingClock() {
        double start = 84_940_000d / 1_000_000_000d;
        double end = 1_799_786_920_000d / 1_000_000_000d;
        double windowEnd = 40.065d;
        double[] time = {start, windowEnd, end};
        double[] values = {25.4d, 30.0d, 51.0d};
        var tracks = List.of(new SignalReportRenderer.Track("CH01", "°C", time, values));
        SignalReportRenderer.ChartLayout layout = SignalReportRenderer.alignedLayout(tracks);
        SignalReportRenderer.TrackFrame frame = layout.tracks().getFirst();
        double span = frame.xMax() - frame.xMin();
        double pointFraction = (start - frame.xMin()) / span;
        double windowFraction = (windowEnd - frame.xMin()) / span;
        assertEquals(start, frame.xMin(), 1e-9);
        assertEquals(end, frame.xMax(), 1e-6);
        assertTrue(pointFraction < 0.001d, "point fraction " + pointFraction);
        assertTrue(windowFraction > 0.01d && windowFraction < 0.03d, "window fraction " + windowFraction);
        double slotFraction = start / 1.0d;
        assertTrue(slotFraction > 0.08d && slotFraction < 0.09d);
        assertTrue(pointFraction < slotFraction / 10d);
    }
}
