package com.stellar.platform.desktop.ui.graph.map;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * MapTiler Cloud 256px English raster XYZ. The configured map ID must already be styled with
 * English labels; this class does not switch language at runtime.
 */
public final class MapTilerEnglishRasterProvider implements MapTileProvider {

    public static final String DEFAULT_ATTRIBUTION =
            "&copy; <a href=\"https://www.maptiler.com/copyright/\">MapTiler</a> "
                    + "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap contributors</a>";

    private static final String TILE_TEMPLATE =
            "https://api.maptiler.com/maps/%s/256/{z}/{x}/{y}.png?key=%s";

    private final String mapId;
    private final String apiKey;
    private final String attributionHtml;
    private final int maxZoom;

    MapTilerEnglishRasterProvider(String mapId, String apiKey, String attributionHtml, int maxZoom) {
        this.mapId = Objects.requireNonNull(mapId, "mapId");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.attributionHtml = attributionHtml == null || attributionHtml.isBlank()
                ? DEFAULT_ATTRIBUTION
                : attributionHtml;
        this.maxZoom = maxZoom < 1 ? MapBasemapSettings.DEFAULT_MAX_ZOOM : maxZoom;
    }

    public static Optional<MapTileProvider> tryCreate(MapBasemapSettings settings, MapSecretStore secrets) {
        if (settings == null) {
            return Optional.empty();
        }
        String mapId = sanitizeMapId(settings.englishMapId());
        if (mapId.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> key = secrets == null ? Optional.empty() : secrets.apiKey();
        if (key.isEmpty()) {
            return Optional.empty();
        }
        String attribution = settings.englishAttribution().isBlank()
                ? DEFAULT_ATTRIBUTION
                : settings.englishAttribution();
        return Optional.of(new MapTilerEnglishRasterProvider(
                mapId, key.get(), attribution, settings.englishMaxZoom()));
    }

    static String sanitizeMapId(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.contains("..") || trimmed.contains("/") || trimmed.contains("\\")) {
            return "";
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c > 127 || !(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.')) {
                return "";
            }
        }
        return trimmed;
    }

    @Override
    public String providerId() {
        return MapProviderIds.MAPTILER_ENGLISH_RASTER;
    }

    @Override
    public String displayName() {
        return "MapTiler English raster";
    }

    @Override
    public String tileUrlTemplate() {
        return String.format(Locale.ROOT, TILE_TEMPLATE, mapId, apiKey);
    }

    @Override
    public String attributionHtml() {
        return attributionHtml;
    }

    @Override
    public int maxZoom() {
        return maxZoom;
    }

    @Override
    public boolean supportsEnglishLabels() {
        return true;
    }

    @Override
    public Set<MapBoundaryProfile> supportedBoundaryProfiles() {
        return Set.of(MapBoundaryProfile.PROVIDER_DEFAULT);
    }

    @Override
    public boolean requiresApiKey() {
        return true;
    }

    public String mapId() {
        return mapId;
    }

    @Override
    public String toString() {
        return "MapTilerEnglishRasterProvider{id=" + providerId()
                + ", mapId=" + mapId
                + ", maxZoom=" + maxZoom
                + ", apiKey=" + MapSecretStore.maskConfigured(true) + "}";
    }
}
