package com.stellar.platform.desktop.ui.graph.map;

/**
 * Visible-tile evidence for the Leaflet basemap. A first successful tile is not a healthy map.
 */
public enum BasemapTileHealth {
    LEAFLET_READY,
    TILES_LOADING,
    TILES_PARTIALLY_LOADED,
    TILES_HEALTHY,
    TILE_PROVIDER_FAILED,
    NEUTRAL_FALLBACK
}
