package com.stellar.platform.desktop.ui.graph.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfHostedEnglishRasterProviderTest {

    @TempDir
    Path temp;

    @Test
    void configuredUrlIsEnglishCapableWithoutApiKey() {
        MapBasemapSettings settings = selfHosted(
                "http://tiles.example.internal/styles/stellar-en/{z}/{x}/{y}.png",
                "© OpenMapTiles © OpenStreetMap contributors",
                14);
        MapTileProvider provider = SelfHostedEnglishRasterProvider.tryCreate(settings).orElseThrow();
        assertEquals(MapProviderIds.SELFHOSTED_ENGLISH_RASTER, provider.providerId());
        assertTrue(provider.supportsEnglishLabels());
        assertFalse(provider.requiresApiKey());
        assertEquals(256, provider.tilePixelSize());
        assertEquals(14, provider.maxZoom());
        assertTrue(provider.attributionHtml().toLowerCase().contains("openstreetmap"));
        assertTrue(provider.attributionHtml().toLowerCase().contains("openmaptiles"));
        assertEquals(settings.selfHostedUrl(), provider.tileUrlTemplate());
        assertFalse(provider.toString().contains("{key}"));
    }

    @Test
    void missingUrlLeavesProviderUnavailable() {
        assertTrue(SelfHostedEnglishRasterProvider.tryCreate(MapBasemapSettings.defaults()).isEmpty());
        assertTrue(SelfHostedEnglishRasterProvider.tryCreate(selfHosted("", "", 19)).isEmpty());
    }

    @Test
    void keyPlaceholderAndRetinaUrlsAreRejected() {
        assertTrue(SelfHostedEnglishRasterProvider.tryCreate(
                selfHosted("http://tiles.example/{z}/{x}/{y}.png?key={key}", "", 19)).isEmpty());
        assertTrue(SelfHostedEnglishRasterProvider.tryCreate(
                selfHosted("http://tiles.example/{z}/{x}/{y}@2x.png", "", 19)).isEmpty());
        assertTrue(SelfHostedEnglishRasterProvider.tryCreate(
                selfHosted("file:///maps/{z}/{x}/{y}.png", "", 19)).isEmpty());
    }

    @Test
    void projectDirectoryDoesNotChangeSelfHostedConfig() throws Exception {
        MapBasemapSettingsStore store = new MapBasemapSettingsStore(temp.resolve("map-settings.properties"));
        MapBasemapSettings saved = selfHosted(
                "http://tiles.example/styles/stellar-en/{z}/{x}/{y}.png",
                "© OpenMapTiles © OpenStreetMap contributors",
                12);
        saved = new MapBasemapSettings(
                saved.language(),
                saved.boundaryProfile(),
                MapProviderIds.SELFHOSTED_ENGLISH_RASTER,
                saved.englishMapId(),
                saved.selfHostedUrl(),
                saved.selfHostedAttribution(),
                saved.selfHostedMaxZoom());
        store.save(saved);
        Path projectDb = temp.resolve("project.db");
        Files.writeString(projectDb, "sqlite-bytes");
        MapBasemapSettings loaded = store.load();
        assertEquals(MapProviderIds.SELFHOSTED_ENGLISH_RASTER, loaded.providerPreference());
        assertEquals("http://tiles.example/styles/stellar-en/{z}/{x}/{y}.png", loaded.selfHostedUrl());
        assertEquals(12, loaded.selfHostedMaxZoom());
        assertFalse(Files.readString(projectDb).contains("tiles.example"));
        String persisted = Files.readString(store.settingsFile());
        assertTrue(persisted.contains("selfHostedUrl="));
        assertFalse(persisted.toLowerCase().contains("apikey"));
    }

    @Test
    void localhostIsAcceptedForDevelopmentButIsNotTheDefault() {
        assertTrue(MapBasemapSettings.defaults().selfHostedUrl().isEmpty());
        MapTileProvider provider = SelfHostedEnglishRasterProvider.tryCreate(selfHosted(
                "http://127.0.0.1:8080/styles/stellar-en/{z}/{x}/{y}.png", "", 14)).orElseThrow();
        assertTrue(provider.tileUrlTemplate().contains("127.0.0.1"));
    }

    private static MapBasemapSettings selfHosted(String url, String attribution, int maxZoom) {
        return new MapBasemapSettings(
                MapLanguage.ENGLISH,
                MapBoundaryProfile.PROVIDER_DEFAULT,
                MapProviderIds.AUTO,
                "",
                url,
                attribution,
                maxZoom);
    }
}
