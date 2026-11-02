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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JS tile snapshots are the health source of truth. LiveConnect misses must not fail a loaded map.
 */
class LeafletBasemapTileHealthAdoptTest {

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
    void jsHealthySnapshotOverridesMissedLiveConnect() throws Exception {
        Harness harness = showReady();
        onFx(() -> harness.view.beginOnlineGenerationForTest());
        int generation = valueOf(() -> harness.view.providerGenerationForTest());
        assertEquals(BasemapTileHealth.TILES_LOADING, valueOf(() -> harness.view.tileHealth()));
        assertFalse(valueOf(() -> harness.view.firstTileLoadedForTest()));

        onFx(() -> harness.view.adoptTileHealthSnapshotForTest(
                generation + "|1|9|9|9|0|9|1|1|1"));

        assertEquals(BasemapTileHealth.TILES_HEALTHY, valueOf(() -> harness.view.tileHealth()));
        assertTrue(valueOf(() -> harness.view.tilesLoaded()));
        assertTrue(valueOf(() -> harness.view.firstTileLoadedForTest()));
        assertTrue(valueOf(() -> harness.view.adoptedTileHealthFromJsForTest()),
                "a missed LiveConnect firstTile must still be adopted from JS");
        harness.close();
    }

    @Test
    @Timeout(60)
    void generationMismatchIsRejected() throws Exception {
        Harness harness = showReady();
        onFx(() -> harness.view.beginOnlineGenerationForTest());
        int generation = valueOf(() -> harness.view.providerGenerationForTest());

        onFx(() -> harness.view.adoptTileHealthSnapshotForTest(
                (generation + 1) + "|1|9|9|9|0|9|1|1|1"));

        assertEquals(BasemapTileHealth.TILES_LOADING, valueOf(() -> harness.view.tileHealth()));
        assertFalse(valueOf(() -> harness.view.tilesLoaded()));
        assertFalse(valueOf(() -> harness.view.firstTileLoadedForTest()));
        harness.close();
    }

    @Test
    @Timeout(60)
    void partialSnapshotIsNotHealthy() throws Exception {
        Harness harness = showReady();
        onFx(() -> harness.view.beginOnlineGenerationForTest());
        int generation = valueOf(() -> harness.view.providerGenerationForTest());
        onFx(() -> harness.view.adoptTileHealthSnapshotForTest(
                generation + "|1|9|6|6|1|6|1|0|1"));
        assertEquals(BasemapTileHealth.TILES_PARTIALLY_LOADED, valueOf(() -> harness.view.tileHealth()));
        assertFalse(valueOf(() -> harness.view.tilesLoaded()));
        harness.close();
    }

    @Test
    @Timeout(60)
    void failedSnapshotFailsTheProvider() throws Exception {
        Harness harness = showReady();
        List<MapBasemapMode> failures = new ArrayList<>();
        onFx(() -> harness.view.setModeFailedHandler(failures::add));
        onFx(() -> harness.view.beginOnlineGenerationForTest());
        int generation = valueOf(() -> harness.view.providerGenerationForTest());
        onFx(() -> harness.view.adoptTileHealthSnapshotForTest(
                generation + "|1|0|0|0|4|0|0|1|1"));
        assertEquals(BasemapTileHealth.TILE_PROVIDER_FAILED, valueOf(() -> harness.view.tileHealth()));
        assertTrue(valueOf(() -> harness.view.onlineTilesFailed()));
        assertTrue(failures.contains(MapBasemapMode.ONLINE));
        harness.close();
    }

    @Test
    @Timeout(60)
    void sameUrlReapplyDoesNotLoseKnownHealthyState() throws Exception {
        Harness harness = showReady();
        onFx(() -> harness.view.beginOnlineGenerationForTest());
        int generation = valueOf(() -> harness.view.providerGenerationForTest());
        onFx(() -> harness.view.adoptTileHealthSnapshotForTest(
                generation + "|1|9|9|9|0|9|1|1|1"));
        assertEquals(BasemapTileHealth.TILES_HEALTHY, valueOf(() -> harness.view.tileHealth()));

        onFx(() -> harness.view.applyOnline(testTiles()));
        assertEquals(2, valueOf(() -> harness.view.onlineProviderAppliesForTest()));
        assertEquals(generation, valueOf(() -> harness.view.providerGenerationForTest()),
                "one logical provider apply is one generation");
        assertEquals(BasemapTileHealth.TILES_HEALTHY, valueOf(() -> harness.view.tileHealth()));
        assertTrue(valueOf(() -> harness.view.firstTileLoadedForTest()));
        harness.close();
    }

    private static Harness showReady() throws Exception {
        AtomicReference<LeafletBasemapView> viewRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        onFx(() -> {
            LeafletBasemapView view = new LeafletBasemapView();
            view.setReadyHandler(ready::countDown);
            viewRef.set(view);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 480, 360));
            stage.show();
            stageRef.set(stage);
        });
        assertTrue(ready.await(40, TimeUnit.SECONDS));
        return new Harness(viewRef.get(), stageRef.get());
    }

    private record Harness(LeafletBasemapView view, Stage stage) {
        void close() throws Exception {
            onFx(() -> {
                view.dispose();
                stage.close();
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

    private static MapTileProvider testTiles() {
        return new MapTileProvider() {
            @Override
            public String displayName() {
                return "test-tiles";
            }

            @Override
            public String tileUrlTemplate() {
                return "test://tiles/{z}/{x}/{y}.png";
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
