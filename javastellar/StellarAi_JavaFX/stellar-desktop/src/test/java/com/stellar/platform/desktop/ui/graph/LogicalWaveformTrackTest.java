package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphLogicalTrack;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.processing.graph.GraphTrackSegment;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One logical signal stays one stacked track and one colour, including across 73 files. */
class LogicalWaveformTrackTest {

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @Test
    void oneSignalAcrossSeventyThreeFilesIsOneTrackAndOneColour() throws Exception {
        JavaFxTestSupport.runOnFx("73 files one track", () -> {
            AutomotiveSignalCanvas canvas = show(List.of(track("VehicleSpeed", 0, 73, false)));
            assertEquals(1, canvas.logicalTrackCountForTest());
            assertEquals(73, canvas.segmentCountForTest(0));
            assertEquals(73, canvas.seriesCountForTest());
            assertEquals(1, canvas.stackedFramesForTest().size());
            Color colour = canvas.logicalTrackColorForTest(0);
            assertEquals(GraphSeriesPalette.at(0), colour);
            assertTrue(canvas.getPrefHeight() < 400, "one track must not grow with file count: " + canvas.getPrefHeight());
        });
    }

    @Test
    void twoAndFiveSignalsStayDistinctTracksAndColours() throws Exception {
        JavaFxTestSupport.runOnFx("several logical tracks", () -> {
            AutomotiveSignalCanvas two = show(List.of(
                    track("VehicleSpeed", 0, 73, false),
                    track("Coolant", 1, 73, false)));
            assertEquals(2, two.logicalTrackCountForTest());
            assertEquals(2, two.stackedFramesForTest().size());
            assertEquals(GraphSeriesPalette.at(0), two.logicalTrackColorForTest(0));
            assertEquals(GraphSeriesPalette.at(1), two.logicalTrackColorForTest(1));

            List<GraphLogicalTrack> five = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                five.add(track("Signal" + i, i, 73, false));
            }
            AutomotiveSignalCanvas canvas = show(five);
            assertEquals(5, canvas.logicalTrackCountForTest());
            for (int i = 0; i < 5; i++) {
                assertEquals(GraphSeriesPalette.at(i), canvas.logicalTrackColorForTest(i));
                assertEquals(73, canvas.segmentCountForTest(i));
            }
        });
    }

    @Test
    void hoverOnSecondTrackReadsThatSignalAndTheFileUnderThePointer() throws Exception {
        JavaFxTestSupport.runOnFx("hover provenance", () -> {
            AutomotiveSignalCanvas canvas = show(List.of(
                    track("VehicleSpeed", 0, 2, false),
                    track("Coolant", 1, 2, false)));
            var frames = canvas.stackedFramesForTest();
            assertEquals(2, frames.size());
            double y = frames.get(1).top() + 8;
            canvas.fireEvent(new MouseEvent(
                    MouseEvent.MOUSE_MOVED, 760, y, 760, y,
                    MouseButton.NONE, 0,
                    false, false, false, false,
                    false, false, false, false, false, false, null));
            AutomotiveSignalCanvas.HoverPoint point = canvas.hoverPointProperty().get();
            assertTrue(point != null, "hover point");
            assertTrue(point.signalName().contains("Coolant"), point.signalName());
            assertTrue(point.signalName().contains("file-1.dat"), point.signalName());
            assertTrue(point.signalName().indexOf("VehicleSpeed") < 0, point.signalName());
        });
    }

    @Test
    void focusedFileNavigatorKeepsOneTrackAndLabelsTimeWithinTheFile() throws Exception {
        JavaFxTestSupport.runOnFx("file navigator", () -> {
            AutomotiveSignalCanvas canvas = show(List.of(track("VehicleSpeed", 0, 1, false)));
            canvas.setFileNavigator(11, 73);
            assertEquals(1, canvas.logicalTrackCountForTest());
            assertEquals(1, canvas.segmentCountForTest(0));
            assertEquals(73, canvas.fileNavigatorCountForTest());
            assertEquals(11, canvas.fileNavigatorIndexForTest());
            assertEquals("Time within this file (s)", canvas.timeAxisCaptionForTest());
            assertEquals(GraphSeriesPalette.at(3), canvas.logicalTrackColorForTest(0));
        });
    }

    @Test
    void oneFileIsOneTrack() throws Exception {
        JavaFxTestSupport.runOnFx("one file", () -> {
            AutomotiveSignalCanvas canvas = show(List.of(track("VehicleSpeed", 3, 1, false)));
            assertEquals(1, canvas.logicalTrackCountForTest());
            assertEquals(1, canvas.segmentCountForTest(0));
            assertEquals(GraphSeriesPalette.at(3), canvas.logicalTrackColorForTest(0));
        });
    }

    private static AutomotiveSignalCanvas show(List<GraphLogicalTrack> tracks) {
        AutomotiveSignalCanvas canvas = new AutomotiveSignalCanvas();
        Map<SignalCatalogKey, Integer> colors = new LinkedHashMap<>();
        for (int i = 0; i < tracks.size(); i++) {
            colors.put(tracks.get(i).logicalKey(), i == 0 && tracks.size() == 1
                    ? colorOf(tracks.get(0))
                    : i);
        }
        if (tracks.size() == 1) {
            colors.put(tracks.getFirst().logicalKey(), colorOf(tracks.getFirst()));
        }
        Map<SignalCatalogKey, String> labels = new LinkedHashMap<>();
        for (GraphLogicalTrack track : tracks) {
            labels.put(track.logicalKey(), track.logicalKey().signalName());
            for (GraphTrackSegment segment : track.segments()) {
                labels.put(segment.series().key(),
                        track.logicalKey().signalName() + " · " + segment.measurementLabel());
            }
        }
        canvas.setTracePresentation(labels, colors);
        canvas.setLogicalTracks(tracks);
        canvas.setSharedViewport(0L, 1_000_000_000L);
        canvas.setViewportFloorHeight(360);
        Stage stage = new Stage();
        stage.setScene(new Scene(canvas, 960, 520));
        stage.show();
        canvas.resize(960, 520);
        canvas.layout();
        return canvas;
    }

    private static int colorOf(GraphLogicalTrack track) {
        String name = track.logicalKey().signalName();
        if ("VehicleSpeed".equals(name)) {
            return track.segments().size() == 1 ? 3 : 0;
        }
        return 0;
    }

    private static GraphLogicalTrack track(String name, int colorIndex, int files, boolean absolute) {
        SignalCatalogKey logical = new SignalCatalogKey(
                name, SignalSourceType.DBC_CAN, "Vehicle", 0x100L, "PROJECT", "one.dbc");
        List<GraphTrackSegment> segments = new ArrayList<>();
        for (int i = 0; i < files; i++) {
            SignalCatalogKey physical = new SignalCatalogKey(
                    name, SignalSourceType.DBC_CAN, "Vehicle", 0x100L, "src-" + i, "one.dbc");
            GraphBucket bucket = new GraphBucket(0L, 10 + i, 0L, 10, 1L, 20 + i, 1L, 12, 2);
            GraphRenderSeries series = new GraphRenderSeries(
                    physical, "km/h", 2, false, 0L, 1_000_000_000L, List.of(bucket));
            segments.add(new GraphTrackSegment(series, "file-" + i + ".dat", absolute ? (long) i * 1_000_000_000L : null));
        }
        return new GraphLogicalTrack(logical, segments, absolute && files > 1);
    }
}
