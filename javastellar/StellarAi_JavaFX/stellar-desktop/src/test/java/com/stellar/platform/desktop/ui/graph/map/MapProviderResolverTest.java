package com.stellar.platform.desktop.ui.graph.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapProviderResolverTest {

    @TempDir
    Path temp;

    @Test
    void prefersEnglishOnlineWhenHealthy() {
        MapProviderResolver resolver = new MapProviderResolver(
                MapBasemapSettings.defaults(),
                List.of(english()),
                new OfflineMapPackRegistry(temp.resolve("maps")),
                true);
        MapProviderSelection selection = resolver.resolve(true, true, 50, -1, 52, 1);
        assertEquals(MapBasemapMode.ONLINE, selection.mode());
        assertTrue(selection.onlineProvider().supportsEnglishLabels());
        assertEquals(MapBasemapCompliance.ENGLISH_COMPLIANT, selection.compliance());
    }

    @Test
    void fallsBackToEnglishOfflineWhenOnlineUnhealthy() throws Exception {
        Path maps = temp.resolve("maps");
        Files.createDirectories(maps);
        Files.writeString(maps.resolve("uk.pmtiles"), "not-a-real-archive");
        Files.writeString(maps.resolve("uk.map.properties"), "supportsEnglishLabels=true\n");
        MapProviderResolver resolver = new MapProviderResolver(
                MapBasemapSettings.defaults(),
                List.of(english()),
                new OfflineMapPackRegistry(maps),
                true);
        MapProviderSelection selection = resolver.afterOnlineFailure(50, -1, 52, 1);
        assertEquals(MapBasemapMode.OFFLINE_PMTILES, selection.mode());
        assertTrue(selection.offlinePackOptional().isPresent());
        assertEquals("uk", selection.offlinePackOptional().orElseThrow().id());
    }

    @Test
    void fallsBackToOsmStandardWithoutEnglishSources() {
        MapProviderResolver resolver = new MapProviderResolver(
                MapBasemapSettings.defaults(),
                List.of(MapTileProvider.openStreetMap()),
                new OfflineMapPackRegistry(temp.resolve("empty-maps")),
                true);
        MapProviderSelection selection = resolver.afterOnlineFailure(50, -1, 52, 1);
        assertEquals(MapBasemapMode.ONLINE, selection.mode());
        assertEquals(MapProviderIds.OSM_CARTO, selection.onlineProvider().providerId());
        assertEquals(MapBasemapCompliance.STANDARD_FALLBACK, selection.compliance());
        assertEquals(MapBasemapUserMessages.STANDARD_FALLBACK, selection.reason());
    }

    @Test
    void fallsBackToNeutralWhenStandardFallbackDisabled() {
        MapProviderResolver resolver = new MapProviderResolver(
                MapBasemapSettings.defaults().withAllowStandardFallback(false),
                List.of(MapTileProvider.openStreetMap()),
                new OfflineMapPackRegistry(temp.resolve("empty-maps")),
                true);
        MapProviderSelection selection = resolver.afterOnlineFailure(50, -1, 52, 1);
        assertEquals(MapBasemapMode.NEUTRAL, selection.mode());
        assertEquals(MapBasemapUserMessages.MAP_UNAVAILABLE, selection.reason());
    }

    @Test
    void projectChangeDoesNotAlterProviderPolicy() {
        MapBasemapSettings settings = MapBasemapSettings.defaults();
        MapProviderResolver resolver = new MapProviderResolver(
                () -> settings,
                new MapProviderCatalog(() -> settings, MapSecretStore.empty(), List.of()),
                new OfflineMapPackRegistry(temp.resolve("empty-maps")),
                true);
        MapProviderSelection first = resolver.resolve(true, true, 12, 77, 13, 78);
        MapProviderSelection second = resolver.resolve(true, true, 19, 72, 20, 73);
        assertEquals(first.mode(), second.mode());
        assertEquals(first.reason(), second.reason());
    }

    private static MapTileProvider english() {
        return new MapTileProvider() {
            @Override public String providerId() { return "TEST_ENGLISH"; }
            @Override public String displayName() { return "test-english"; }
            @Override public String tileUrlTemplate() { return "test://en/{z}/{x}/{y}.png"; }
            @Override public String attributionHtml() { return "© test"; }
            @Override public int maxZoom() { return 19; }
            @Override public boolean supportsEnglishLabels() { return true; }
        };
    }
}
