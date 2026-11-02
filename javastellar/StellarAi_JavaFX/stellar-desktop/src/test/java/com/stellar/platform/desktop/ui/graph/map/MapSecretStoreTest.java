package com.stellar.platform.desktop.ui.graph.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapSecretStoreTest {

    @TempDir
    Path temp;

    @Test
    void environmentKeyWinsOverFile() throws Exception {
        Path file = temp.resolve("map-secrets.properties");
        Files.writeString(file, "apiKey=file-secret\n");
        MapSecretStore store = new MapSecretStore(file, () -> "env-secret");
        assertEquals("env-secret", store.apiKey().orElseThrow());
        assertFalse(store.toString().contains("env-secret"));
        assertFalse(store.toString().contains("file-secret"));
    }

    @Test
    void fileKeyIsUsedWhenEnvironmentIsEmpty() throws Exception {
        Path file = temp.resolve("map-secrets.properties");
        MapSecretStore store = new MapSecretStore(file, () -> null);
        store.writeApiKeyForTest("file-only");
        assertEquals("file-only", store.apiKey().orElseThrow());
        assertFalse(Files.readString(file).contains("project"));
    }

    @Test
    void redactsKeyQueryParameters() {
        String url = "https://api.maptiler.com/maps/en/256/{z}/{x}/{y}.png?key=super-secret";
        String redacted = MapSecretStore.redactForLog(url);
        assertTrue(redacted.contains("key=***"));
        assertFalse(redacted.contains("super-secret"));
        assertFalse(MapTilerEnglishRasterProvider.tryCreate(
                new MapBasemapSettings(MapLanguage.ENGLISH, MapBoundaryProfile.PROVIDER_DEFAULT,
                        MapProviderIds.AUTO, "en", "", "", 19),
                new MapSecretStore(temp.resolve("x"), () -> "super-secret"))
                .orElseThrow()
                .toString()
                .contains("super-secret"));
    }
}
