package com.stellar.platform.desktop.ui.graph.map;

/**
 * The geographic camera both Leaflet and {@link RouteCanvas} render.
 *
 * <p>In the JavaFX WebView the zoom is a discrete Web Mercator integer so Leaflet never applies a
 * CSS {@code scale()} that WebKit fails to paint. The canvas derives its pixels-per-degree from
 * the same integer; it must not keep a private continuous scale.
 */
public record MapCamera(
        double centerLatitude,
        double centerLongitude,
        int zoom,
        double viewportWidth,
        double viewportHeight,
        int providerMaxZoom
) {

    public static MapCamera of(MapViewport viewport) {
        if (viewport == null) {
            return new MapCamera(0, 0, (int) MapScale.MIN_ZOOM, 1, 1, MapScale.DEFAULT_MAX_ZOOM);
        }
        return new MapCamera(
                viewport.centerLatitude(),
                viewport.centerLongitude(),
                viewport.leafletZoom(),
                viewport.width(),
                viewport.height(),
                viewport.providerMaxZoom());
    }
}
