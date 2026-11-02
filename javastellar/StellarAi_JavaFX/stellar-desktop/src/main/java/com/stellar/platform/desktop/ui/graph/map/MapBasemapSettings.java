package com.stellar.platform.desktop.ui.graph.map;

import java.util.Objects;

/**
 * Workstation map policy. Persisted under Analysis Storage {@code config/}, never per project.
 */
public record MapBasemapSettings(
        MapLanguage language,
        MapBoundaryProfile boundaryProfile,
        String providerPreference,
        String englishMapId,
        String englishTileUrl,
        String englishAttribution,
        int englishMaxZoom,
        boolean allowStandardFallback
) {
    public static final int DEFAULT_MAX_ZOOM = 19;

    public MapBasemapSettings {
        language = language == null ? MapLanguage.defaultLanguage() : language;
        boundaryProfile = boundaryProfile == null
                ? MapBoundaryProfile.defaultProfile()
                : boundaryProfile;
        providerPreference = MapProviderIds.normalizePreference(providerPreference);
        englishMapId = englishMapId == null ? "" : englishMapId.trim();
        englishTileUrl = englishTileUrl == null ? "" : englishTileUrl.trim();
        englishAttribution = englishAttribution == null ? "" : englishAttribution.trim();
        englishMaxZoom = englishMaxZoom < 1 ? DEFAULT_MAX_ZOOM : englishMaxZoom;
    }

    /** Defaults {@link #allowStandardFallback} to {@code true} (pilot / desktop usage). */
    public MapBasemapSettings(
            MapLanguage language,
            MapBoundaryProfile boundaryProfile,
            String providerPreference,
            String englishMapId,
            String englishTileUrl,
            String englishAttribution,
            int englishMaxZoom) {
        this(
                language,
                boundaryProfile,
                providerPreference,
                englishMapId,
                englishTileUrl,
                englishAttribution,
                englishMaxZoom,
                true);
    }

    public static MapBasemapSettings defaults() {
        return new MapBasemapSettings(
                MapLanguage.ENGLISH,
                MapBoundaryProfile.PROVIDER_DEFAULT,
                MapProviderIds.AUTO,
                "",
                "",
                "",
                DEFAULT_MAX_ZOOM,
                true);
    }

    public String selfHostedUrl() {
        return englishTileUrl;
    }

    public String selfHostedAttribution() {
        return englishAttribution;
    }

    public int selfHostedMaxZoom() {
        return englishMaxZoom;
    }

    public MapBoundaryProfile tileMatchingProfile() {
        return boundaryProfile.forTileMatching();
    }

    public boolean prefersAuto() {
        return MapProviderIds.AUTO.equalsIgnoreCase(providerPreference);
    }

    public boolean prefers(String providerId) {
        return providerId != null && providerId.equalsIgnoreCase(providerPreference);
    }

    @Override
    public String toString() {
        return "MapBasemapSettings{language=" + language
                + ", boundary=" + boundaryProfile
                + ", provider=" + providerPreference
                + ", englishMapId=" + englishMapId
                + ", englishTileUrl=" + MapSecretStore.redactForLog(englishTileUrl)
                + ", maxZoom=" + englishMaxZoom
                + ", allowStandardFallback=" + allowStandardFallback + "}";
    }

    public MapBasemapSettings withBoundaryProfile(MapBoundaryProfile profile) {
        return new MapBasemapSettings(
                language,
                Objects.requireNonNullElse(profile, MapBoundaryProfile.PROVIDER_DEFAULT),
                providerPreference,
                englishMapId,
                englishTileUrl,
                englishAttribution,
                englishMaxZoom,
                allowStandardFallback);
    }

    public MapBasemapSettings withAllowStandardFallback(boolean value) {
        return new MapBasemapSettings(
                language,
                boundaryProfile,
                providerPreference,
                englishMapId,
                englishTileUrl,
                englishAttribution,
                englishMaxZoom,
                value);
    }
}
