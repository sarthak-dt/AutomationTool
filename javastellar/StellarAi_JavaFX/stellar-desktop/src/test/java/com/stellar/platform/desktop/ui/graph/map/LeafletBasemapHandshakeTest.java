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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-desktop handshake: JS can have a Leaflet map while Java still has {@code leafletReady=false}.
 * Recovery must replay the ready callback, flush the pending view, and not recreate the map.
 */
class LeafletBasemapHandshakeTest {

    private static final double BENGALURU_LATITUDE = 13.12548;
    private static final double BENGALURU_LONGITUDE = 77.61881;
    private static final double BENGALURU_ZOOM = 10.6;

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        Platform.runLater(() -> Platform.setImplicitExit(false));
    }

    @Test
    @Timeout(60)
    void scenarioA_initCreatesMapAndReadyCallbackReachesJava() throws Exception {
        Harness harness = showMap(500, 380);
        assertTrue(harness.ready.await(40, TimeUnit.SECONDS));
        assertTrue(valueOf(() -> harness.view.get().isReady()));
        assertEquals(1, valueOf(() -> harness.view.get().leafletInitCountForTest()));
        assertTrue(valueOf(() -> harness.view.get().leafletReadyCallbackCountForTest()) >= 1);
        assertEquals("1", jsDiagField(harness, 0), "JS must create exactly one Leaflet map");
        harness.close();
    }

    @Test
    @Timeout(60)
    void scenarioB_existingJsMapReplaysReadyWhenJavaMissedTheCallback() throws Exception {
        Harness harness = showMap(500, 380);
        assertTrue(harness.ready.await(40, TimeUnit.SECONDS));
        int callbacks = valueOf(() -> harness.view.get().leafletReadyCallbackCountForTest());

        onFx(() -> harness.view.get().forgetLeafletReadyForTest());
        assertFalse(valueOf(() -> harness.view.get().isReady()));
        assertEquals("1", jsDiagField(harness, 0), "JS map must still exist after Java forgets ready");

        onFx(() -> harness.view.get().requestLeafletInitForTest());
        awaitFx(() -> harness.view.get().isReady(), 20);

        assertEquals(1, valueOf(() -> harness.view.get().leafletInitCountForTest()),
                "replay must not create a second Leaflet map");
        assertEquals("1", jsDiagField(harness, 0));
        assertTrue(valueOf(() -> harness.view.get().leafletReadyCallbackCountForTest()) > callbacks);
        harness.close();
    }

    @Test
    @Timeout(60)
    void scenarioC_repeatedReadyCallbackDoesNotDuplicateMapProviderOrListeners() throws Exception {
        Harness harness = showMap(500, 380);
        assertTrue(harness.ready.await(40, TimeUnit.SECONDS));
        onFx(() -> harness.view.get().applyOnline(unreachableTiles()));
        int generation = valueOf(() -> harness.view.get().providerGenerationForTest());
        int applies = valueOf(() -> harness.view.get().onlineProviderAppliesForTest());
        int inits = valueOf(() -> harness.view.get().leafletInitCountForTest());
        int readyRuns = harness.readyHandlerRuns.get();
        String pending = valueOf(() -> harness.view.get().pendingViewForTest());

        onFx(() -> harness.view.get().deliverLeafletReadyFromJsForTest());
        onFx(() -> harness.view.get().deliverLeafletReadyFromJsForTest());
        pumpFx(4);

        assertEquals(inits, valueOf(() -> harness.view.get().leafletInitCountForTest()));
        assertEquals(applies, valueOf(() -> harness.view.get().onlineProviderAppliesForTest()));
        assertEquals(generation, valueOf(() -> harness.view.get().providerGenerationForTest()));
        assertEquals(readyRuns, harness.readyHandlerRuns.get(), "ready handler must run once");
        assertEquals(pending, valueOf(() -> harness.view.get().pendingViewForTest()),
                "a replay must not reset the route viewport");
        assertEquals("1", jsDiagField(harness, 0));
        harness.close();
    }

    @Test
    @Timeout(60)
    void scenarioD_pendingViewportFlushesImmediatelyOnReady() throws Exception {
        Harness harness = showCollapsed();
        awaitFx(() -> harness.view.get().htmlReadyForTest(), 30);
        onFx(() -> harness.view.get().seedPendingViewForTest(
                BENGALURU_LATITUDE, BENGALURU_LONGITUDE, BENGALURU_ZOOM));
        assertFalse(valueOf(() -> harness.view.get().isReady()));
        assertTrue(valueOf(() -> harness.view.get().pendingViewForTest()).contains("lat13.12548"));

        onFx(() -> {
            harness.host.get().setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            harness.host.get().setPrefSize(500, 380);
        });
        assertTrue(harness.ready.await(40, TimeUnit.SECONDS));
        awaitFx(() -> leafletCenterLooksLikeBengaluru(harness.view.get()), 20);
        assertTrue(valueOf(() -> harness.view.get().pendingViewForTest()).contains("pendingView=yes")
                || valueOf(() -> harness.view.get().pendingViewForTest()).contains("lat13.12548"));
        harness.close();
    }

    @Test
    @Timeout(60)
    void scenarioE_pendingProviderThenPendingViewportApplyInOrder() throws Exception {
        Harness harness = showCollapsed();
        awaitFx(() -> harness.view.get().htmlReadyForTest(), 30);
        onFx(() -> {
            harness.view.get().applyOnline(unreachableTiles());
            harness.view.get().seedPendingViewForTest(
                    BENGALURU_LATITUDE, BENGALURU_LONGITUDE, BENGALURU_ZOOM);
        });
        assertEquals(1, valueOf(() -> harness.view.get().onlineProviderAppliesForTest()));
        assertFalse(valueOf(() -> harness.view.get().isReady()));

        onFx(() -> {
            harness.host.get().setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            harness.host.get().setPrefSize(500, 380);
        });
        assertTrue(harness.ready.await(40, TimeUnit.SECONDS));
        awaitFx(() -> leafletCenterLooksLikeBengaluru(harness.view.get()), 20);
        assertEquals(MapBasemapMode.ONLINE, valueOf(() -> harness.view.get().mode()));
        assertEquals(1, valueOf(() -> harness.view.get().onlineProviderAppliesForTest()));
        harness.close();
    }

    @Test
    @Timeout(60)
    void scenarioF_zeroDomIsNotUsableThenNonZeroSendsReady() throws Exception {
        Harness harness = showCollapsed();
        awaitFx(() -> harness.view.get().htmlReadyForTest(), 30);
        pumpFx(6);
        assertFalse(harness.ready.await(1, TimeUnit.SECONDS));
        assertEquals(0, valueOf(() -> harness.view.get().leafletInitCountForTest()));
        assertEquals(0, valueOf(() -> harness.view.get().leafletReadyCallbackCountForTest()));

        onFx(() -> {
            harness.host.get().setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            harness.host.get().setPrefSize(360, 260);
        });
        assertTrue(harness.ready.await(40, TimeUnit.SECONDS));
        assertEquals(1, valueOf(() -> harness.view.get().leafletInitCountForTest()));
        assertTrue(valueOf(() -> harness.view.get().leafletReadyCallbackCountForTest()) >= 1);
        harness.close();
    }

    @Test
    @Timeout(60)
    void scenarioG_watchdogRecoveryReplaysExistingMapInsteadOfNeutral() throws Exception {
        Harness harness = showMap(500, 380);
        assertTrue(harness.ready.await(40, TimeUnit.SECONDS));
        List<MapBasemapMode> failures = new ArrayList<>();
        onFx(() -> harness.view.get().setModeFailedHandler(failures::add));

        onFx(() -> {
            harness.view.get().forgetLeafletReadyForTest();
            harness.view.get().readinessTimeoutUnforcedForTest();
        });
        awaitFx(() -> harness.view.get().isReady(), 20);
        assertFalse(valueOf(() -> harness.view.get().readinessFailedForTest()));
        assertFalse(failures.contains(MapBasemapMode.NEUTRAL));

        onFx(() -> {
            harness.view.get().forgetLeafletReadyForTest();
            harness.view.get().readinessTimeoutUnforcedForTest();
        });
        awaitFx(() -> harness.view.get().isReady(), 20);
        assertFalse(valueOf(() -> harness.view.get().readinessFailedForTest()),
                "an existing usable JS map must not force NEUTRAL");
        assertFalse(failures.contains(MapBasemapMode.NEUTRAL));
        assertEquals(1, valueOf(() -> harness.view.get().leafletInitCountForTest()));
        harness.close();
    }

    private static boolean leafletCenterLooksLikeBengaluru(LeafletBasemapView view) {
        MapBasemapDiagnostics diagnostics = view.diagnostics();
        String bounds = diagnostics.geographicBounds();
        if (bounds == null || !bounds.contains("center=")) {
            return false;
        }
        int start = bounds.indexOf("center=") + "center=".length();
        int comma = bounds.indexOf(',', start);
        int space = bounds.indexOf(' ', comma);
        if (comma < 0) {
            return false;
        }
        try {
            double latitude = Double.parseDouble(bounds.substring(start, comma));
            double longitude = Double.parseDouble(bounds.substring(comma + 1, space < 0 ? bounds.length() : space));
            return Math.abs(latitude - BENGALURU_LATITUDE) < 0.5
                    && Math.abs(longitude - BENGALURU_LONGITUDE) < 0.5;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static String jsDiagField(Harness harness, int index) throws Exception {
        return valueOf(() -> {
            Object raw = harness.view.get().webViewForTest().getEngine()
                    .executeScript("window.stellarMapDiagnostics();");
            String[] parts = String.valueOf(raw).split("\\|");
            return index < parts.length ? parts[index] : "";
        });
    }

    private static Harness showMap(int width, int height) throws Exception {
        Harness harness = new Harness();
        onFx(() -> {
            LeafletBasemapView view = new LeafletBasemapView();
            view.setReadyHandler(() -> {
                harness.readyHandlerRuns.incrementAndGet();
                harness.ready.countDown();
            });
            harness.view.set(view);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), width, height));
            stage.show();
            harness.stage.set(stage);
        });
        return harness;
    }

    private static Harness showCollapsed() throws Exception {
        Harness harness = new Harness();
        onFx(() -> {
            LeafletBasemapView view = new LeafletBasemapView();
            view.setReadyHandler(() -> {
                harness.readyHandlerRuns.incrementAndGet();
                harness.ready.countDown();
            });
            harness.view.set(view);
            StackPane host = new StackPane(view);
            host.setMinSize(0, 0);
            host.setPrefSize(0, 0);
            host.setMaxSize(0, 0);
            harness.host.set(host);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(host), 400, 300));
            stage.show();
            harness.stage.set(stage);
        });
        return harness;
    }

    private static final class Harness {
        final AtomicReference<LeafletBasemapView> view = new AtomicReference<>();
        final AtomicReference<StackPane> host = new AtomicReference<>();
        final AtomicReference<Stage> stage = new AtomicReference<>();
        final CountDownLatch ready = new CountDownLatch(1);
        final AtomicInteger readyHandlerRuns = new AtomicInteger();

        void close() throws Exception {
            onFx(() -> {
                if (view.get() != null) {
                    view.get().dispose();
                }
                if (stage.get() != null) {
                    stage.get().close();
                }
            });
        }
    }

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
