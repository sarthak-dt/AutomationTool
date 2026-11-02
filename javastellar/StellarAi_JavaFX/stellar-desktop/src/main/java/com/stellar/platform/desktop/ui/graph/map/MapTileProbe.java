package com.stellar.platform.desktop.ui.graph.map;

import java.util.Locale;

/**
 * Opt-in Map Route tile diagnostics. Unset property = production. Never a default provider.
 *
 * <p>{@code stellar.map.tileProbe} values: {@code audit}, {@code local}, {@code 2d},
 * {@code 2d-local}, {@code 2d-osm}, {@code grid-local}, {@code grid-osm}.
 */
final class MapTileProbe {

    private MapTileProbe() {
    }

    static String mode() {
        String configured = System.getProperty("stellar.map.tileProbe", "");
        return configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
    }

    static boolean enabled() {
        return !mode().isEmpty();
    }

    static boolean suppressFallback() {
        return enabled();
    }

    static boolean use2dPositioning() {
        return mode().startsWith("2d");
    }

    static boolean leafletLocalTiles() {
        String mode = mode();
        return "local".equals(mode) || "2d".equals(mode) || "2d-local".equals(mode);
    }

    static boolean gridLocal() {
        return "grid-local".equals(mode());
    }

    static boolean gridOsm() {
        return "grid-osm".equals(mode());
    }

    static boolean grid() {
        return gridLocal() || gridOsm();
    }
}
