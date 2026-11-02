package com.stellar.platform.desktop.ui.graph.map;

/** Stable provider identifiers for settings and diagnostics. Not shown as raw enums in Map Route. */
public final class MapProviderIds {

    public static final String AUTO = "AUTO";
    public static final String OSM_CARTO = "OSM_CARTO";
    public static final String MAPTILER_ENGLISH_RASTER = "MAPTILER_ENGLISH_RASTER";
    public static final String SELFHOSTED_ENGLISH_RASTER = "SELFHOSTED_ENGLISH_RASTER";
    public static final String OFFLINE_ENGLISH_RASTER_PMTILES = "OFFLINE_ENGLISH_RASTER_PMTILES";

    private MapProviderIds() {
    }

    public static String normalizePreference(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        return raw.trim();
    }

    /** Configuration combo labels. Internal ids are never shown in Map Route. */
    public static String mapSourceLabel(String id) {
        if (id == null) {
            return "";
        }
        return switch (id) {
            case MAPTILER_ENGLISH_RASTER -> "MapTiler English";
            case SELFHOSTED_ENGLISH_RASTER -> "Self-hosted English";
            default -> "Auto";
        };
    }
}
