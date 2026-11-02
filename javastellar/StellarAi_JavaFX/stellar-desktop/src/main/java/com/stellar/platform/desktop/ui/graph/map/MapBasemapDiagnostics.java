package com.stellar.platform.desktop.ui.graph.map;

import java.util.Locale;

/**
 * Support/development snapshot of the basemap pipeline.
 *
 * <p>Creating a Leaflet tile layer is not evidence that a basemap rendered, so the fields here
 * separate the four failure classes that look identical to a user: the WebView never loaded,
 * Leaflet never initialized, the DOM container has no usable size, or tiles never arrived.
 */
public record MapBasemapDiagnostics(
        String engineLoadState,
        boolean htmlReady,
        boolean leafletReady,
        int webViewWidth,
        int webViewHeight,
        int mapDomWidth,
        int mapDomHeight,
        int leafletInitCount,
        String provider,
        boolean tileLayerPresent,
        boolean firstTileLoaded,
        boolean tileLayerLoadCompleted,
        int tileErrorCount,
        int invalidateSizeRequests,
        String geographicBounds,
        String tileHealth,
        int leafletReadyCallbackCount
) {

    static final int MIN_USABLE_PIXELS = 8;

    public MapBasemapDiagnostics {
        engineLoadState = engineLoadState == null ? "UNKNOWN" : engineLoadState;
        provider = provider == null ? "" : provider;
        geographicBounds = geographicBounds == null ? "" : geographicBounds;
        tileHealth = tileHealth == null ? "" : tileHealth;
    }

    /** True only when a basemap is genuinely on screen, not merely configured. */
    public boolean basemapRendered() {
        return leafletReady && tileLayerPresent && "TILES_HEALTHY".equals(tileHealth) && domSizeValid();
    }

    /** Mirrors the minimum the view itself enforces before it lets Leaflet project anything. */
    public boolean domSizeValid() {
        return mapDomWidth >= MIN_USABLE_PIXELS && mapDomHeight >= MIN_USABLE_PIXELS;
    }

    /** Single log/support line. Never surfaced in the normal production UI. */
    public String describe() {
        return String.format(Locale.ROOT,
                "engine=%s html=%s leaflet=%s webView=%dx%d mapDom=%dx%d inits=%d "
                        + "readyCallbacks=%d provider=%s "
                        + "tileLayer=%s firstTile=%s layerLoaded=%s tileErrors=%d health=%s "
                        + "invalidateRequests=%d bounds=%s",
                engineLoadState, htmlReady, leafletReady,
                webViewWidth, webViewHeight, mapDomWidth, mapDomHeight,
                leafletInitCount, leafletReadyCallbackCount, provider,
                tileLayerPresent, firstTileLoaded, tileLayerLoadCompleted,
                tileErrorCount, tileHealth, invalidateSizeRequests, geographicBounds);
    }
}
