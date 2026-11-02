package com.stellar.platform.desktop.ui.graph.map;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Offline raster PMTiles via loopback Range server + Leaflet WebView (no external tile CDN).
 *
 * <p>Fixture: Stamen Toner z3 raster from the PMTiles v3 spec examples (CC-BY + ODbL),
 * stored under test resources only — not shipped in the production installer.
 */
class OfflinePmtilesIntegrationTest {

    private static final String FIXTURE = "/map/stamen_toner_z3.pmtiles";

    @TempDir
    Path temp;

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
    void localAssetServerServesFixtureWithRange() throws Exception {
        Path packFile = copyFixture();
        assumeTrue(Files.size(packFile) > 1_000, "PMTiles fixture missing");

        OfflineMapPack pack = OfflineMapPack.untagged(
                "stamen-toner-z3", "Stamen Toner z3", packFile,
                "Map tiles by Stamen Design (CC BY 4.0); data © OpenStreetMap",
                null, null, null, null, true);

        try (MapLocalAssetServer server = new MapLocalAssetServer()) {
            String url = server.registerPack(pack);
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<byte[]> header = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .header("Range", "bytes=0-126")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(206, header.statusCode());
            byte[] body = header.body();
            assertEquals('P', body[0]);
            assertEquals('M', body[1]);
            // PMTiles v3 tile_type at offset 99: 1=MVT, 2=PNG, 3=JPEG, 4=WebP
            int tileType = body[99] & 0xFF;
            assertTrue(tileType == 2 || tileType == 3 || tileType == 4,
                    "Fixture must be raster for JavaFX WebView (tileType=" + tileType + ")");
        }
    }

    @Test
    @Timeout(60)
    void leafletLoadsOfflinePmtilesWithoutExternalTiles() throws Exception {
        Path packFile = copyFixture();
        assumeTrue(Files.size(packFile) > 1_000, "PMTiles fixture missing");

        OfflineMapPack pack = OfflineMapPack.untagged(
                "stamen-toner-z3", "Stamen Toner z3", packFile,
                "© Stamen / OSM", null, null, null, null, true);

        CountDownLatch ready = new CountDownLatch(1);
        AtomicBoolean offlineLayerOk = new AtomicBoolean(false);
        AtomicReference<LeafletBasemapView> viewRef = new AtomicReference<>();
        AtomicReference<MapLocalAssetServer> serverRef = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                MapLocalAssetServer server = new MapLocalAssetServer();
                serverRef.set(server);
                String url = server.registerPack(pack);
                LeafletBasemapView view = new LeafletBasemapView();
                viewRef.set(view);
                view.setReadyHandler(() -> {
                    view.applyOfflinePmtiles(url, pack.attribution());
                    Platform.runLater(() -> Platform.runLater(() -> {
                        try {
                            Object ok = ((javafx.scene.web.WebView) view.getChildren().get(0))
                                    .getEngine()
                                    .executeScript(
                                            "baseLayer != null && map != null"
                                                    + " && document.documentElement.outerHTML.indexOf('tile.openstreetmap.org') < 0");
                            offlineLayerOk.set(Boolean.TRUE.equals(ok));
                        } catch (RuntimeException ex) {
                            offlineLayerOk.set(false);
                        } finally {
                            ready.countDown();
                        }
                    }));
                });
                Stage stage = new Stage();
                stage.setScene(new Scene(new StackPane(view), 800, 600));
                stage.show();
            } catch (Exception ex) {
                ready.countDown();
            }
        });

        assertTrue(ready.await(45, TimeUnit.SECONDS), "Offline basemap did not become ready");
        // Allow PMTiles getHeader() promise to settle.
        Thread.sleep(1500);
        CountDownLatch probe = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                LeafletBasemapView view = viewRef.get();
                if (view != null) {
                    Object ok = ((javafx.scene.web.WebView) view.getChildren().get(0))
                            .getEngine()
                            .executeScript("baseLayer != null");
                    if (Boolean.TRUE.equals(ok)) {
                        offlineLayerOk.set(true);
                    }
                    view.invalidateSize("offline-smoke");
                }
            } finally {
                probe.countDown();
            }
        });
        assertTrue(probe.await(10, TimeUnit.SECONDS));
        assertTrue(offlineLayerOk.get(), "Offline raster layer should be attached without OSM tile URLs");

        CountDownLatch closed = new CountDownLatch(1);
        Platform.runLater(() -> {
            LeafletBasemapView view = viewRef.get();
            if (view != null) {
                view.dispose();
            }
            MapLocalAssetServer server = serverRef.get();
            if (server != null) {
                server.close();
            }
            closed.countDown();
        });
        assertTrue(closed.await(5, TimeUnit.SECONDS));
    }

    private Path copyFixture() throws Exception {
        Path target = temp.resolve("stamen_toner_z3.pmtiles");
        try (InputStream in = OfflinePmtilesIntegrationTest.class.getResourceAsStream(FIXTURE)) {
            assumeTrue(in != null, "Test resource " + FIXTURE + " not on classpath");
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }
}
