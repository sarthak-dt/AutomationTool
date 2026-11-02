package com.stellar.platform.desktop.ui.graph.map;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Organization-hosted English raster XYZ (OpenMapTiles / TileServer GL). No cloud API key.
 *
 * <p>The URL is entirely external configuration. Localhost is a development example only and is
 * never the production default.
 */
public final class SelfHostedEnglishRasterProvider implements MapTileProvider {

    public static final int TILE_PIXEL_SIZE = 256;

    public static final String DEFAULT_ATTRIBUTION =
            "&copy; <a href=\"https://openmaptiles.org/\">OpenMapTiles</a> "
                    + "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap contributors</a>";

    private final String tileUrlTemplate;
    private final String attributionHtml;
    private final int maxZoom;

    SelfHostedEnglishRasterProvider(String tileUrlTemplate, String attributionHtml, int maxZoom) {
        this.tileUrlTemplate = tileUrlTemplate;
        this.attributionHtml = attributionHtml == null || attributionHtml.isBlank()
                ? DEFAULT_ATTRIBUTION
                : attributionHtml;
        this.maxZoom = maxZoom < 1 ? MapBasemapSettings.DEFAULT_MAX_ZOOM : maxZoom;
    }

    public static Optional<MapTileProvider> tryCreate(MapBasemapSettings settings) {
        if (settings == null) {
            return Optional.empty();
        }
        Optional<String> url = normalizeTileUrl(settings.selfHostedUrl());
        if (url.isEmpty()) {
            return Optional.empty();
        }
        String attribution = settings.selfHostedAttribution().isBlank()
                ? DEFAULT_ATTRIBUTION
                : settings.selfHostedAttribution();
        return Optional.of(new SelfHostedEnglishRasterProvider(
                url.get(), attribution, settings.selfHostedMaxZoom()));
    }

    /**
     * 256×256 XYZ template over HTTP(S). Rejects cloud-key placeholders and retina (@2x) URLs.
     */
    public static Optional<String> normalizeTileUrl(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
            return Optional.empty();
        }
        if (lower.contains("{key}") || MapSecretStore.looksLikeKeyQuery(trimmed)) {
            return Optional.empty();
        }
        if (lower.contains("@2x") || lower.contains("tilesize=512")) {
            return Optional.empty();
        }
        if (!trimmed.contains("{z}") || !trimmed.contains("{x}") || !trimmed.contains("{y}")) {
            return Optional.empty();
        }
        try {
            URI.create(trimmed.replace("{z}", "0").replace("{x}", "0").replace("{y}", "0"));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }

    @Override
    public String providerId() {
        return MapProviderIds.SELFHOSTED_ENGLISH_RASTER;
    }

    @Override
    public String displayName() {
        return "Self-hosted English raster";
    }

    @Override
    public String tileUrlTemplate() {
        return tileUrlTemplate;
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
    public int tilePixelSize() {
        return TILE_PIXEL_SIZE;
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
        return false;
    }

    @Override
    public String toString() {
        return "SelfHostedEnglishRasterProvider{url=" + MapSecretStore.redactForLog(tileUrlTemplate)
                + ", maxZoom=" + maxZoom
                + ", tileSize=" + TILE_PIXEL_SIZE + "}";
    }
}
