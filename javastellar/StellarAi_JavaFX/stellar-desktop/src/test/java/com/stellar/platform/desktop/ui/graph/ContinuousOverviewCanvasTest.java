package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphLogicalTrack;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.processing.graph.GraphTrackSegment;
import com.stellar.platform.processing.graph.timeline.UnknownOrderLayout;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Layout-slot overview on the existing canvas. This is not a decoded 73-file project. */
class ContinuousOverviewCanvasTest {

    private static final long SLOT = UnknownOrderLayout.EQUAL_SLOT_SPAN;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @Test
    void seventyThreeSlotsStayOneTrackAndOneColour() throws Exception {
        JavaFxTestSupport.runOnFx("73 layout slots", () -> {
            AutomotiveSignalCanvas canvas = show(List.of(track("VehicleSpeed", 0, 73, true)));
            assertEquals(1, canvas.logicalTrackCountForTest());
            assertEquals(73, canvas.segmentCountForTest(0));
            assertEquals(GraphSeriesPalette.at(0), canvas.logicalTrackColorForTest(0));
            assertTrue(canvas.unknownOrderOverviewForTest());
            assertEquals("UNKNOWN ORDER · slot position is not elapsed time", canvas.overviewCaptionForTest());
            assertEquals("Slot position is not elapsed time", canvas.timeAxisCaptionForTest());
            assertTrue(canvas.getPrefHeight() < 400);
        });
    }

    @Test
    void oneHundredLogicalTracksKeepAVirtualHeightAndAStableColour() throws Exception {
        JavaFxTestSupport.runOnFx("100 logical tracks", () -> {
            List<GraphLogicalTrack> tracks = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                tracks.add(track("Signal" + i, i, 1, true));
            }
            long started = System.nanoTime();
            AutomotiveSignalCanvas canvas = show(tracks);
            canvas.setVisibleWindow(400, 0);
            canvas.layout();
            canvas.snapshot(null, null);
            long paintMs = (System.nanoTime() - started) / 1_000_000L;
            assertEquals(100, canvas.logicalTrackCountForTest());
            assertTrue(canvas.getPrefHeight() > 8_000, "virtual height " + canvas.getPrefHeight());
            assertEquals(GraphSeriesPalette.at(0), canvas.logicalTrackColorForTest(0));
            assertEquals(GraphSeriesPalette.at(8), canvas.logicalTrackColorForTest(8));
            assertNotEquals(canvas.logicalTrackColorForTest(0), canvas.logicalTrackColorForTest(1));
            javafx.scene.paint.Color first = canvas.logicalTrackColorForTest(0);
            canvas.setVisibleWindow(400, 4_000);
            canvas.layout();
            assertEquals(first, canvas.logicalTrackColorForTest(0));
            assertEquals(24, GraphSeriesPalette.size());
            assertEquals("#0f8a96", GraphSeriesPalette.cssAt(0));
            assertEquals("#a16207", GraphSeriesPalette.cssAt(7));
            assertTrue(GraphSeriesPalette.dashesAt(0) == null);
            assertTrue(GraphSeriesPalette.dashesAt(24) != null);
            System.out.println("M2_22_100_TRACK_HEIGHT=" + canvas.getPrefHeight());
            System.out.println("M2_22_100_TRACK_PAINT_MS=" + paintMs);
        });
    }

    @Test
    void threeHundredSlotsRemainOneTrack() throws Exception {
        JavaFxTestSupport.runOnFx("300 layout slots", () -> {
            long started = System.nanoTime();
            AutomotiveSignalCanvas canvas = show(List.of(track("VehicleSpeed", 0, 300, true)));
            canvas.snapshot(null, null);
            long renderMs = (System.nanoTime() - started) / 1_000_000L;
            assertEquals(1, canvas.logicalTrackCountForTest());
            assertEquals(300, canvas.segmentCountForTest(0));
            assertEquals(GraphSeriesPalette.at(0), canvas.logicalTrackColorForTest(0));
            assertTrue(canvas.getPrefHeight() < 400, "rendered track count must not follow file count");
            assertTrue(renderMs < 30_000, "300 synthetic slots rendered in " + renderMs + " ms");
            System.out.println("M2_300_RENDER_MS=" + renderMs);
        });
    }

    @Test
    void writesUnknownOrderAndUtcCanvasEvidence() throws Exception {
        JavaFxTestSupport.runOnFx("canvas evidence", () -> {
            AutomotiveSignalCanvas unknown = show(List.of(
                    track("VehicleSpeed", 0, 73, true),
                    track("Coolant", 1, 73, false)));
            save(unknown, "unknown-order-73-two-signals.png");
            AutomotiveSignalCanvas utc = showUtc();
            save(utc, "verified-utc-gap.png");
            assertEquals("UNKNOWN ORDER · slot position is not elapsed time", unknown.overviewCaptionForTest());
            assertEquals("", utc.overviewCaptionForTest());
        });
    }

    @Test
    void overlayPlacesEachRecordingInItsSlotInsteadOfTheStoredClock() throws Exception {
        JavaFxTestSupport.runOnFx("overlay slots", () -> {
            AutomotiveSignalCanvas canvas = show(List.of(
                    track("IN_Vehicle_speed", 0, 73, true),
                    track("IN_Coolant_temperature", 1, 73, true)));
            canvas.setLayoutMode(AutomotiveSignalCanvas.LayoutMode.OVERLAY_MULTI_AXIS);
            canvas.layout();
            long started = System.nanoTime();
            WritableImage image = canvas.snapshot(null, null);
            long paintMs = (System.nanoTime() - started) / 1_000_000L;
            var raster = SwingFXUtils.fromFXImage(image, null);
            int bands = 0;
            for (int band = 0; band < 8; band++) {
                int x0 = 100 + band * 90;
                if (hasWaveformInk(raster, x0, x0 + 36, 90, 420)) {
                    bands++;
                }
            }
            assertTrue(bands >= 4, "overlay ink collapsed into " + bands + " bands");
            List<Double> speed = canvas.overlayLayoutXsForTest(0);
            List<Double> coolant = canvas.overlayLayoutXsForTest(1);
            assertEquals(73, speed.size());
            assertEquals(73, coolant.size());
            assertTrue(speed.get(0) < speed.get(37), "slot 1 x=" + speed.get(0) + " slot 38 x=" + speed.get(37));
            assertTrue(speed.get(37) < speed.get(72));
            assertTrue(speed.get(72) - speed.get(0) > 200, "recordings collapsed onto one x");
            double left = 24;
            double right = canvas.getWidth() - 24;
            for (int i = 0; i < speed.size(); i++) {
                assertEquals(speed.get(i), coolant.get(i), 0.01, "slot " + i);
                assertTrue(speed.get(i) >= left && speed.get(i) <= right, "x " + speed.get(i));
            }
            assertEquals(GraphSeriesPalette.at(0), canvas.logicalTrackColorForTest(0));
            assertEquals(GraphSeriesPalette.at(1), canvas.logicalTrackColorForTest(1));
            canvas.fireEvent(new MouseEvent(
                    MouseEvent.MOUSE_MOVED, speed.get(37), 220, speed.get(37), 220,
                    MouseButton.NONE, 0,
                    false, false, false, false,
                    false, false, false, false, false, false, null));
            AutomotiveSignalCanvas.HoverPoint point = canvas.hoverPointProperty().get();
            assertTrue(point != null, "overlay hover");
            assertTrue(point.timestampNanos() < SLOT, "stored time " + point.timestampNanos());
            assertTrue(point.signalName().contains("file-37.dat"), point.signalName());
            assertTrue(paintMs < 2_000, "overlay paint " + paintMs + " ms");
            System.out.println("OVERLAY_SLOT_PAINT_MS=" + paintMs);
        });
    }

    @Test
    void twoSignalsKeepSeparateColoursAcrossSlots() throws Exception {
        JavaFxTestSupport.runOnFx("two signals", () -> {
            AutomotiveSignalCanvas canvas = show(List.of(
                    track("VehicleSpeed", 0, 73, true),
                    track("Coolant", 1, 73, false)));
            assertEquals(2, canvas.logicalTrackCountForTest());
            assertEquals(GraphSeriesPalette.at(0), canvas.logicalTrackColorForTest(0));
            assertEquals(GraphSeriesPalette.at(1), canvas.logicalTrackColorForTest(1));
        });
    }

    @Test
    void boundaryBelongsToTheNextSlotAndHoverUsesStoredTime() throws Exception {
        JavaFxTestSupport.runOnFx("slot boundary", () -> {
            AutomotiveSignalCanvas canvas = show(List.of(track("VehicleSpeed", 0, 2, true)));
            double left = 82;
            double right = 960 - 18;
            double boundary = left + (right - left) / 2.0;
            assertEquals(1, canvas.layoutOwnerForTest(0, boundary));
            assertEquals(0, canvas.layoutOwnerForTest(0, boundary - 2));
            var frames = canvas.stackedFramesForTest();
            double y = frames.getFirst().top() + 40;
            canvas.fireEvent(new MouseEvent(
                    MouseEvent.MOUSE_MOVED, boundary + 4, y, boundary + 4, y,
                    MouseButton.NONE, 0,
                    false, false, false, false,
                    false, false, false, false, false, false, null));
            AutomotiveSignalCanvas.HoverPoint point = canvas.hoverPointProperty().get();
            assertTrue(point != null, "hover");
            assertTrue(point.signalName().contains("file-1.dat"), point.signalName());
            assertTrue(point.timestampNanos() < SLOT, "stored time must not be the layout position: " + point.timestampNanos());
            assertNotEquals(SLOT, point.timestampNanos());
        });
    }

    private static AutomotiveSignalCanvas show(List<GraphLogicalTrack> tracks) {
        AutomotiveSignalCanvas canvas = new AutomotiveSignalCanvas();
        Map<SignalCatalogKey, Integer> colors = new LinkedHashMap<>();
        Map<SignalCatalogKey, String> labels = new LinkedHashMap<>();
        for (int i = 0; i < tracks.size(); i++) {
            GraphLogicalTrack track = tracks.get(i);
            colors.put(track.logicalKey(), i);
            labels.put(track.logicalKey(), track.logicalKey().signalName());
            for (GraphTrackSegment segment : track.segments()) {
                labels.put(segment.series().key(),
                        track.logicalKey().signalName() + " · " + segment.measurementLabel());
            }
        }
        canvas.setTracePresentation(labels, colors);
        canvas.setUnknownOrderOverview(true);
        canvas.setLogicalTracks(tracks);
        long end = Math.max(SLOT, tracks.getFirst().segments().size() * SLOT);
        canvas.setSharedViewport(0L, end);
        canvas.setViewportFloorHeight(360);
        Stage stage = new Stage();
        stage.setScene(new Scene(canvas, 960, 520));
        stage.show();
        canvas.resize(960, 520);
        canvas.layout();
        return canvas;
    }

    private static GraphLogicalTrack track(String name, int colorIndex, int files, boolean withSamples) {
        SignalCatalogKey logical = new SignalCatalogKey(
                name, SignalSourceType.MF4_NATIVE, "DIRECT_MEASUREMENT", 0L, "PROJECT", "");
        List<GraphTrackSegment> segments = new ArrayList<>();
        for (int i = 0; i < files; i++) {
            SignalCatalogKey physical = new SignalCatalogKey(
                    name, SignalSourceType.MF4_NATIVE, "DIRECT_MEASUREMENT", 0L, "src-" + i, "");
            long stored = 5_000_000L + i;
            List<GraphBucket> buckets = withSamples || i == 0
                    ? List.of(new GraphBucket(stored, 10 + colorIndex, stored, 10, stored + 1, 12, stored + 1, 11, 1))
                    : List.of();
            GraphRenderSeries series = new GraphRenderSeries(
                    physical, "km/h", buckets.size(), buckets.size() <= 1, 0L, 10_000_000L, buckets);
            segments.add(new GraphTrackSegment(
                    series, "file-" + i + ".dat", null, i * SLOT, (i + 1) * SLOT));
        }
        return new GraphLogicalTrack(logical, segments, false);
    }

    private static AutomotiveSignalCanvas showUtc() {
        long second = 1_000_000_000L;
        SignalCatalogKey logical = new SignalCatalogKey(
                "VehicleSpeed", SignalSourceType.MF4_NATIVE, "DIRECT_MEASUREMENT", 0L, "PROJECT", "");
        SignalCatalogKey first = new SignalCatalogKey(
                "VehicleSpeed", SignalSourceType.MF4_NATIVE, "DIRECT_MEASUREMENT", 0L, "utc-a", "");
        SignalCatalogKey secondKey = new SignalCatalogKey(
                "VehicleSpeed", SignalSourceType.MF4_NATIVE, "DIRECT_MEASUREMENT", 0L, "utc-b", "");
        GraphRenderSeries early = new GraphRenderSeries(first, "km/h", 1, true, 0L, 20 * second,
                List.of(new GraphBucket(5 * second, 40, 5 * second, 40, 15 * second, 55, 15 * second, 48, 2)));
        GraphRenderSeries later = new GraphRenderSeries(secondKey, "km/h", 1, true, 0L, 20 * second,
                List.of(new GraphBucket(2 * second, 30, 2 * second, 30, 12 * second, 36, 12 * second, 33, 2)));
        GraphLogicalTrack track = new GraphLogicalTrack(logical, List.of(
                new GraphTrackSegment(early, "utc-a.mf4", 0L),
                new GraphTrackSegment(later, "utc-b.mf4", 40 * second)), true);
        AutomotiveSignalCanvas canvas = new AutomotiveSignalCanvas();
        canvas.setTracePresentation(
                Map.of(logical, "VehicleSpeed", first, "VehicleSpeed · utc-a.mf4", secondKey, "VehicleSpeed · utc-b.mf4"),
                Map.of(logical, 0, first, 0, secondKey, 0));
        canvas.setUnknownOrderOverview(false);
        canvas.setLogicalTracks(List.of(track));
        canvas.setSharedViewport(0L, 60 * second);
        canvas.setViewportFloorHeight(360);
        Stage stage = new Stage();
        stage.setScene(new Scene(canvas, 960, 520));
        stage.show();
        canvas.resize(960, 520);
        canvas.layout();
        return canvas;
    }

    private static boolean hasWaveformInk(java.awt.image.BufferedImage image, int x0, int x1, int y0, int y1) {
        int maxX = Math.min(image.getWidth() - 1, x1);
        int maxY = Math.min(image.getHeight() - 1, y1);
        for (int y = Math.max(0, y0); y <= maxY; y++) {
            for (int x = Math.max(0, x0); x <= maxX; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                int spread = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                if (spread > 28 && (r + g + b) < 680) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void save(AutomotiveSignalCanvas canvas, String name) throws Exception {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "stellar-accuracy", "m2-evidence");
        Files.createDirectories(dir);
        WritableImage image = canvas.snapshot(null, null);
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", dir.resolve(name).toFile());
    }
}
