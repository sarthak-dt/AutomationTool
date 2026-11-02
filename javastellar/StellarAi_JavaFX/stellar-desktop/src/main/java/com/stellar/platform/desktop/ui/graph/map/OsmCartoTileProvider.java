package com.stellar.platform.desktop.ui.graph.map;

import java.util.Set;

/** OpenStreetMap Carto raster from tile.openstreetmap.org. Labels are not English-primary. */
final class OsmCartoTileProvider implements MapTileProvider {

    static final String DEFAULT_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
    static final String DEFAULT_ATTRIBUTION =
            "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a>";

    @Override
    public String providerId() {
        return MapProviderIds.OSM_CARTO;
    }

    @Override
    public String displayName() {
        return "OpenStreetMap";
    }

    @Override
    public String tileUrlTemplate() {
        String configured = System.getProperty("stellarai.map.tile.url");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return DEFAULT_URL;
    }

    @Override
    public String attributionHtml() {
        String configured = System.getProperty("stellarai.map.tile.attribution");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return DEFAULT_ATTRIBUTION;
    }

    @Override
    public int maxZoom() {
        String configured = System.getProperty("stellarai.map.tile.maxZoom");
        if (configured != null && !configured.isBlank()) {
            try {
                return Math.max(1, Integer.parseInt(configured.trim()));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 19;
    }

    @Override
    public boolean supportsEnglishLabels() {
        return false;
    }

    @Override
    public Set<MapBoundaryProfile> supportedBoundaryProfiles() {
        return Set.of(MapBoundaryProfile.PROVIDER_DEFAULT);
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public String toString() {
        return "OsmCartoTileProvider";
    }
}
