package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.map.MiniTrackCanvas;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphRecordingAlignment;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused-file analytics, engineering, and sparkline stay on one recording. */
class FileFocusCrossViewTest {

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @Test
    void engineeringDoesNotCorrelateUnrelatedRecordingsAndLeavesTheFxThread() throws Exception {
        EngineeringAnalysisPane[] holder = new EngineeringAnalysisPane[1];
        JavaFxTestSupport.runOnFx("schedule engineering", () -> {
            holder[0] = new EngineeringAnalysisPane();
            holder[0].setScopedSeries(
                List.of(series("Speed", "file-a", 1), series("Coolant", "file-b", 2)),
                Map.of(),
                "");
        });
        EngineeringAnalysisPane pane = holder[0];
        pane.flushEngineeringForTest();
        JavaFxTestSupport.runOnFx("read engineering", () -> {
            assertEquals(0, pane.relationshipCountForTest());
            assertTrue(pane.guidanceForTest().contains(GraphRecordingAlignment.CROSS_FILE_UNAVAILABLE), pane.guidanceForTest());
            assertTrue(pane.lastComputeLeftFxThreadForTest());
        });
    }

    @Test
    void engineeringComparesSignalsThatShareARecording() throws Exception {
        EngineeringAnalysisPane[] holder = new EngineeringAnalysisPane[1];
        JavaFxTestSupport.runOnFx("schedule same file", () -> {
            holder[0] = new EngineeringAnalysisPane();
            holder[0].setScopedSeries(
                List.of(series("Speed", "file-a", 1), series("Coolant", "file-a", 4)),
                Map.of(key("Speed", "file-a"), "Speed · file-a.dat"),
                "file-a.dat");
        });
        EngineeringAnalysisPane pane = holder[0];
        pane.flushEngineeringForTest();
        JavaFxTestSupport.runOnFx("read same file", () -> {
            assertEquals(1, pane.relationshipCountForTest());
            assertTrue(pane.headlineForTest().contains("file-a.dat"), pane.headlineForTest());
            assertTrue(pane.guidanceForTest().contains("Measurement: file-a.dat"), pane.guidanceForTest());
            assertFalse(pane.guidanceForTest().contains(GraphRecordingAlignment.CROSS_FILE_UNAVAILABLE));
        });
    }

    @Test
    void analyticsNamesTheFocusedMeasurementOnce() throws Exception {
        JavaFxTestSupport.runOnFx("analytics scope", () -> {
            EChartsAnalyticsPane pane = new EChartsAnalyticsPane();
            SignalCatalogKey speed = key("Speed", "file-12");
            pane.setScopedSeries(
                    List.of(series("Speed", "file-12", 1)),
                    Map.of(speed, "Speed · file-12.dat"),
                    "file-12.dat");
            String model = pane.modelForTest();
            assertTrue(model.contains("'file-12.dat'"), model);
            assertTrue(model.contains("Speed · file-12.dat"), model);
            assertFalse(model.contains("file-0"), model);
            int cats = model.indexOf("cats:[");
            int actual = model.indexOf("],actual:");
            assertTrue(cats >= 0 && actual > cats, model);
            assertFalse(model.substring(cats, actual).contains("','"), model);
        });
    }

    @Test
    void sparklineUsesLogicalColourAndTheFocusedSampleCount() throws Exception {
        JavaFxTestSupport.runOnFx("sparkline", () -> {
            MiniTrackCanvas canvas = new MiniTrackCanvas();
            GraphRenderSeries focused = series("Speed", "file-12", 40);
            canvas.setSeries(List.of(focused), List.of(3));
            assertEquals(1, canvas.trackCount());
            assertEquals(3, canvas.colorIndexForTest(0));
            assertEquals(40, canvas.sampleCountForTest(0));
        });
    }

    private static GraphRenderSeries series(String name, String file, long samples) {
        SignalCatalogKey catalogKey = key(name, file);
        List<GraphBucket> buckets = List.of(
                bucket(0, 1),
                bucket(100_000_000L, 2),
                bucket(200_000_000L, 3),
                bucket(300_000_000L, 4));
        return new GraphRenderSeries(catalogKey, "km/h", samples, false, 0, 300_000_000L, buckets);
    }

    private static GraphBucket bucket(long timestamp, double value) {
        return new GraphBucket(timestamp, value, timestamp, value, timestamp, value, timestamp, value, 1);
    }

    private static SignalCatalogKey key(String name, String file) {
        return new SignalCatalogKey(name, SignalSourceType.DBC_CAN, "Vehicle", 0x100L, file, "one.dbc");
    }
}
