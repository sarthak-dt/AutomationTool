package com.stellar.platform.desktop.ui.graph.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapProviderCapabilityTest {

    @TempDir
    Path temp;

    @Test
    void englishIsTheDefaultMapLanguage() {
        assertEquals(MapLanguage.ENGLISH, MapLanguage.defaultLanguage());
        assertEquals(MapLanguage.ENGLISH, MapBasemapSettings.defaults().language());
    }

    @Test
    void osmCartoDoesNotClaimEnglishLabels() {
        MapTileProvider osm = MapTileProvider.openStreetMap();
        assertEquals(MapProviderIds.OSM_CARTO, osm.providerId());
        assertFalse(osm.supportsEnglishLabels());
        assertFalse(osm.requiresApiKey());
        assertEquals(Set.of(MapBoundaryProfile.PROVIDER_DEFAULT), osm.supportedBoundaryProfiles());
        assertTrue(osm.attributionHtml().toLowerCase().contains("openstreetmap"));
        assertEquals(19, osm.maxZoom());
    }

    @Test
    void maptilerEnglishRasterRequiresKeyAndMapId() {
        MapBasemapSettings settings = new MapBasemapSettings(
                MapLanguage.ENGLISH,
                MapBoundaryProfile.PROVIDER_DEFAULT,
                MapProviderIds.AUTO,
                "streets-en",
                "",
                "",
                19);
        assertTrue(MapTilerEnglishRasterProvider.tryCreate(settings, MapSecretStore.empty()).isEmpty());

        MapSecretStore secrets = new MapSecretStore(temp.resolve("map-secrets.properties"), () -> "lab-key");
        MapTileProvider provider = MapTilerEnglishRasterProvider.tryCreate(settings, secrets).orElseThrow();
        assertTrue(provider.supportsEnglishLabels());
        assertTrue(provider.requiresApiKey());
        assertEquals(MapProviderIds.MAPTILER_ENGLISH_RASTER, provider.providerId());
        assertEquals(19, provider.maxZoom());
        assertTrue(provider.tileUrlTemplate().contains("/256/{z}/{x}/{y}.png"));
        assertFalse(provider.tileUrlTemplate().contains("@2x"));
        assertTrue(provider.attributionHtml().toLowerCase().contains("maptiler"));
        assertTrue(provider.attributionHtml().toLowerCase().contains("openstreetmap"));
        assertEquals(256, provider.tilePixelSize());
        assertFalse(provider.toString().contains("lab-key"));
    }

    @Test
    void settingsPersistAtApplicationLevelNotPerProject() throws Exception {
        Path config = temp.resolve("config");
        MapBasemapSettingsStore store = new MapBasemapSettingsStore(config.resolve("map-settings.properties"));
        MapBasemapSettings saved = new MapBasemapSettings(
                MapLanguage.ENGLISH,
                MapBoundaryProfile.GLOBAL_NEUTRAL,
                MapProviderIds.MAPTILER_ENGLISH_RASTER,
                "english-style",
                "",
                "",
                19);
        store.save(saved);
        MapBasemapSettings reloaded = store.load();
        assertEquals(MapLanguage.ENGLISH, reloaded.language());
        assertEquals(MapBoundaryProfile.GLOBAL_NEUTRAL, reloaded.boundaryProfile());
        assertEquals("english-style", reloaded.englishMapId());

        Path projectDb = temp.resolve("projects").resolve("proj-a").resolve("project.db");
        Files.createDirectories(projectDb.getParent());
        Files.writeString(projectDb, "sqlite-bytes");
        MapBasemapSettings afterProjectChange = store.load();
        assertEquals(reloaded, afterProjectChange);
        assertFalse(Files.readString(projectDb).contains("english-style"));
        assertFalse(Files.readString(store.settingsFile()).toLowerCase().contains("apikey"));
    }

    @Test
    void indiaOfficialCannotBePersistedAsSupportedWithoutASource() throws Exception {
        MapBasemapSettingsStore store = new MapBasemapSettingsStore(temp.resolve("map-settings.properties"));
        store.save(MapBasemapSettings.defaults().withBoundaryProfile(MapBoundaryProfile.INDIA_OFFICIAL));
        assertEquals(MapBoundaryProfile.PROVIDER_DEFAULT, store.load().boundaryProfile());
        assertFalse(MapBoundaryProfile.indiaOfficialSourceConfigured());
        assertFalse(MapBoundaryProfile.INDIA_OFFICIAL.selectableAsSupported());
    }

    @Test
    void englishResolutionSkipsOsmAndUsesConfiguredRaster() {
        MapTileProvider english = englishStub();
        MapProviderResolver resolver = new MapProviderResolver(
                MapBasemapSettings.defaults(),
                List.of(MapTileProvider.openStreetMap(), english),
                new OfflineMapPackRegistry(temp.resolve("empty-maps")),
                true);
        MapProviderSelection selection = resolver.resolve(true, true, 12, 77, 13, 78);
        assertEquals(MapBasemapMode.ONLINE, selection.mode());
        assertEquals("TEST_ENGLISH", selection.onlineProvider().providerId());
        assertNotEquals(MapProviderIds.OSM_CARTO, selection.onlineProvider().providerId());
        assertEquals(MapBasemapCompliance.ENGLISH_COMPLIANT, selection.compliance());
        assertTrue(selection.englishCompliant());
    }

    @Test
    void englishModeFallsBackToOsmWhenNoEnglishProviderExists() {
        MapProviderResolver resolver = new MapProviderResolver(
                MapBasemapSettings.defaults(),
                List.of(MapTileProvider.openStreetMap()),
                new OfflineMapPackRegistry(temp.resolve("empty-maps")),
                true);
        MapProviderSelection selection = resolver.resolve(true, true, 12, 77, 13, 78);
        assertEquals(MapBasemapMode.ONLINE, selection.mode());
        assertEquals(MapProviderIds.OSM_CARTO, selection.onlineProvider().providerId());
        assertFalse(selection.onlineProvider().supportsEnglishLabels());
        assertEquals(MapBasemapCompliance.STANDARD_FALLBACK, selection.compliance());
        assertFalse(selection.englishCompliant());
        assertEquals(MapBasemapUserMessages.STANDARD_FALLBACK, selection.reason());
        assertNotEquals(MapBasemapCompliance.ENGLISH_COMPLIANT, selection.compliance());
    }

    @Test
    void englishModeUsesNeutralWhenStandardFallbackDisabled() {
        MapProviderResolver resolver = new MapProviderResolver(
                MapBasemapSettings.defaults().withAllowStandardFallback(false),
                List.of(MapTileProvider.openStreetMap()),
                new OfflineMapPackRegistry(temp.resolve("empty-maps")),
                true);
        MapProviderSelection selection = resolver.resolve(true, true, 12, 77, 13, 78);
        assertEquals(MapBasemapMode.NEUTRAL, selection.mode());
        assertEquals(MapBasemapCompliance.NEUTRAL, selection.compliance());
        assertEquals(MapBasemapUserMessages.MAP_UNAVAILABLE, selection.reason());
    }

    @Test
    void missingMaptilerKeyFallsBackToOsmWhenStandardFallbackAllowed() {
        MapBasemapSettings settings = new MapBasemapSettings(
                MapLanguage.ENGLISH,
                MapBoundaryProfile.PROVIDER_DEFAULT,
                MapProviderIds.MAPTILER_ENGLISH_RASTER,
                "streets-en",
                "",
                "",
                19);
        MapProviderCatalog catalog = new MapProviderCatalog(
                () -> settings, MapSecretStore.empty(), List.of(MapTileProvider.openStreetMap()));
        MapProviderResolver resolver = new MapProviderResolver(
                () -> settings, catalog, new OfflineMapPackRegistry(temp.resolve("empty-maps")), true);
        MapProviderSelection selection = resolver.resolve(true, true, 12, 77, 13, 78);
        assertEquals(MapBasemapMode.ONLINE, selection.mode());
        assertEquals(MapProviderIds.OSM_CARTO, selection.onlineProvider().providerId());
        assertEquals(MapBasemapCompliance.STANDARD_FALLBACK, selection.compliance());
        assertEquals(MapBasemapUserMessages.STANDARD_FALLBACK, selection.reason());
    }

    @Test
    void englishProviderFailureFallsBackToHealthyOsm() {
        MapProviderResolver resolver = new MapProviderResolver(
                MapBasemapSettings.defaults(),
                List.of(englishStub(), MapTileProvider.openStreetMap()),
                new OfflineMapPackRegistry(temp.resolve("empty-maps")),
                true);
        MapProviderSelection selection = resolver.afterOnlineFailure(
                "TEST_ENGLISH", 12, 77, 13, 78);
        assertEquals(MapBasemapMode.ONLINE, selection.mode());
        assertEquals(MapProviderIds.OSM_CARTO, selection.onlineProvider().providerId());
        assertEquals(MapBasemapCompliance.STANDARD_FALLBACK, selection.compliance());
        assertFalse(selection.englishCompliant());
    }

    @Test
    void osmFallbackFailureGoesToNeutral() {
        MapProviderResolver resolver = new MapProviderResolver(
                MapBasemapSettings.defaults(),
                List.of(MapTileProvider.openStreetMap()),
                new OfflineMapPackRegistry(temp.resolve("empty-maps")),
                true);
        MapProviderSelection selection = resolver.afterOnlineFailure(
                MapProviderIds.OSM_CARTO, 12, 77, 13, 78);
        assertEquals(MapBasemapMode.NEUTRAL, selection.mode());
        assertEquals(MapBasemapCompliance.NEUTRAL, selection.compliance());
        assertEquals(MapBasemapUserMessages.MAP_UNAVAILABLE, selection.reason());
    }

    @Test
    void englishOfflinePackIsUsedWhenOnlineIsUnhealthy() throws Exception {
        Path maps = temp.resolve("maps");
        Files.createDirectories(maps);
        Files.writeString(maps.resolve("india-en.pmtiles"), "not-a-real-archive");
        Files.writeString(maps.resolve("india-en.map.properties"), """
                supportsEnglishLabels=true
                boundaryProfile=PROVIDER_DEFAULT
                attribution=© OpenMapTiles © OpenStreetMap
                styleId=openmaptiles-en
                """);
        MapProviderResolver resolver = new MapProviderResolver(
                MapBasemapSettings.defaults(),
                List.of(englishStub()),
                new OfflineMapPackRegistry(maps),
                true);
        MapProviderSelection selection = resolver.afterOnlineFailure(12, 77, 13, 78);
        assertEquals(MapBasemapMode.OFFLINE_PMTILES, selection.mode());
        assertEquals(MapBasemapCompliance.ENGLISH_COMPLIANT, selection.compliance());
        assertTrue(selection.offlinePackOptional().orElseThrow().supportsEnglishLabels());
        assertEquals("india-en", selection.offlinePackOptional().orElseThrow().id());
    }

    @Test
    void untaggedLegacyPackIsNotEnglishCompliant() throws Exception {
        Path maps = temp.resolve("maps");
        Files.createDirectories(maps);
        Files.writeString(maps.resolve("legacy.pmtiles"), "not-a-real-archive");
        MapProviderResolver resolver = new MapProviderResolver(
                MapBasemapSettings.defaults(),
                List.of(),
                new OfflineMapPackRegistry(maps),
                true);
        MapProviderSelection selection = resolver.resolve(true, true, 12, 77, 13, 78);
        assertEquals(MapBasemapMode.ONLINE, selection.mode());
        assertEquals(MapProviderIds.OSM_CARTO, selection.onlineProvider().providerId());
        assertEquals(MapBasemapCompliance.STANDARD_FALLBACK, selection.compliance());
        OfflineMapPack pack = new OfflineMapPackRegistry(maps).listEnabled().get(0);
        assertFalse(pack.supportsEnglishLabels());
    }

    @Test
    void allowStandardFallbackPersistsAtApplicationLevel() throws Exception {
        Path config = temp.resolve("config-fallback");
        MapBasemapSettingsStore store = new MapBasemapSettingsStore(config.resolve("map-settings.properties"));
        store.save(MapBasemapSettings.defaults().withAllowStandardFallback(false));
        assertFalse(store.load().allowStandardFallback());
        store.save(MapBasemapSettings.defaults().withAllowStandardFallback(true));
        assertTrue(store.load().allowStandardFallback());
        assertTrue(MapBasemapSettings.defaults().allowStandardFallback());
    }

    private static MapTileProvider englishStub() {
        return new MapTileProvider() {
            @Override
            public String providerId() {
                return "TEST_ENGLISH";
            }

            @Override
            public String displayName() {
                return "test-english";
            }

            @Override
            public String tileUrlTemplate() {
                return "test://en/{z}/{x}/{y}.png";
            }

            @Override
            public String attributionHtml() {
                return "© test";
            }

            @Override
            public int maxZoom() {
                return 19;
            }

            @Override
            public boolean supportsEnglishLabels() {
                return true;
            }
        };
    }
}
