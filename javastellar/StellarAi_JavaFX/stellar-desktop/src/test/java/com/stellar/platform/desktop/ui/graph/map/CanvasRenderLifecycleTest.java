package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.canvas.CanvasRenderSupport;
import com.stellar.platform.desktop.ui.canvas.CanvasRenderSupport.Size;
import com.stellar.platform.desktop.ui.graph.AutomotiveSignalCanvas;
import com.stellar.platform.desktop.ui.graph.map.RouteCanvas;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.processing.map.MapRoute;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.RouteSegment;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Canvas lifecycle and texture budget. These run on the JavaFX toolkit (software pipeline in Surefire)
 * because a unit assertion cannot see a render-thread texture failure by itself.
 */
class CanvasRenderLifecycleTest {

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @Test
    void oversizedCssIsClampedToTheBackingEdgeAtTheDisplayScale() {
        Size fitted = CanvasRenderSupport.fit(20_000, 20_000, 2, 2, 8192);
        assertTrue(fitted.paintable());
        assertTrue(fitted.backingWidth() <= 8192, "backing " + fitted.backingWidth());
        assertTrue(fitted.backingHeight() <= 8192, "backing " + fitted.backingHeight());
        assertTrue(fitted.cssWidth() <= 4096);
        assertTrue(fitted.cssHeight() <= 4096);
        assertEquals(2, fitted.scaleX(), 0);
        assertEquals(2, fitted.scaleY(), 0);
    }

    @Test
    void nonFiniteAndSubPixelSizesAreNotPaintable() {
        assertFalse(CanvasRenderSupport.fit(Double.NaN, 400, 1, 1, 8192).paintable());
        assertFalse(CanvasRenderSupport.fit(400, Double.POSITIVE_INFINITY, 1.5, 1.5, 8192).paintable());
        assertFalse(CanvasRenderSupport.fit(0, 400, 1, 1, 8192).paintable());
        assertFalse(CanvasRenderSupport.fit(-20, 400, 2, 2, 8192).paintable());
    }

    @Test
    void fractionalScaleCannotRoundTheTexturePastTheEdge() {
        Size fitted = CanvasRenderSupport.fit(10_000, 800, 2.5, 1.25, 4096);
        assertTrue(fitted.paintable());
        assertTrue(fitted.backingWidth() <= 4096);
        assertTrue(fitted.backingHeight() <= 4096);
        assertTrue(fitted.cssWidth() < 10_000);
    }

    @Test
    void shownCanvasRejectsALayoutLargerThanThePipelineBudget() throws Exception {
        AtomicReference<double[]> css = new AtomicReference<>();
        AtomicInteger edge = new AtomicInteger();
        AtomicReference<Double> scale = new AtomicReference<>();
        JavaFxTestSupport.runOnFx("oversized route canvas", () -> {
            RouteCanvas view = new RouteCanvas();
            Stage stage = new Stage();
            try {
                StackPane root = new StackPane(view);
                stage.setScene(new Scene(root, 800, 600));
                stage.show();
                view.resize(30_000, 30_000);
                view.layout();
                css.set(view.canvasCssSizeForTest());
                edge.set(CanvasRenderSupport.maxBackingEdge());
                scale.set(stage.getOutputScaleX());
                view.paintForTest();
            } finally {
                stage.close();
            }
        });
        double[] size = css.get();
        double output = scale.get();
        assertTrue(Double.isFinite(size[0]) && Double.isFinite(size[1]));
        assertTrue(Math.ceil(size[0] * output) <= edge.get() + 1, size[0] + " * " + output);
        assertTrue(Math.ceil(size[1] * output) <= edge.get() + 1, size[1] + " * " + output);
        assertTrue(size[0] < 30_000 && size[1] < 30_000, "css stayed " + size[0] + "x" + size[1]);
    }

    @Test
    void disposeDropsLateRedrawsAndDoesNotRequireAClear() throws Exception {
        AtomicInteger afterDispose = new AtomicInteger(-1);
        AtomicInteger afterLate = new AtomicInteger(-1);
        JavaFxTestSupport.runOnFx("dispose late redraw", () -> {
            RouteCanvas view = new RouteCanvas();
            Stage stage = new Stage();
            try {
                StackPane root = new StackPane(view);
                stage.setScene(new Scene(root, 640, 360));
                stage.show();
                root.applyCss();
                root.layout();
                view.setSegments(List.of(segment("a", "A", 18.9, 73.8, 18.8, 73.7)));
                view.layout();
                int painted = view.paintCountForTest();
                assertTrue(painted > 0);
                view.dispose();
                view.setSegments(List.of(segment("b", "B", 19, 74, 19.1, 74.1)));
                view.paintForTest();
                afterDispose.set(view.paintCountForTest());
                assertEquals(painted, afterDispose.get());
            } finally {
                stage.close();
            }
        });
        pump();
        JavaFxTestSupport.runOnFx("late pulse", () -> afterLate.set(1));
        assertEquals(1, afterLate.get());
    }

    @Test
    void detachedCanvasDoesNotPaint() throws Exception {
        AtomicInteger paints = new AtomicInteger();
        AtomicReference<Boolean> attached = new AtomicReference<>(true);
        JavaFxTestSupport.runOnFx("detach", () -> {
            RouteCanvas view = new RouteCanvas();
            Stage stage = new Stage();
            try {
                StackPane root = new StackPane(view);
                stage.setScene(new Scene(root, 640, 360));
                stage.show();
                root.applyCss();
                root.layout();
                view.setSegments(List.of(segment("a", "A", 18.9, 73.8, 18.8, 73.7)));
                view.layout();
                int before = view.paintCountForTest();
                root.getChildren().clear();
                view.setSegments(segments(10));
                view.paintForTest();
                paints.set(view.paintCountForTest());
                attached.set(view.getScene() != null);
                assertEquals(before, paints.get());
                assertFalse(attached.get());
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void repeatedLayoutDoesNotRepaintAnUnchangedCanvas() throws Exception {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger later = new AtomicInteger();
        JavaFxTestSupport.runOnFx("repeated layout", () -> {
            RouteCanvas view = new RouteCanvas();
            Stage stage = new Stage();
            try {
                StackPane root = new StackPane(view);
                stage.setScene(new Scene(root, 800, 500));
                stage.show();
                view.setSegments(segments(4));
                root.applyCss();
                root.layout();
                first.set(view.paintCountForTest());
                for (int i = 0; i < 30; i++) {
                    view.layout();
                }
                later.set(view.paintCountForTest());
            } finally {
                stage.close();
            }
        });
        assertTrue(first.get() > 0);
        assertEquals(first.get(), later.get(), "layout passes repainted");
    }

    @Test
    void manySegmentsDoNotGrowTheCanvasWithTheSampleCount() throws Exception {
        AtomicReference<double[]> small = new AtomicReference<>();
        AtomicReference<double[]> many = new AtomicReference<>();
        JavaFxTestSupport.runOnFx("73 segments", () -> {
            RouteCanvas view = new RouteCanvas();
            Stage stage = new Stage();
            try {
                StackPane root = new StackPane(view);
                stage.setScene(new Scene(root, 900, 600));
                stage.show();
                view.setSegments(segments(1));
                root.applyCss();
                root.layout();
                small.set(view.canvasCssSizeForTest());
                view.setSegments(segments(73));
                view.layout();
                view.paintForTest();
                many.set(view.canvasCssSizeForTest());
                assertEquals(73, view.overlayCountForTest());
                assertEquals(2, view.endpointMarksForTest().size());
                assertEquals(RouteCanvas.EndpointPolicy.GLOBAL, view.endpointPolicyForTest());
            } finally {
                stage.close();
            }
        });
        assertEquals(small.get()[0], many.get()[0], 1);
        assertEquals(small.get()[1], many.get()[1], 1);
        assertTrue(small.get()[0] < 2_000 && small.get()[1] < 2_000);
    }

    @Test
    void stackedWaveformTextureStaysOnTheViewportWhileTheRegionKeepsEveryTrack() throws Exception {
        AtomicReference<double[]> inner = new AtomicReference<>();
        AtomicReference<Double> logical = new AtomicReference<>();
        AtomicReference<Double> viewport = new AtomicReference<>();
        AtomicReference<Double> scrolled = new AtomicReference<>();
        AtomicInteger edge = new AtomicInteger();
        AtomicReference<Long> timeBefore = new AtomicReference<>();
        AtomicReference<Long> timeAfter = new AtomicReference<>();
        JavaFxTestSupport.runOnFx("stacked viewport tile", () -> {
            AutomotiveSignalCanvas graph = new AutomotiveSignalCanvas();
            graph.setSeries(tracks(73));
            graph.setSharedViewport(0L, 10_000_000_000L);
            graph.setViewportFloorHeight(640);
            ScrollPane scroll = new ScrollPane(graph);
            scroll.setFitToWidth(true);
            scroll.setFitToHeight(false);
            scroll.setPrefViewportWidth(1100);
            scroll.setPrefViewportHeight(640);
            Stage stage = new Stage();
            try {
                stage.setScene(new Scene(scroll, 1100, 640));
                stage.show();
                scroll.applyCss();
                scroll.layout();
                graph.layout();
                double viewH = scroll.getViewportBounds().getHeight();
                graph.setVisibleWindow(viewH, 0);
                graph.layout();
                timeBefore.set(graph.timestampAtForTest(180));
                graph.setVisibleWindow(viewH, 40);
                graph.layout();
                timeAfter.set(graph.timestampAtForTest(180));
                inner.set(graph.innerCanvasSizeForTest());
                logical.set(graph.getHeight());
                viewport.set(viewH);
                edge.set(CanvasRenderSupport.maxBackingEdge());
                scroll.setVvalue(1);
                scroll.layout();
                double content = graph.getHeight();
                double offset = scroll.getVvalue() * Math.max(0, content - viewH);
                graph.setVisibleWindow(viewH, offset);
                graph.layout();
                scrolled.set(graph.innerCanvasSizeForTest()[2]);
            } finally {
                stage.close();
            }
        });
        double[] tile = inner.get();
        assertTrue(logical.get() > tile[1] + 40, "logical " + logical.get() + " tile " + tile[1]);
        assertTrue(tile[1] <= viewport.get() + 2, "tile taller than the viewport");
        assertTrue(Math.ceil(tile[0]) <= edge.get() + 1);
        assertTrue(Math.ceil(tile[1]) <= edge.get() + 1);
        assertEquals(timeBefore.get(), timeAfter.get(), "moving the tile must not retarget the time axis");
        assertTrue(scrolled.get() > 20, "scrolled tile origin " + scrolled.get());
    }

    @Test
    void openAndCloseCyclesDoNotPaintAfterDispose() throws Exception {
        AtomicInteger leaks = new AtomicInteger();
        JavaFxTestSupport.runOnFx("open close", () -> {
            for (int i = 0; i < 12; i++) {
                RouteCanvas view = new RouteCanvas();
                Stage stage = new Stage();
                try {
                    StackPane root = new StackPane(view);
                    stage.setScene(new Scene(root, 480, 320));
                    stage.show();
                    view.setSegments(segments(3));
                    root.applyCss();
                    root.layout();
                    int painted = view.paintCountForTest();
                    assertTrue(painted > 0);
                    view.dispose();
                    view.paintForTest();
                    if (view.paintCountForTest() != painted) {
                        leaks.incrementAndGet();
                    }
                } finally {
                    stage.close();
                }
            }
        });
        assertEquals(0, leaks.get());
    }

    private static void pump() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(done::countDown);
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    private static List<RouteSegment> segments(int count) {
        List<RouteSegment> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(segment("m" + i, "M" + i, 18.5 + i * 0.01, 73.5, 18.6 + i * 0.01, 73.6));
        }
        return out;
    }

    private static RouteSegment segment(String id, String name, double startLat, double startLon,
                                        double endLat, double endLon) {
        List<SignalSamplePoint> latitude = new ArrayList<>();
        List<SignalSamplePoint> longitude = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long timestamp = i * 1_000_000_000L;
            double t = i / 3.0;
            latitude.add(new SignalSamplePoint(timestamp, startLat + (endLat - startLat) * t, null, null));
            longitude.add(new SignalSamplePoint(timestamp, startLon + (endLon - startLon) * t, null, null));
        }
        return new RouteSegment(id, name, new MapRoute(MapRouteBuilder.build(latitude, longitude, null)));
    }

    private static List<GraphRenderSeries> tracks(int count) {
        List<GraphRenderSeries> out = new ArrayList<>();
        List<GraphBucket> buckets = List.of(new GraphBucket(
                1_000_000L, 10,
                1_000_000L, 8,
                2_000_000L, 14,
                2_000_000L, 12,
                4));
        for (int i = 0; i < count; i++) {
            SignalCatalogKey key = new SignalCatalogKey(
                    "Sig" + i, SignalSourceType.DBC_CAN, "Msg", i, 0, "run.mf4", "veh.dbc");
            out.add(new GraphRenderSeries(key, "km/h", 100, false, 0L, 10_000_000_000L, buckets));
        }
        return out;
    }
}
