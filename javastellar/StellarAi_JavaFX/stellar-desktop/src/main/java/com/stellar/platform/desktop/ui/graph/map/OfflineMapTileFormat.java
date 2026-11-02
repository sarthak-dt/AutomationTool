package com.stellar.platform.desktop.ui.graph.map;

import java.util.Locale;

/**
 * Leaflet Map Route renders raster tiles only. Vector (MVT) archives are not a compatible fallback.
 */
public enum OfflineMapTileFormat {
    RASTER,
    VECTOR,
    UNKNOWN;

    public boolean rasterCompatible() {
        return this != VECTOR;
    }

    public static OfflineMapTileFormat fromSidecar(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "raster", "png", "jpeg", "jpg", "webp", "raster_png", "raster_jpeg", "raster_webp" -> RASTER;
            case "vector", "mvt", "pbf", "mapbox-vector" -> VECTOR;
            default -> UNKNOWN;
        };
    }
}
