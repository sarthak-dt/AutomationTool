package com.stellar.platform.desktop.ui.graph.map;

/** User-visible Map Route status lines. No HTTP codes, keys, or internal provider IDs. */
public final class MapBasemapUserMessages {

    public static final String LOADING = "Loading map tiles…";
    public static final String STANDARD_FALLBACK =
            "English basemap unavailable — showing standard map labels";
    public static final String STANDARD_FALLBACK_HINT =
            "Configure an English map source in Configuration → Map.";
    public static final String MAP_UNAVAILABLE = "Map unavailable — route remains available";
    public static final String INDIA_OFFICIAL_UNCONFIGURED = "India Official boundary data is not configured.";

    /** @deprecated Use {@link #MAP_UNAVAILABLE} or {@link #STANDARD_FALLBACK}. */
    @Deprecated
    public static final String ENGLISH_UNAVAILABLE = MAP_UNAVAILABLE;
    /** @deprecated Use {@link #MAP_UNAVAILABLE}. */
    @Deprecated
    public static final String PROVIDER_UNAVAILABLE = MAP_UNAVAILABLE;

    private MapBasemapUserMessages() {
    }
}
