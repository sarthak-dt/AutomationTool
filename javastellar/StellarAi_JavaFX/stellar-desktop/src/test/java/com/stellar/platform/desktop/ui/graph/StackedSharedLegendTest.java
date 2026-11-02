package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Overlay keeps the shared waveform legend. Stacked does not reserve that row. */
class StackedSharedLegendTest {

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @Test
    void overlayShowsSharedLegendAndStackedReleasesItWithoutReloadingSamples() throws Exception {
        JavaFxTestSupport.runOnFx("shared legend follows layout mode", () -> {
            for (int count : new int[] {1, 2, 4}) {
                assertLegendFor(count);
            }
        });
    }

    private static void assertLegendFor(int count) {
        Stage stage = new Stage();
        try {
            AutomotiveSignalCanvas canvas = new AutomotiveSignalCanvas();
            List<GraphRenderSeries> series = sampleSeries(count);
            Map<SignalCatalogKey, String> labels = new LinkedHashMap<>();
            Map<SignalCatalogKey, Integer> colors = new LinkedHashMap<>();
            for (int i = 0; i < series.size(); i++) {
                labels.put(series.get(i).key(), "Signal " + (char) ('A' + i));
                colors.put(series.get(i).key(), i + 2);
            }
            canvas.setTracePresentation(labels, colors);
            canvas.setSeries(series);
            canvas.setSharedViewport(1_000_000L, 4_000_000L);
            int assignments = canvas.seriesAssignmentCountForTest();
            List<Color> overlayColors = new ArrayList<>();
            List<String> overlayLabels = new ArrayList<>();

            stage.setScene(new Scene(canvas, 960, 520));
            stage.show();
            canvas.resize(960, 520);
            canvas.layout();

            canvas.setLayoutMode(AutomotiveSignalCanvas.LayoutMode.OVERLAY_MULTI_AXIS);
            canvas.flushDeferredPaintForTest();
            canvas.layout();
            assertTrue(canvas.sharedLegendVisibleForTest(), count + " overlay legend");
            assertTrue(canvas.sharedLegendManagedForTest(), count + " overlay managed");
            assertEquals(count, canvas.sharedLegendHitCountForTest(), count + " overlay names");
            for (int i = 0; i < count; i++) {
                overlayLabels.add(canvas.seriesLabelForTest(i));
                overlayColors.add(canvas.seriesColorForTest(i));
                assertEquals("Signal " + (char) ('A' + i), overlayLabels.get(i));
                assertEquals(GraphSeriesPalette.at(i + 2), overlayColors.get(i));
            }

            canvas.setLayoutMode(AutomotiveSignalCanvas.LayoutMode.STACKED);
            canvas.flushDeferredPaintForTest();
            canvas.layout();
            assertFalse(canvas.sharedLegendVisibleForTest(), count + " stacked legend");
            assertFalse(canvas.sharedLegendManagedForTest(), count + " stacked managed");
            assertEquals(0, canvas.sharedLegendHitCountForTest(), count + " stacked shared hits");
            assertTrue(canvas.firstTrackHeaderYForTest() <= 4,
                    count + " stacked track header left a gap at " + canvas.firstTrackHeaderYForTest());
            assertEquals(count, canvas.trackHeaderHitCountForTest(), count + " stacked tracks still named");
            assertEquals(assignments, canvas.seriesAssignmentCountForTest(), count + " stacked reload");

            canvas.setLayoutMode(AutomotiveSignalCanvas.LayoutMode.OVERLAY_MULTI_AXIS);
            canvas.flushDeferredPaintForTest();
            canvas.layout();
            assertTrue(canvas.sharedLegendVisibleForTest(), count + " restored");
            assertEquals(count, canvas.sharedLegendHitCountForTest(), count + " restored names");
            for (int i = 0; i < count; i++) {
                assertEquals(overlayLabels.get(i), canvas.seriesLabelForTest(i));
                assertEquals(overlayColors.get(i), canvas.seriesColorForTest(i));
            }

            canvas.setLayoutMode(AutomotiveSignalCanvas.LayoutMode.STACKED);
            canvas.setLayoutMode(AutomotiveSignalCanvas.LayoutMode.OVERLAY_MULTI_AXIS);
            canvas.setLayoutMode(AutomotiveSignalCanvas.LayoutMode.STACKED);
            canvas.flushDeferredPaintForTest();
            canvas.layout();
            assertEquals(0, canvas.sharedLegendHitCountForTest());
            assertEquals(assignments, canvas.seriesAssignmentCountForTest(), count + " repeated switch reloaded samples");
        } finally {
            stage.close();
        }
    }

    private static List<GraphRenderSeries> sampleSeries(int count) {
        List<GraphRenderSeries> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            SignalCatalogKey key = new SignalCatalogKey(
                    "Sig" + i, SignalSourceType.DBC_CAN, "Msg", i, 0, "run.mf4", "veh.dbc");
            List<GraphBucket> buckets = List.of(new GraphBucket(
                    1_000_000L, 1.0,
                    1_000_000L, 0.5,
                    2_000_000L, 2.0,
                    2_000_000L, 1.5,
                    10));
            out.add(new GraphRenderSeries(key, "rpm", 100, false, 1_000_000L, 4_000_000L, buckets));
        }
        return out;
    }
}
