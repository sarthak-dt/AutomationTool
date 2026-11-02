package com.stellar.platform.desktop.ui.graph.map;

import java.util.Objects;
import java.util.Optional;

/** Result of {@link MapProviderResolver}. */
public record MapProviderSelection(
        MapBasemapMode mode,
        MapTileProvider onlineProvider,
        OfflineMapPack offlinePack,
        String reason,
        MapBasemapCompliance compliance
) {
    public MapProviderSelection {
        Objects.requireNonNull(mode, "mode");
        reason = reason == null ? "" : reason;
        compliance = compliance == null ? MapBasemapCompliance.NEUTRAL : compliance;
        if (mode == MapBasemapMode.NEUTRAL) {
            compliance = MapBasemapCompliance.NEUTRAL;
        }
    }

    public Optional<OfflineMapPack> offlinePackOptional() {
        return Optional.ofNullable(offlinePack);
    }

    public boolean englishCompliant() {
        return compliance == MapBasemapCompliance.ENGLISH_COMPLIANT;
    }

    public boolean standardFallback() {
        return compliance == MapBasemapCompliance.STANDARD_FALLBACK;
    }

    public static MapProviderSelection online(MapTileProvider provider) {
        if (provider != null && provider.supportsEnglishLabels()) {
            return new MapProviderSelection(
                    MapBasemapMode.ONLINE, provider, null, "Online street tiles",
                    MapBasemapCompliance.ENGLISH_COMPLIANT);
        }
        return standardOnline(provider);
    }

    public static MapProviderSelection standardOnline(MapTileProvider provider) {
        return new MapProviderSelection(
                MapBasemapMode.ONLINE,
                provider,
                null,
                MapBasemapUserMessages.STANDARD_FALLBACK,
                MapBasemapCompliance.STANDARD_FALLBACK);
    }

    public static MapProviderSelection offline(OfflineMapPack pack) {
        boolean english = pack != null && pack.supportsEnglishLabels();
        return new MapProviderSelection(
                MapBasemapMode.OFFLINE_PMTILES,
                null,
                pack,
                english
                        ? "Offline PMTiles · " + pack.displayName()
                        : MapBasemapUserMessages.STANDARD_FALLBACK,
                english ? MapBasemapCompliance.ENGLISH_COMPLIANT : MapBasemapCompliance.STANDARD_FALLBACK);
    }

    public static MapProviderSelection neutral(String reason) {
        String text = reason == null || reason.isBlank()
                ? MapBasemapUserMessages.MAP_UNAVAILABLE
                : reason;
        return new MapProviderSelection(
                MapBasemapMode.NEUTRAL, null, null, text, MapBasemapCompliance.NEUTRAL);
    }
}
