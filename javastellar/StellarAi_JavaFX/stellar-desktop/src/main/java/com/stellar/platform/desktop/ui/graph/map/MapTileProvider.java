package com.stellar.platform.desktop.ui.graph.map;

import java.util.Set;

/**
 * Replaceable street-tile configuration for Map Route.
 *
 * <p>No API keys belong in source. Capability flags live on the provider so controllers do not
 * duplicate English / boundary policy.
 */
public interface MapTileProvider {

    String displayName();

    /** Leaflet-compatible template, e.g. {@code https://tile.openstreetmap.org/{z}/{x}/{y}.png}. */
    String tileUrlTemplate();

    String attributionHtml();

    int maxZoom();

    default String providerId() {
        return "UNKNOWN";
    }

    default boolean supportsEnglishLabels() {
        return false;
    }

    default Set<MapBoundaryProfile> supportedBoundaryProfiles() {
        return Set.of(MapBoundaryProfile.PROVIDER_DEFAULT);
    }

    default boolean requiresApiKey() {
        return false;
    }

    /** Leaflet raster tiles are 256×256 XYZ. Do not switch to 512 / @2x in this architecture. */
    default int tilePixelSize() {
        return 256;
    }

    default boolean supportsBoundaryProfile(MapBoundaryProfile profile) {
        MapBoundaryProfile requested = profile == null
                ? MapBoundaryProfile.PROVIDER_DEFAULT
                : profile;
        return supportedBoundaryProfiles().contains(requested);
    }

    /** OSM Carto raster — local-script labels. Not English-compliant. */
    static MapTileProvider openStreetMap() {
        return new OsmCartoTileProvider();
    }
}
