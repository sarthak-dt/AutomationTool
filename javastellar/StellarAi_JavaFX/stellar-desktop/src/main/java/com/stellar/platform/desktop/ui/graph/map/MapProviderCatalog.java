package com.stellar.platform.desktop.ui.graph.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Online English-capable (and diagnostic) tile providers available to {@link MapProviderResolver}.
 */
public final class MapProviderCatalog {

    private final Supplier<MapBasemapSettings> settings;
    private final MapSecretStore secrets;
    private final List<MapTileProvider> extra;

    public MapProviderCatalog(
            Supplier<MapBasemapSettings> settings,
            MapSecretStore secrets,
            List<MapTileProvider> extra) {
        this.settings = settings == null ? MapBasemapSettings::defaults : settings;
        this.secrets = secrets == null ? MapSecretStore.empty() : secrets;
        this.extra = extra == null ? List.of() : List.copyOf(extra);
    }

    public static MapProviderCatalog production() {
        return new MapProviderCatalog(
                () -> MapBasemapSettingsStore.production().load(),
                MapSecretStore.production(),
                List.of());
    }

    public List<MapTileProvider> onlineCandidates() {
        MapBasemapSettings current = Objects.requireNonNullElse(settings.get(), MapBasemapSettings.defaults());
        List<MapTileProvider> candidates = new ArrayList<>();
        SelfHostedEnglishRasterProvider.tryCreate(current).ifPresent(candidates::add);
        MapTilerEnglishRasterProvider.tryCreate(current, secrets).ifPresent(candidates::add);
        candidates.addAll(extra);
        candidates.add(MapTileProvider.openStreetMap());
        return List.copyOf(candidates);
    }
}
