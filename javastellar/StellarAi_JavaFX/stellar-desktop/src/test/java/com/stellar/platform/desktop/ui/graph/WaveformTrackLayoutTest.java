package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.AutomotiveSignalCanvas.StackedTrackFrame;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stacked waveform footer bands stay apart at the resolutions used on the engineering desktops. */
class WaveformTrackLayoutTest {

    private static final int[][] RESOLUTIONS = {
            {1280, 720},
            {1366, 768},
            {1536, 864},
            {1920, 1080}
    };

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @Test
    void bottomSummaryDoesNotIntersectTimeAxisAtDesktopWidths() throws Exception {
        JavaFxTestSupport.runOnFx("stacked footer bands", () -> {
            for (int[] size : RESOLUTIONS) {
                for (int tracks : new int[] {1, 3}) {
                    assertSeparated(size[0], size[1], tracks, false);
                }
            }
        });
    }

    @Test
    void manyTracksScrollTheLastAxisFullyIntoView() throws Exception {
        JavaFxTestSupport.runOnFx("stacked scroll", () -> {
            for (int[] size : RESOLUTIONS) {
                assertSeparated(size[0], size[1], 12, true);
            }
        });
    }

    private static void assertSeparated(int width, int height, int tracks, boolean expectScroll) {
        Stage stage = new Stage();
        try {
            AutomotiveSignalCanvas canvas = new AutomotiveSignalCanvas();
            canvas.setSeries(constantSeries(tracks));
            canvas.setSharedViewport(0L, 6_000_000_000_000L);
            canvas.setViewportFloorHeight(height);
            ScrollPane scroll = new ScrollPane(canvas);
            scroll.setFitToWidth(true);
            scroll.setFitToHeight(false);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            scroll.setPrefViewportWidth(width);
            scroll.setPrefViewportHeight(height);
            stage.setScene(new Scene(scroll, width, height));
            stage.show();
            scroll.applyCss();
            scroll.layout();
            canvas.applyCss();
            canvas.layout();

            double viewport = scroll.getViewportBounds().getHeight();
            if (expectScroll) {
                assertTrue(canvas.getHeight() > viewport + 8,
                        width + "x" + height + " " + tracks + " tracks did not grow past the viewport");
                scroll.setVvalue(1);
                scroll.layout();
            }

            List<StackedTrackFrame> frames = canvas.stackedFramesForTest();
            assertFalse(frames.isEmpty(), width + " produced no tracks");
            double covered = 0;
            for (int i = 0; i < frames.size(); i++) {
                StackedTrackFrame frame = frames.get(i);
                assertTrue(frame.summaryInsideTrack(), width + " summary outside track " + i + " " + frame);
                assertFalse(frame.summaryIntersectsTimeAxis(),
                        width + " summary intersects x-axis on track " + i + " " + frame);
                assertTrue(frame.top() + 0.5 >= covered, width + " tracks overlap at " + i);
                covered = frame.bottom();
                if (i == frames.size() - 1) {
                    assertTrue(frame.last());
                    assertTrue(frame.captionInsideTrack(), width + " time caption clipped " + frame);
                    assertTrue(frame.bottom() <= canvas.getHeight() + 0.5,
                            width + " last track extends past the canvas");
                    assertTrue(frame.timeLabelTop() + 0.01 >= frame.summaryBottom(),
                            width + " time labels share the summary row");
                }
            }
            assertTrue(covered <= canvas.getHeight() + 0.5,
                    width + " stacked tracks " + covered + " exceed canvas " + canvas.getHeight());
        } finally {
            stage.close();
        }
    }

    private static List<GraphRenderSeries> constantSeries(int count) {
        List<GraphRenderSeries> out = new ArrayList<>();
        List<GraphBucket> buckets = List.of(new GraphBucket(
                0L, 0.0,
                0L, 0.0,
                1_000_000_000L, 0.0,
                1_000_000_000L, 0.0,
                1));
        for (int i = 0; i < count; i++) {
            SignalCatalogKey key = new SignalCatalogKey(
                    "Sig" + i, SignalSourceType.DBC_CAN, "Msg", i, 0, "run.dat", "veh.dbc");
            out.add(new GraphRenderSeries(key, "", 620_330, false, 0L, 6_000_000_000_000L, buckets));
        }
        return out;
    }
}
