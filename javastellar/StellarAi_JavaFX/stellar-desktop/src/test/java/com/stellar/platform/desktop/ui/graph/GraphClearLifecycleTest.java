package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import javafx.application.Platform;
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
 * Structural Clear lifecycle: graph working-state empties; bookmarks survive;
 * WebView is not forced to materialize on empty Clear.
 */
class GraphClearLifecycleTest {

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    void canvasClearDropsSeriesAndCursors() throws Exception {
        AtomicInteger count = new AtomicInteger(-1);
        runOnFx(() -> {
            AutomotiveSignalCanvas canvas = new AutomotiveSignalCanvas();
            canvas.setSeries(sampleSeries(3));
            canvas.setCursors(10L, 20L);
            canvas.clearGraphData();
            count.set(canvas.seriesCountForTest());
        });
        assertEquals(0, count.get());
    }

    @Test
    void engineeringClearDropsSeriesKeepsBookmarks() throws Exception {
        AtomicInteger series = new AtomicInteger(-1);
        AtomicInteger bookmarks = new AtomicInteger(-1);
        runOnFx(() -> {
            EngineeringAnalysisPane pane = new EngineeringAnalysisPane();
            pane.setSeries(sampleSeries(2));
            pane.replaceBookmarks(List.of(
                    new EngineeringAnalysisPane.BookmarkRow("0.001 s", "S1", "STEP", "note", 1_000_000L)));
            pane.clearViewportState();
            series.set(pane.currentSeriesCountForTest());
            bookmarks.set(pane.bookmarkCountForTest());
        });
        assertEquals(0, series.get());
        assertEquals(1, bookmarks.get(), "persisted bookmarks must survive Clear");
    }

    @Test
    void echartsClearDoesNotMaterializeWebView() throws Exception {
        AtomicReference<Boolean> materialized = new AtomicReference<>(true);
        runOnFx(() -> {
            EChartsAnalyticsPane pane = new EChartsAnalyticsPane();
            pane.setSeries(sampleSeries(1));
            pane.clearChartData();
            materialized.set(pane.webViewMaterializedForTest());
        });
        assertFalse(materialized.get(), "Clear must not create WebKit");
    }

    @Test
    void twentyClearCyclesLeaveEmptyModels() throws Exception {
        AtomicInteger finalSeries = new AtomicInteger(-1);
        AtomicInteger finalBookmarks = new AtomicInteger(-1);
        AtomicReference<Boolean> webkit = new AtomicReference<>(true);
        runOnFx(() -> {
            AutomotiveSignalCanvas canvas = new AutomotiveSignalCanvas();
            EngineeringAnalysisPane engineering = new EngineeringAnalysisPane();
            EChartsAnalyticsPane analytics = new EChartsAnalyticsPane();
            engineering.replaceBookmarks(List.of(
                    new EngineeringAnalysisPane.BookmarkRow("t", "S", "E", "o", 1L)));

            for (int i = 0; i < 20; i++) {
                List<GraphRenderSeries> series = sampleSeries(8);
                canvas.setSeries(series);
                engineering.setSeries(series);
                analytics.setSeries(series);

                canvas.clearGraphData();
                engineering.clearViewportState();
                analytics.clearChartData();
            }
            finalSeries.set(canvas.seriesCountForTest());
            finalBookmarks.set(engineering.bookmarkCountForTest());
            webkit.set(analytics.webViewMaterializedForTest());
        });
        assertEquals(0, finalSeries.get());
        assertEquals(1, finalBookmarks.get());
        assertFalse(webkit.get());
    }

    private static void runOnFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
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
            out.add(new GraphRenderSeries(
                    key, "u", 100, false, 1_000_000L, 2_000_000L * Math.max(1, count), buckets));
        }
        return out;
    }
}
