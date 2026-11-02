package com.stellar.platform.desktop.ui.graph.map;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Readiness-sequence behaviour of the live WebView basemap.
 *
 * <p>These cover the failure that made the map load "sometimes": Leaflet being created — or
 * {@code fitBounds} being applied — while the container still had no usable layout. Tile outcomes
 * are injected through the same bridge entry point the JavaScript uses, so nothing here needs a
 * network or a tile server.
 */
class LeafletBasemapReadinessTest {

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        // Each scenario closes its own stage. With implicit exit the first close would shut the
        // toolkit down for the rest of the JVM, and every later scenario would hang on runLater.
        Platform.runLater(() -> Platform.setImplicitExit(false));
    }

    @Test
    @Timeout(60)
    void zeroSizedStartupDefersLeafletUntilTheContainerIsLaidOut() throws Exception {
        AtomicReference<LeafletBasemapView> ref = new AtomicReference<>();
        AtomicReference<StackPane> hostRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);

        onFx(() -> {
            LeafletBasemapView view = new LeafletBasemapView();
            view.setReadyHandler(ready::countDown);
            ref.set(view);
            // A collapsed host is what an inactive tab or a fully collapsed inspector looks like.
            StackPane host = new StackPane(view);
            host.setMinSize(0, 0);
            host.setPrefSize(0, 0);
            host.setMaxSize(0, 0);
            hostRef.set(host);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(host), 400, 300));
            stage.show();
            stageRef.set(stage);
        });

        // Give the document time to load while the container stays collapsed.
        awaitFx(() -> ref.get().htmlReadyForTest(), 30);
        pumpFx(6);
        assertFalse(ready.await(1, TimeUnit.SECONDS),
                "Leaflet must not initialize against a zero-sized container");
        assertEquals(0, valueOf(() -> ref.get().leafletInitCountForTest()));
        assertFalse(valueOf(() -> ref.get().domSizeValidForTest()));

        onFx(() -> {
            StackPane host = hostRef.get();
            host.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            host.setPrefSize(360, 260);
        });
        assertTrue(ready.await(30, TimeUnit.SECONDS),
                "Leaflet must initialize once the container is finally laid out");
        assertEquals(1, valueOf(() -> ref.get().leafletInitCountForTest()));
        assertTrue(valueOf(() -> ref.get().domSizeValidForTest()));

        onFx(() -> {
            ref.get().dispose();
            stageRef.get().close();
        });
    }

    @Test
    @Timeout(60)
    void resizeAndRepeatedActivationKeepOneLeafletAndResyncTheViewport() throws Exception {
        AtomicReference<LeafletBasemapView> ref = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);

        onFx(() -> {
            LeafletBasemapView view = new LeafletBasemapView();
            view.setReadyHandler(ready::countDown);
            ref.set(view);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 500, 380));
            stage.show();
            stageRef.set(stage);
        });
        assertTrue(ready.await(40, TimeUnit.SECONDS));

        MapViewport viewport = new MapViewport();
        viewport.resize(500, 380);
        onFx(() -> ref.get().syncViewport(viewport));
        String fitted = valueOf(() -> ref.get().pendingViewForTest());
        assertFalse(fitted.isBlank(), "a sized viewport must produce Leaflet bounds");

        // Zooming the route canvas must move Leaflet with it, not leave the two diverging.
        onFx(() -> {
            viewport.zoomCentered(0.25);
            ref.get().syncViewport(viewport);
        });
        assertFalse(fitted.equals(valueOf(() -> ref.get().pendingViewForTest())),
                "a canvas zoom must be pushed into Leaflet");

        // An unsized viewport must be refused rather than fitted to a degenerate extent.
        String lastGood = valueOf(() -> ref.get().pendingViewForTest());
        MapViewport collapsed = new MapViewport();
        collapsed.resize(0, 0);
        onFx(() -> ref.get().syncViewport(collapsed));
        assertEquals(lastGood, valueOf(() -> ref.get().pendingViewForTest()));

        int before = valueOf(() -> ref.get().invalidateRequestsForTest());
        onFx(() -> stageRef.get().setWidth(760));
        // Repeated tab activation on top of a live resize must still converge on one DOM size.
        onFx(() -> ref.get().scheduleInvalidateSize("tab-activate"));
        onFx(() -> ref.get().scheduleInvalidateSize("tab-activate"));
        awaitFx(() -> {
            MapBasemapDiagnostics d = ref.get().diagnostics();
            return d.mapDomWidth() > 500 && Math.abs(d.mapDomWidth() - d.webViewWidth()) < 8;
        }, 20);

        assertTrue(valueOf(() -> ref.get().invalidateRequestsForTest()) > before);
        assertEquals(1, valueOf(() -> ref.get().leafletInitCountForTest()),
                "resize and repeated activation must reuse the same Leaflet instance");
        MapBasemapDiagnostics diagnostics = valueOf(() -> ref.get().diagnostics());
        assertTrue(diagnostics.leafletReady(), diagnostics.describe());
        assertTrue(diagnostics.domSizeValid(), diagnostics.describe());

        onFx(() -> {
            ref.get().dispose();
            stageRef.get().close();
        });
    }

    @Test
    @Timeout(60)
    void tileEvidenceDrivesLoadedStateAndOnlineFailure() throws Exception {
        AtomicReference<LeafletBasemapView> ref = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        List<MapBasemapMode> failures = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(1);

        onFx(() -> {
            LeafletBasemapView view = new LeafletBasemapView();
            view.setReadyHandler(ready::countDown);
            view.setModeFailedHandler(failures::add);
            ref.set(view);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 480, 360));
            stage.show();
            stageRef.set(stage);
        });
        assertTrue(ready.await(40, TimeUnit.SECONDS));

        onFx(() -> {
            LeafletBasemapView view = ref.get();
            view.applyOnline(unreachableTiles());
            view.applyOnline(unreachableTiles());
        });
        assertEquals(2, valueOf(() -> ref.get().onlineProviderAppliesForTest()));

        int generation = valueOf(() -> ref.get().providerGenerationForTest());
        // A tile layer existing is not evidence that anything rendered.
        onFx(() -> ref.get().tileEventForTest("providerApplied", generation, 0));
        assertFalse(valueOf(() -> ref.get().tilesLoaded()),
                "providerApplied is not a rendered basemap");
        assertEquals(BasemapTileHealth.TILES_LOADING, valueOf(() -> ref.get().tileHealth()));

        onFx(() -> ref.get().tileEventForTest("firstTile", generation, 0));
        awaitFx(() -> ref.get().tileHealth() == BasemapTileHealth.TILES_PARTIALLY_LOADED, 10);
        assertFalse(valueOf(() -> ref.get().tilesLoaded()), "a first tile is not a healthy basemap");

        onFx(() -> ref.get().tileEventForTest("layerLoaded", generation, 0));
        awaitFx(() -> ref.get().tilesLoaded(), 10);
        assertEquals(BasemapTileHealth.TILES_HEALTHY, valueOf(() -> ref.get().tileHealth()));

        // Events from a superseded provider must not corrupt the current state.
        onFx(() -> ref.get().tileEventForTest("basemapCleared", generation - 1, 0));
        pumpFx(3);
        assertTrue(valueOf(() -> ref.get().tilesLoaded()));

        onFx(() -> ref.get().tileEventForTest("tileError", generation, 4));
        awaitFx(() -> ref.get().onlineTilesFailed(), 10);
        assertTrue(failures.contains(MapBasemapMode.ONLINE));
        assertEquals(4, valueOf(() -> ref.get().diagnostics().tileErrorCount()));
        assertEquals(BasemapTileHealth.TILE_PROVIDER_FAILED, valueOf(() -> ref.get().tileHealth()));

        onFx(() -> {
            ref.get().dispose();
            stageRef.get().close();
        });
    }

    @Test
    @Timeout(60)
    void partialTileFailureIsNotHealthyAndAStalledViewportFailsTheProvider() throws Exception {
        AtomicReference<LeafletBasemapView> ref = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        List<MapBasemapMode> failures = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(1);

        onFx(() -> {
            LeafletBasemapView view = new LeafletBasemapView();
            view.setReadyHandler(ready::countDown);
            view.setModeFailedHandler(failures::add);
            ref.set(view);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 480, 360));
            stage.show();
            stageRef.set(stage);
        });
        assertTrue(ready.await(40, TimeUnit.SECONDS));

        onFx(() -> ref.get().applyOnline(unreachableTiles()));
        int generation = valueOf(() -> ref.get().providerGenerationForTest());
        onFx(() -> ref.get().tileEventForTest("providerApplied", generation, 0));
        onFx(() -> ref.get().tileEventForTest("firstTile", generation, 0));
        awaitFx(() -> ref.get().tileHealth() == BasemapTileHealth.TILES_PARTIALLY_LOADED, 10);
        assertFalse(valueOf(() -> ref.get().tilesLoaded()));

        onFx(() -> ref.get().tileEventForTest("layerLoaded", generation, 2));
        pumpFx(3);
        assertFalse(valueOf(() -> ref.get().tilesLoaded()), "a mixed tile set is not a healthy basemap");
        assertEquals(BasemapTileHealth.TILES_PARTIALLY_LOADED, valueOf(() -> ref.get().tileHealth()));
        assertTrue(valueOf(() -> ref.get().tileLoadCompleted()));

        onFx(() -> {
            ref.get().dispose();
            stageRef.get().close();
        });

        AtomicReference<LeafletBasemapView> stalled = new AtomicReference<>();
        AtomicReference<Stage> stalledStage = new AtomicReference<>();
        List<MapBasemapMode> stalledFailures = new ArrayList<>();
        CountDownLatch stalledReady = new CountDownLatch(1);
        onFx(() -> {
            LeafletBasemapView view = new LeafletBasemapView();
            view.setReadyHandler(stalledReady::countDown);
            view.setModeFailedHandler(stalledFailures::add);
            stalled.set(view);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 480, 360));
            stage.show();
            stalledStage.set(stage);
        });
        assertTrue(stalledReady.await(40, TimeUnit.SECONDS));
        onFx(() -> stalled.get().applyOnline(unreachableTiles()));
        int stalledGen = valueOf(() -> stalled.get().providerGenerationForTest());
        onFx(() -> stalled.get().tileEventForTest("providerApplied", stalledGen, 0));
        onFx(() -> stalled.get().tileEventForTest("firstTile", stalledGen, 0));
        onFx(() -> stalled.get().tileEventForTest("tileStalled", stalledGen, 0));
        awaitFx(() -> stalled.get().onlineTilesFailed(), 10);
        assertTrue(stalledFailures.contains(MapBasemapMode.ONLINE));
        assertEquals(BasemapTileHealth.TILE_PROVIDER_FAILED, valueOf(() -> stalled.get().tileHealth()));
        onFx(() -> {
            stalled.get().dispose();
            stalledStage.get().close();
        });
    }

    @Test
    @Timeout(60)
    void leafletThatNeverBecomesReadyFallsBackToNeutralWithTheRouteStillUsable() throws Exception {
        AtomicReference<LeafletBasemapView> ref = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        List<MapBasemapMode> failures = new ArrayList<>();

        onFx(() -> {
            LeafletBasemapView view = new LeafletBasemapView();
            view.setModeFailedHandler(failures::add);
            ref.set(view);
            // Keep the container collapsed so Leaflet cannot become ready before the injected
            // watchdog failure. adoptJsReadiness would otherwise mark the map ready on first layout.
            StackPane host = new StackPane(view);
            host.setMinSize(0, 0);
            host.setPrefSize(0, 0);
            host.setMaxSize(0, 0);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(host), 400, 300));
            stage.show();
            stageRef.set(stage);
        });

        // Stands in for Leaflet never initializing: a broken script, a blocked resource, a WebView
        // that never runs the document. The watchdog is the only thing that can notice.
        onFx(() -> ref.get().failReadinessForTest());

        assertTrue(valueOf(() -> ref.get().readinessFailedForTest()));
        assertEquals(MapBasemapMode.NEUTRAL, valueOf(() -> ref.get().mode()),
                "an unusable Leaflet must leave the map in NEUTRAL, not waiting forever");
        assertTrue(failures.contains(MapBasemapMode.NEUTRAL),
                "the parent must be told so it can show route-only status");
        assertFalse(valueOf(() -> ref.get().tilesLoaded()));

        // The overlay must still be drivable: the canvas keeps owning the viewport in NEUTRAL.
        MapViewport viewport = new MapViewport();
        viewport.resize(480, 360);
        onFx(() -> ref.get().syncViewport(viewport));
        assertFalse(valueOf(() -> ref.get().pendingViewForTest()).isBlank(),
                "the route viewport must still be accepted after readiness failure");

        onFx(() -> {
            ref.get().dispose();
            stageRef.get().close();
        });
    }

    @Test
    @Timeout(60)
    void aModeFailureHandlerRegisteredAfterAnAssetFailureIsStillNotified() throws Exception {
        AtomicReference<LeafletBasemapView> ref = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        List<MapBasemapMode> failures = new ArrayList<>();

        onFx(() -> {
            LeafletBasemapView view = new LeafletBasemapView();
            ref.set(view);
            StackPane host = new StackPane(view);
            host.setMinSize(0, 0);
            host.setPrefSize(0, 0);
            host.setMaxSize(0, 0);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(host), 400, 300));
            stage.show();
            stageRef.set(stage);
        });
        onFx(() -> ref.get().failReadinessForTest());

        // Missing web assets are discovered during construction, before the parent can register.
        onFx(() -> ref.get().setModeFailedHandler(failures::add));

        assertEquals(List.of(MapBasemapMode.NEUTRAL), failures,
                "a failure that happened before registration must not be lost");

        onFx(() -> {
            ref.get().dispose();
            stageRef.get().close();
        });
    }

    // ------------------------------------------------------------------

    private static void onFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                failure.set(ex);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS), "FX action timed out");
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    private static <T> T valueOf(java.util.function.Supplier<T> supplier) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        onFx(() -> out.set(supplier.get()));
        return out.get();
    }

    /** Lets a bounded number of JavaFX pulses run so deferred work can settle. */
    private static void pumpFx(int pulses) throws Exception {
        for (int i = 0; i < pulses; i++) {
            onFx(() -> { });
        }
    }

    private static void awaitFx(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (valueOf(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("condition not met within " + seconds + "s");
    }

    /** Unreachable endpoint so injected tile events are not raced by live OSM. */
    private static MapTileProvider unreachableTiles() {
        return new MapTileProvider() {
            @Override
            public String displayName() {
                return "test-unreachable";
            }

            @Override
            public String tileUrlTemplate() {
                return "http://127.0.0.1:9/no-tiles/{z}/{x}/{y}.png";
            }

            @Override
            public String attributionHtml() {
                return "test";
            }

            @Override
            public int maxZoom() {
                return 19;
            }
        };
    }
}
