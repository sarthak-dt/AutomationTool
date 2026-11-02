package com.stellar.platform.desktop.ui.graph.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Resolves English-compliant providers first, then a usable non-English OSM fallback, then Neutral.
 *
 * <p>OSM Carto is never {@link MapBasemapCompliance#ENGLISH_COMPLIANT}.
 */
public final class MapProviderResolver {

    private static final Logger LOG = Logger.getLogger(MapProviderResolver.class.getName());

    private final Supplier<MapBasemapSettings> settings;
    private final MapProviderCatalog catalog;
    private final OfflineMapPackRegistry offlineRegistry;
    private final boolean preferOnline;

    public MapProviderResolver() {
        this(
                () -> MapBasemapSettingsStore.production().load(),
                MapProviderCatalog.production(),
                new OfflineMapPackRegistry(),
                true);
    }

    public MapProviderResolver(
            Supplier<MapBasemapSettings> settings,
            MapProviderCatalog catalog,
            OfflineMapPackRegistry offlineRegistry,
            boolean preferOnline) {
        this.settings = settings == null ? MapBasemapSettings::defaults : settings;
        this.catalog = catalog == null ? MapProviderCatalog.production() : catalog;
        this.offlineRegistry = offlineRegistry == null ? new OfflineMapPackRegistry() : offlineRegistry;
        this.preferOnline = preferOnline;
    }

    /**
     * Test constructor: explicit online candidates plus settings.
     */
    public MapProviderResolver(
            MapBasemapSettings settings,
            List<MapTileProvider> onlineCandidates,
            OfflineMapPackRegistry offlineRegistry,
            boolean preferOnline) {
        this(
                () -> settings == null ? MapBasemapSettings.defaults() : settings,
                new MapProviderCatalog(
                        () -> settings == null ? MapBasemapSettings.defaults() : settings,
                        MapSecretStore.empty(),
                        onlineCandidates == null ? List.of() : onlineCandidates),
                offlineRegistry,
                preferOnline);
    }

    /**
     * @param onlineHealthy false when the active online tile provider has failed beyond threshold
     * @param hasRouteBounds false when no GPS route exists yet (still may show online world map)
     */
    public MapProviderSelection resolve(
            boolean onlineHealthy,
            boolean hasRouteBounds,
            double south,
            double west,
            double north,
            double east) {
        boolean tryEnglishOnline = preferOnline && onlineHealthy;
        boolean tryOsm = preferOnline && onlineHealthy;
        return resolveInternal(tryEnglishOnline, true, tryOsm, hasRouteBounds, south, west, north, east);
    }

    /**
     * English online failed (or unknown). Matching English PMTiles still win; OSM is the next
     * usable fallback unless the failed provider was already OSM.
     */
    public MapProviderSelection afterOnlineFailure(double south, double west, double north, double east) {
        return afterOnlineFailure(null, south, west, north, east);
    }

    public MapProviderSelection afterOnlineFailure(
            String failedProviderId,
            double south,
            double west,
            double north,
            double east) {
        boolean osmFailed = MapProviderIds.OSM_CARTO.equalsIgnoreCase(failedProviderId);
        return resolveInternal(false, true, !osmFailed, true, south, west, north, east);
    }

    /** English online and English PMTiles both failed. OSM remains if the policy allows it. */
    public MapProviderSelection afterOfflineFailure(double south, double west, double north, double east) {
        return resolveInternal(false, false, true, true, south, west, north, east);
    }

    private MapProviderSelection resolveInternal(
            boolean tryEnglishOnline,
            boolean tryEnglishOffline,
            boolean tryOsmFallback,
            boolean hasRouteBounds,
            double south,
            double west,
            double north,
            double east) {
        MapBasemapSettings current = Objects.requireNonNullElse(settings.get(), MapBasemapSettings.defaults());
        boolean englishRequired = current.language() == MapLanguage.ENGLISH;
        MapBoundaryProfile profile = current.tileMatchingProfile();
        if (tryEnglishOnline) {
            Optional<MapTileProvider> online = selectEnglishOnline(current, profile, englishRequired);
            if (online.isPresent()) {
                LOG.fine(() -> "Map provider ONLINE · ENGLISH_COMPLIANT · " + online.get().providerId()
                        + " · " + online.get().displayName());
                return MapProviderSelection.online(online.get());
            }
        }
        if (tryEnglishOffline) {
            Optional<OfflineMapPack> pack = selectOffline(
                    englishRequired, profile, hasRouteBounds, south, west, north, east);
            if (pack.isPresent()) {
                LOG.fine(() -> "Map provider OFFLINE_PMTILES · " + pack.get().id());
                return MapProviderSelection.offline(pack.get());
            }
        }
        if (tryOsmFallback && current.allowStandardFallback()) {
            Optional<MapTileProvider> osm = selectOsm(profile);
            if (osm.isPresent()) {
                LOG.fine(() -> "Map provider ONLINE · STANDARD_FALLBACK · " + osm.get().providerId());
                return MapProviderSelection.standardOnline(osm.get());
            }
        }
        LOG.fine(() -> "Map provider NEUTRAL · " + MapBasemapUserMessages.MAP_UNAVAILABLE);
        return MapProviderSelection.neutral(MapBasemapUserMessages.MAP_UNAVAILABLE);
    }

    private Optional<MapTileProvider> selectEnglishOnline(
            MapBasemapSettings current,
            MapBoundaryProfile profile,
            boolean englishRequired) {
        List<MapTileProvider> ranked = rankEnglish(current, catalog.onlineCandidates(), englishRequired, profile);
        if (ranked.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ranked.get(0));
    }

    private Optional<MapTileProvider> selectOsm(MapBoundaryProfile profile) {
        for (MapTileProvider candidate : catalog.onlineCandidates()) {
            if (candidate == null || !MapProviderIds.OSM_CARTO.equals(candidate.providerId())) {
                continue;
            }
            if (candidate.supportsEnglishLabels()) {
                continue;
            }
            if (candidate.supportsBoundaryProfile(profile)
                    || (profile != MapBoundaryProfile.PROVIDER_DEFAULT
                    && candidate.supportsBoundaryProfile(MapBoundaryProfile.PROVIDER_DEFAULT))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private List<MapTileProvider> rankEnglish(
            MapBasemapSettings current,
            List<MapTileProvider> candidates,
            boolean englishRequired,
            MapBoundaryProfile profile) {
        List<MapTileProvider> exact = new ArrayList<>();
        List<MapTileProvider> defaultProfile = new ArrayList<>();
        for (MapTileProvider candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (MapProviderIds.OSM_CARTO.equals(candidate.providerId())) {
                continue;
            }
            if (englishRequired && !candidate.supportsEnglishLabels()) {
                continue;
            }
            if (!current.prefersAuto() && !current.prefers(candidate.providerId())) {
                continue;
            }
            if (candidate.supportsBoundaryProfile(profile)) {
                exact.add(candidate);
            } else if (profile != MapBoundaryProfile.PROVIDER_DEFAULT
                    && candidate.supportsBoundaryProfile(MapBoundaryProfile.PROVIDER_DEFAULT)) {
                defaultProfile.add(candidate);
            }
        }
        if (!exact.isEmpty()) {
            return exact;
        }
        return defaultProfile;
    }

    private Optional<OfflineMapPack> selectOffline(
            boolean englishRequired,
            MapBoundaryProfile profile,
            boolean hasRouteBounds,
            double south,
            double west,
            double north,
            double east) {
        List<OfflineMapPack> enabled = offlineRegistry.listEnabled();
        List<OfflineMapPack> matching = new ArrayList<>();
        for (OfflineMapPack pack : enabled) {
            if (!pack.rasterCompatible()) {
                continue;
            }
            if (englishRequired && !pack.supportsEnglishLabels()) {
                continue;
            }
            if (!pack.matchesBoundary(profile)) {
                continue;
            }
            matching.add(pack);
        }
        if (hasRouteBounds) {
            for (OfflineMapPack pack : matching) {
                if (pack.covers(south, west, north, east)) {
                    return Optional.of(pack);
                }
            }
            return Optional.empty();
        }
        return matching.stream().findFirst();
    }
}
