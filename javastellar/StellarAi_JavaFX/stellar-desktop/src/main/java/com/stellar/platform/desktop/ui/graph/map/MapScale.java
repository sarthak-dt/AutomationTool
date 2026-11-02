package com.stellar.platform.desktop.ui.graph.map;

/**
 * Conversion between a continuous desired Web Mercator zoom and the integer zoom JavaFX WebView
 * Leaflet can actually paint.
 *
 * <p>{@link MapCamera} is the shared authority: Leaflet and {@code RouteCanvas} both render the
 * integer zoom {@link #webViewSafeZoom} selected from the route's desired continuous fit. The
 * canvas must not keep a private fractional scale while Leaflet snaps — that is the QA-01 mismatch.
 * Flooring the desired zoom (rather than rounding or ceiling) keeps the requested route extent
 * fully visible; a slightly larger geographic area is expected and acceptable.
 *
 * <p>At zoom {@code z} Web Mercator spans 360° of longitude across {@code 256 · 2^z} pixels, and the
 * horizontal scale does not depend on latitude. The canvas uses an equirectangular projection whose
 * longitude scale is already cosine-corrected, so equating the two longitude scales is what makes
 * both projections agree — the latitude scales then follow, because Mercator's vertical scale at
 * latitude &phi; is exactly its horizontal scale divided by cos &phi;, which is how the canvas
 * relates its own two axes.
 */
public final class MapScale {

    /** Leaflet's tile size in CSS pixels. */
    public static final double TILE_PIXELS = 256.0;

    /** World view; below this Leaflet has nothing further to show. */
    public static final double MIN_ZOOM = 1.0;

    /** Street basemaps stop here, so the canvas must not out-zoom it either. */
    public static final int DEFAULT_MAX_ZOOM = 19;

    private MapScale() {
    }

    /** Continuous Web Mercator zoom whose horizontal scale equals the supplied canvas scale. */
    public static double zoomFor(double pixelsPerDegreeLongitude) {
        if (!(pixelsPerDegreeLongitude > 0) || !Double.isFinite(pixelsPerDegreeLongitude)) {
            return MIN_ZOOM;
        }
        return log2(pixelsPerDegreeLongitude * 360.0 / TILE_PIXELS);
    }

    /** Horizontal scale Leaflet actually adopts at {@code zoom}; the inverse of {@link #zoomFor}. */
    public static double pixelsPerDegreeLongitudeAt(double zoom) {
        return TILE_PIXELS * Math.pow(2, zoom) / 360.0;
    }

    /**
     * JavaFX WebView cannot paint Leaflet's CSS {@code scale()} on a fractional zoom. Floor the
     * desired continuous zoom so the route stays fully inside the viewport, then clamp to the
     * provider.
     */
    public static int webViewSafeZoom(double desiredContinuousZoom, int providerMaxZoom) {
        int maximum = Math.max((int) MIN_ZOOM, providerMaxZoom);
        if (!Double.isFinite(desiredContinuousZoom)) {
            return (int) MIN_ZOOM;
        }
        int zoom = (int) Math.floor(desiredContinuousZoom + 1e-9);
        if (zoom < (int) MIN_ZOOM) {
            return (int) MIN_ZOOM;
        }
        return Math.min(maximum, zoom);
    }

    /**
     * Largest latitude scale the canvas may use before it out-zooms the active provider.
     *
     * <p>Longitude degrees shrink towards the poles, so the same tile zoom permits a larger latitude
     * scale at higher latitude; the cosine term is the same one the canvas applies to its own
     * longitude axis.
     */
    public static double maxPixelsPerDegreeLatitude(int providerMaxZoom, double centerLatitude) {
        double cosine = cosineCorrection(centerLatitude);
        return pixelsPerDegreeLongitudeAt(Math.max(MIN_ZOOM, providerMaxZoom)) / cosine;
    }

    /** Cosine correction shared by the canvas projection and the zoom limit, floored for the poles. */
    public static double cosineCorrection(double latitude) {
        return Math.max(0.05, Math.cos(Math.toRadians(latitude)));
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }
}
