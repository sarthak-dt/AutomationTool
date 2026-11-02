package com.stellar.platform.desktop.ui.graph.map;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JavaFX WebView smoke: local Leaflet initializes without CDN and invalidateSize is callable.
 */
class LeafletBasemapSmokeTest {

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
    @Timeout(45)
    void leafletReadyWithoutCdnAndResizeWorks() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<LeafletBasemapView> viewRef = new AtomicReference<>();
        AtomicBoolean healthy = new AtomicBoolean(false);
        AtomicBoolean resizeOk = new AtomicBoolean(false);

        Platform.runLater(() -> {
            LeafletBasemapView view = new LeafletBasemapView();
            viewRef.set(view);
            view.setReadyHandler(() -> {
                try {
                    javafx.scene.web.WebView web =
                            (javafx.scene.web.WebView) view.getChildren().get(0);
                    Object ok = web.getEngine().executeScript(
                            "(document.documentElement.outerHTML.indexOf('unpkg.com') < 0)"
                                    + " && (typeof L !== 'undefined')"
                                    + " && (typeof window.stellarResizeMap === 'function')"
                                    + " && (typeof window.pmtiles !== 'undefined')");
                    healthy.set(Boolean.TRUE.equals(ok));
                    Object resized = web.getEngine().executeScript("window.stellarResizeMap(); true;");
                    resizeOk.set(Boolean.TRUE.equals(resized));
                } catch (RuntimeException ex) {
                    healthy.set(false);
                } finally {
                    ready.countDown();
                }
            });
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 640, 480));
            stage.show();
        });

        assertTrue(ready.await(30, TimeUnit.SECONDS), "Leaflet did not become ready");
        assertTrue(healthy.get(), "Local Leaflet + pmtiles must initialize without CDN");
        assertTrue(resizeOk.get(), "stellarResizeMap must succeed");

        CountDownLatch sized = new CountDownLatch(1);
        AtomicBoolean sizedOk = new AtomicBoolean(false);
        AtomicBoolean singleMap = new AtomicBoolean(false);
        AtomicBoolean singleTile = new AtomicBoolean(false);
        Platform.runLater(() -> {
            LeafletBasemapView view = viewRef.get();
            view.applyOnline(MapTileProvider.openStreetMap());
            view.applyOnline(MapTileProvider.openStreetMap());
            view.invalidateSize("smoke-size");
            Object diag = view.webViewForTest().getEngine().executeScript("window.stellarMapDiagnostics();");
            String text = String.valueOf(diag);
            String[] parts = text.split("\\|");
            singleMap.set("1".equals(parts[0]));
            singleTile.set("1".equals(parts[1]));
            int mapW = Integer.parseInt(parts[2]);
            int mapH = Integer.parseInt(parts[3]);
            sizedOk.set(mapW >= 200 && mapH >= 200
                    && Math.abs(mapW - view.webViewForTest().getWidth()) < 8
                    && Math.abs(mapH - view.webViewForTest().getHeight()) < 8);
            sized.countDown();
        });
        assertTrue(sized.await(10, TimeUnit.SECONDS));
        assertTrue(singleMap.get(), "Leaflet map must be created once");
        assertTrue(singleTile.get(), "online tile layer must exist once after repeated applyOnline");
        assertTrue(sizedOk.get(), "map DOM size must match WebView after invalidateSize");

        CountDownLatch closed = new CountDownLatch(1);
        Platform.runLater(() -> {
            LeafletBasemapView view = viewRef.get();
            if (view != null) {
                view.dispose();
            }
            closed.countDown();
        });
        assertTrue(closed.await(5, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(15)
    void disposedViewDoesNotExecuteJavascript() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean threw = new AtomicBoolean(false);
        Platform.runLater(() -> {
            LeafletBasemapView view = new LeafletBasemapView();
            view.dispose();
            try {
                view.runScriptForTest("throw 'must-not-run'");
            } catch (RuntimeException ex) {
                threw.set(true);
            }
            assertFalse(view.htmlReadyForTest());
            assertFalse(view.isReady());
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertFalse(threw.get(), "page-not-ready / disposed must skip JS");
    }
}
