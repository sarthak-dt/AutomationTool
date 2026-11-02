package com.stellar.platform.desktop.ui.graph.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineEnglishPackResolutionTest {

    @TempDir
    Path temp;

    @Test
    void autoPrefersSelfHostedOverMapTilerWhenBothAreConfigured() {
        MapBasemapSettings settings = new MapBasemapSettings(
                MapLanguage.ENGLISH,
                MapBoundaryProfile.PROVIDER_DEFAULT,
                MapProviderIds.AUTO,
                "streets-en",
                "http://tiles.example/styles/stellar-en/{z}/{x}/{y}.png",
                "",
                14);
        MapSecretStore secrets = new MapSecretStore(temp.resolve("secrets.properties"), () -> "lab-key");
        MapProviderCatalog catalog = new MapProviderCatalog(() -> settings, secrets, List.of());
        assertEquals(MapProviderIds.SELFHOSTED_ENGLISH_RASTER, catalog.onlineCandidates().get(0).providerId());
        assertEquals(MapProviderIds.MAPTILER_ENGLISH_RASTER, catalog.onlineCandidates().get(1).providerId());
    }

    @Test
    void selfHostedOnlineIsPreferredWhenConfigured() {
        MapBasemapSettings settings = settings(MapProviderIds.AUTO,
                "http://tiles.example/styles/stellar-en/{z}/{x}/{y}.png");
        MapProviderResolver resolver = resolver(settings, temp.resolve("empty-maps"));
        MapProviderSelection selection = resolver.resolve(true, true, 12, 77, 13, 78);
        assertEquals(MapBasemapMode.ONLINE, selection.mode());
        assertEquals(MapProviderIds.SELFHOSTED_ENGLISH_RASTER, selection.onlineProvider().providerId());
        assertTrue(selection.onlineProvider().supportsEnglishLabels());
        assertEquals(MapBasemapCompliance.ENGLISH_COMPLIANT, selection.compliance());
        assertFalse(selection.onlineProvider().requiresApiKey());
    }

    @Test
    void selfHostedFailureFallsBackToEnglishRasterPmtiles() throws Exception {
        Path maps = englishPack("india-en", """
                supportsEnglishLabels=true
                tileFormat=raster
                boundaryProfile=PROVIDER_DEFAULT
                attribution=© OpenMapTiles © OpenStreetMap
                styleId=stellar-en
                maxZoom=14
                minLatitude=6
                minLongitude=68
                maxLatitude=37
                maxLongitude=97
                """);
        MapBasemapSettings settings = settings(MapProviderIds.SELFHOSTED_ENGLISH_RASTER,
                "http://tiles.example/styles/stellar-en/{z}/{x}/{y}.png");
        MapProviderResolver resolver = resolver(settings, maps);
        MapProviderSelection selection = resolver.afterOnlineFailure(12.9, 77.5, 13.1, 77.7);
        assertEquals(MapBasemapMode.OFFLINE_PMTILES, selection.mode());
        OfflineMapPack pack = selection.offlinePackOptional().orElseThrow();
        assertTrue(pack.supportsEnglishLabels());
        assertEquals(MapBasemapCompliance.ENGLISH_COMPLIANT, selection.compliance());
        assertTrue(pack.rasterCompatible());
        assertEquals("india-en", pack.id());
    }

    @Test
    void pmtilesFailureFallsBackToOsmStandardWhenAllowed() {
        MapBasemapSettings settings = settings(MapProviderIds.SELFHOSTED_ENGLISH_RASTER,
                "http://tiles.example/styles/stellar-en/{z}/{x}/{y}.png");
        MapProviderResolver resolver = resolver(settings, temp.resolve("empty-maps"));
        MapProviderSelection selection = resolver.afterOnlineFailure(12, 77, 13, 78);
        assertEquals(MapBasemapMode.ONLINE, selection.mode());
        assertEquals(MapProviderIds.OSM_CARTO, selection.onlineProvider().providerId());
        assertEquals(MapBasemapCompliance.STANDARD_FALLBACK, selection.compliance());
        assertEquals(MapBasemapUserMessages.STANDARD_FALLBACK, selection.reason());
        assertFalse(selection.englishCompliant());
    }

    @Test
    void pmtilesFailureFallsBackToNeutralWhenStandardFallbackDisabled() {
        MapBasemapSettings settings = settings(MapProviderIds.SELFHOSTED_ENGLISH_RASTER,
                "http://tiles.example/styles/stellar-en/{z}/{x}/{y}.png")
                .withAllowStandardFallback(false);
        MapProviderResolver resolver = resolver(settings, temp.resolve("empty-maps"));
        MapProviderSelection selection = resolver.afterOnlineFailure(12, 77, 13, 78);
        assertEquals(MapBasemapMode.NEUTRAL, selection.mode());
        assertEquals(MapBasemapCompliance.NEUTRAL, selection.compliance());
        assertEquals(MapBasemapUserMessages.MAP_UNAVAILABLE, selection.reason());
    }

    @Test
    void osmIsNeverEnglishCompliantEvenWhenSelectedAsFallback() {
        MapProviderResolver resolver = new MapProviderResolver(
                MapBasemapSettings.defaults(),
                List.of(MapTileProvider.openStreetMap()),
                new OfflineMapPackRegistry(temp.resolve("empty-maps")),
                true);
        MapProviderSelection selection = resolver.resolve(true, true, 12, 77, 13, 78);
        assertEquals(MapProviderIds.OSM_CARTO, selection.onlineProvider().providerId());
        assertFalse(selection.onlineProvider().supportsEnglishLabels());
        assertEquals(MapBasemapCompliance.STANDARD_FALLBACK, selection.compliance());
        assertNotEquals(MapBasemapCompliance.ENGLISH_COMPLIANT, selection.compliance());
    }

    @Test
    void osmIsNeverSelectedInStrictEnglishMode() {
        MapProviderResolver resolver = new MapProviderResolver(
                MapBasemapSettings.defaults().withAllowStandardFallback(false),
                List.of(MapTileProvider.openStreetMap()),
                new OfflineMapPackRegistry(temp.resolve("empty-maps")),
                true);
        MapProviderSelection selection = resolver.resolve(true, true, 12, 77, 13, 78);
        assertEquals(MapBasemapMode.NEUTRAL, selection.mode());
        assertEquals(MapBasemapCompliance.NEUTRAL, selection.compliance());
    }

    @Test
    void englishMetadataIsAccepted() throws Exception {
        Path maps = englishPack("india-en", """
                supportsEnglishLabels=true
                tileFormat=raster
                boundaryProfile=PROVIDER_DEFAULT
                attribution=© OpenMapTiles
                styleId=stellar-en
                maxZoom=12
                """);
        OfflineMapPack pack = new OfflineMapPackRegistry(maps).listEnabled().get(0);
        assertTrue(pack.supportsEnglishLabels());
        assertEquals(OfflineMapTileFormat.RASTER, pack.tileFormat());
        assertEquals(12, pack.maxZoom());
        assertEquals("stellar-en", pack.styleId());
    }

    @Test
    void untaggedLegacyPackIsNotEnglishCompliant() throws Exception {
        Path maps = temp.resolve("maps");
        Files.createDirectories(maps);
        Files.writeString(maps.resolve("legacy.pmtiles"), "not-a-real-archive");
        OfflineMapPack pack = new OfflineMapPackRegistry(maps).listEnabled().get(0);
        assertFalse(pack.supportsEnglishLabels());
        MapProviderResolver resolver = resolver(MapBasemapSettings.defaults(), maps);
        MapProviderSelection selection = resolver.resolve(true, true, 12, 77, 13, 78);
        assertEquals(MapBasemapMode.ONLINE, selection.mode());
        assertEquals(MapProviderIds.OSM_CARTO, selection.onlineProvider().providerId());
        assertEquals(MapBasemapCompliance.STANDARD_FALLBACK, selection.compliance());
    }

    @Test
    void vectorPmtilesAreRejected() throws Exception {
        Path maps = englishPack("vector-en", """
                supportsEnglishLabels=true
                tileFormat=mvt
                boundaryProfile=PROVIDER_DEFAULT
                """);
        MapProviderResolver resolver = resolver(MapBasemapSettings.defaults(), maps);
        MapProviderSelection selection = resolver.afterOnlineFailure(12, 77, 13, 78);
        assertEquals(MapBasemapMode.ONLINE, selection.mode());
        assertEquals(MapProviderIds.OSM_CARTO, selection.onlineProvider().providerId());
        assertEquals(MapBasemapCompliance.STANDARD_FALLBACK, selection.compliance());
        assertEquals(OfflineMapTileFormat.VECTOR,
                new OfflineMapPackRegistry(maps).listEnabled().get(0).tileFormat());
    }

    @Test
    void wrongBoundaryProfileIsRejected() throws Exception {
        Path maps = englishPack("neutral-en", """
                supportsEnglishLabels=true
                tileFormat=raster
                boundaryProfile=GLOBAL_NEUTRAL
                """);
        MapProviderResolver resolver = resolver(MapBasemapSettings.defaults(), maps);
        MapProviderSelection selection = resolver.afterOnlineFailure(12, 77, 13, 78);
        assertEquals(MapBasemapMode.ONLINE, selection.mode());
        assertEquals(MapProviderIds.OSM_CARTO, selection.onlineProvider().providerId());
    }

    @Test
    void outOfCoveragePackIsRejected() throws Exception {
        Path maps = englishPack("uk-en", """
                supportsEnglishLabels=true
                tileFormat=raster
                boundaryProfile=PROVIDER_DEFAULT
                minLatitude=49.8
                minLongitude=-8.2
                maxLatitude=58.7
                maxLongitude=1.8
                """);
        MapProviderResolver resolver = resolver(MapBasemapSettings.defaults(), maps);
        MapProviderSelection selection = resolver.afterOnlineFailure(12.9, 77.5, 13.1, 77.7);
        assertEquals(MapBasemapMode.ONLINE, selection.mode());
        assertEquals(MapProviderIds.OSM_CARTO, selection.onlineProvider().providerId());
        assertEquals(MapBasemapCompliance.STANDARD_FALLBACK, selection.compliance());
    }

    @Test
    void wrongLanguagePackIsRejected() throws Exception {
        Path maps = englishPack("local-script", """
                supportsEnglishLabels=false
                tileFormat=raster
                boundaryProfile=PROVIDER_DEFAULT
                """);
        MapProviderResolver resolver = resolver(MapBasemapSettings.defaults(), maps);
        MapProviderSelection selection = resolver.afterOnlineFailure(12, 77, 13, 78);
        assertEquals(MapBasemapMode.ONLINE, selection.mode());
        assertEquals(MapProviderIds.OSM_CARTO, selection.onlineProvider().providerId());
        assertFalse(selection.englishCompliant());
    }

    private static MapProviderResolver resolver(MapBasemapSettings settings, Path maps) {
        return new MapProviderResolver(
                () -> settings,
                new MapProviderCatalog(() -> settings, MapSecretStore.empty(), List.of()),
                new OfflineMapPackRegistry(maps),
                true);
    }

    private static MapBasemapSettings settings(String provider, String url) {
        return new MapBasemapSettings(
                MapLanguage.ENGLISH,
                MapBoundaryProfile.PROVIDER_DEFAULT,
                provider,
                "",
                url,
                "© OpenMapTiles © OpenStreetMap contributors",
                14);
    }

    private Path englishPack(String id, String sidecar) throws Exception {
        Path maps = temp.resolve("maps-" + id);
        Files.createDirectories(maps);
        Files.writeString(maps.resolve(id + ".pmtiles"), "not-a-real-archive");
        Files.writeString(maps.resolve(id + ".map.properties"), sidecar);
        return maps;
    }
}
