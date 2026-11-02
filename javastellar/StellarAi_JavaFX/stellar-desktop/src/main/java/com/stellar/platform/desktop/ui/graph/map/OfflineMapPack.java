package com.stellar.platform.desktop.ui.graph.map;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * One optional offline street-map package (PMTiles) living outside project SQLite.
 *
 * <p>Untagged legacy packs must not claim English-label compliance. Vector archives are not a
 * Leaflet raster fallback.
 */
public record OfflineMapPack(
        String id,
        String displayName,
        Path path,
        String attribution,
        Double minLatitude,
        Double minLongitude,
        Double maxLatitude,
        Double maxLongitude,
        boolean enabled,
        boolean supportsEnglishLabels,
        MapBoundaryProfile boundaryProfile,
        String styleId,
        int maxZoom,
        OfflineMapTileFormat tileFormat
) {
    public OfflineMapPack {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(path, "path");
        attribution = attribution == null || attribution.isBlank()
                ? "© Offline map pack contributors"
                : attribution;
        boundaryProfile = boundaryProfile == null
                ? MapBoundaryProfile.PROVIDER_DEFAULT
                : boundaryProfile;
        styleId = styleId == null ? "" : styleId.trim();
        maxZoom = maxZoom < 1 ? 0 : maxZoom;
        tileFormat = tileFormat == null ? OfflineMapTileFormat.UNKNOWN : tileFormat;
    }

    public static OfflineMapPack untagged(
            String id,
            String displayName,
            Path path,
            String attribution,
            Double minLatitude,
            Double minLongitude,
            Double maxLatitude,
            Double maxLongitude,
            boolean enabled) {
        return new OfflineMapPack(
                id, displayName, path, attribution,
                minLatitude, minLongitude, maxLatitude, maxLongitude,
                enabled, false, MapBoundaryProfile.PROVIDER_DEFAULT, "",
                0, OfflineMapTileFormat.UNKNOWN);
    }

    public Optional<String> coverageSummary() {
        if (minLatitude == null || minLongitude == null || maxLatitude == null || maxLongitude == null) {
            return Optional.empty();
        }
        return Optional.of(String.format(
                java.util.Locale.ROOT,
                "%.2f,%.2f — %.2f,%.2f",
                minLatitude, minLongitude, maxLatitude, maxLongitude));
    }

    public boolean hasCoverageBounds() {
        return minLatitude != null && minLongitude != null && maxLatitude != null && maxLongitude != null;
    }

    /** True when coverage is unknown (treat as usable for any route) or intersects the route. */
    public boolean covers(double south, double west, double north, double east) {
        if (!hasCoverageBounds()) {
            return true;
        }
        return maxLatitude >= south && minLatitude <= north
                && maxLongitude >= west && minLongitude <= east;
    }

    public String providerStyleId() {
        return styleId;
    }

    public boolean rasterCompatible() {
        return tileFormat.rasterCompatible();
    }

    public boolean matchesBoundary(MapBoundaryProfile requested) {
        MapBoundaryProfile want = requested == null
                ? MapBoundaryProfile.PROVIDER_DEFAULT
                : requested;
        MapBoundaryProfile have = boundaryProfile == null
                ? MapBoundaryProfile.PROVIDER_DEFAULT
                : boundaryProfile;
        if (have == want) {
            return true;
        }
        return want != MapBoundaryProfile.PROVIDER_DEFAULT
                && have == MapBoundaryProfile.PROVIDER_DEFAULT;
    }

    public int maxZoomOrDefault() {
        return maxZoom < 1 ? MapBasemapSettings.DEFAULT_MAX_ZOOM : maxZoom;
    }
}
