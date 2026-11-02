package com.stellar.platform.desktop.ui.graph.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapLocalAssetServerTest {

    @TempDir
    Path temp;

    @Test
    void servesPmtilesWithHttpRange() throws Exception {
        Path packFile = temp.resolve("demo.pmtiles");
        byte[] payload = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(StandardCharsets.US_ASCII);
        Files.write(packFile, payload);
        OfflineMapPack pack = OfflineMapPack.untagged(
                "demo", "Demo", packFile, "© test", null, null, null, null, true);

        try (MapLocalAssetServer server = new MapLocalAssetServer()) {
            String url = server.registerPack(pack);
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<byte[]> full = client.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, full.statusCode());
            assertEquals(new String(payload, StandardCharsets.US_ASCII),
                    new String(full.body(), StandardCharsets.US_ASCII));

            HttpResponse<byte[]> ranged = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .header("Range", "bytes=0-4")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(206, ranged.statusCode());
            assertEquals("ABCDE", new String(ranged.body(), StandardCharsets.US_ASCII));
            assertTrue(ranged.headers().firstValue("Content-Range").orElse("").startsWith("bytes 0-4/"));
        }
    }
}
