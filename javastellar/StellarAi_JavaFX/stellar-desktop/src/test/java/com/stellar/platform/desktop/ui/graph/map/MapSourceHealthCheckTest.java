package com.stellar.platform.desktop.ui.graph.map;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapSourceHealthCheckTest {

    @TempDir
    Path temp;

    @Test
    void missingUrlReportsNotConfigured() {
        MapBasemapSettings settings = new MapBasemapSettings(
                MapLanguage.ENGLISH,
                MapBoundaryProfile.PROVIDER_DEFAULT,
                MapProviderIds.SELFHOSTED_ENGLISH_RASTER,
                "",
                "",
                "",
                19);
        MapSourceHealthCheck.Result result = MapSourceHealthCheck.check(
                settings, MapSecretStore.empty(), new OfflineMapPackRegistry(temp.resolve("empty")));
        assertFalse(result.reachable());
        assertEquals("Self-hosted map URL is not configured.", result.userMessage());
        assertFalse(result.userMessage().contains("401"));
        assertFalse(result.userMessage().contains("SELFHOSTED"));
    }

    @Test
    void reachableSelfHostedTileReturnsOkWithoutCloudKey() throws Exception {
        byte[] png = MapSyntheticTilePng.png(0, 0, 0);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            exchange.getResponseBody().write(png);
            exchange.close();
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        try {
            int port = server.getAddress().getPort();
            MapBasemapSettings settings = new MapBasemapSettings(
                    MapLanguage.ENGLISH,
                    MapBoundaryProfile.PROVIDER_DEFAULT,
                    MapProviderIds.SELFHOSTED_ENGLISH_RASTER,
                    "",
                    "http://127.0.0.1:" + port + "/styles/stellar-en/{z}/{x}/{y}.png",
                    "© OpenMapTiles © OpenStreetMap contributors",
                    14);
            MapSourceHealthCheck.Result result = MapSourceHealthCheck.check(
                    settings, MapSecretStore.empty(), new OfflineMapPackRegistry(temp.resolve("empty")));
            assertTrue(result.reachable());
            assertEquals("Map source responded.", result.userMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sampleUrlRedactsKeys() {
        String sample = MapSourceHealthCheck.sampleTileUrl(
                "https://api.maptiler.com/maps/en/256/{z}/{x}/{y}.png?key=super-secret");
        assertTrue(sample.contains("0/0/0"));
        assertFalse(MapSecretStore.redactForLog(sample).contains("super-secret"));
    }

    @Test
    void englishPackCountsAsConfiguredSource() throws Exception {
        Path maps = temp.resolve("maps");
        Files.createDirectories(maps);
        Files.writeString(maps.resolve("india-en.pmtiles"), "not-a-real-archive");
        Files.writeString(maps.resolve("india-en.map.properties"), """
                supportsEnglishLabels=true
                tileFormat=raster
                boundaryProfile=PROVIDER_DEFAULT
                """);
        MapSourceHealthCheck.Result result = MapSourceHealthCheck.check(
                MapBasemapSettings.defaults(),
                MapSecretStore.empty(),
                new OfflineMapPackRegistry(maps));
        assertTrue(result.reachable());
    }
}
