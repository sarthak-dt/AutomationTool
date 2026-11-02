package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.processing.map.MapRouteModel;

/**
 * Equirectangular projection state for the route canvas.
 *
 * <p>Vehicle routes span a few kilometres at most, where an equirectangular projection scaled by
 * cos(latitude) is visually indistinguishable from Mercator while costing no trigonometry per point.
 * Conversions are plain arithmetic so a pan/zoom frame allocates nothing.
 *
 * <p>Zoom is the Web Mercator integer the JavaFX WebView Leaflet path can paint. Fit computes a
 * continuous desired zoom, then floors it so the route stays fully visible. {@link RouteCanvas} and
 * Leaflet both consume {@link MapCamera} derived from that integer — never a private continuous
 * scale beside a snapped Leaflet zoom.
 *
 * <p>The upper zoom limit belongs to the active basemap provider rather than to the canvas: a scale
 * the provider cannot represent would leave the overlay drawing detail the roads beneath it can no
 * longer match.
 */
public final class MapViewport {

    private static final double MIN_PIXELS_PER_DEGREE = 1e-2;

    /**
     * Smallest extent {@link #fit} will frame, about 200 m. A stationary vehicle has no extent at
     * all, and framing zero would ask for an unbounded scale and a meaningless basemap zoom.
     */
    private static final double MIN_FIT_SPAN_DEGREES = 200.0 / 111_320.0;

    private double centerLatitude;
    private double centerLongitude;
    private double pixelsPerDegreeLatitude = 1_000;
    private double width = 1;
    private double height = 1;
    private int providerMaxZoom = MapScale.DEFAULT_MAX_ZOOM;
    private int cameraZoom = 2;

    public MapViewport() {
        applyIntegerZoom(MapScale.webViewSafeZoom(
                MapScale.zoomFor(1_000 * MapScale.cosineCorrection(0)), providerMaxZoom));
    }

    public void resize(double width, double height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    public double width() {
        return width;
    }

    public double height() {
        return height;
    }

    public double centerLatitude() {
        return centerLatitude;
    }

    public double centerLongitude() {
        return centerLongitude;
    }

    /**
     * Limits how far the canvas may zoom in, so the overlay never outruns what the active basemap
     * provider can render. Called whenever the provider changes, including on fallback.
     */
    public void setProviderMaxZoom(int maxZoom) {
        this.providerMaxZoom = Math.max(1, maxZoom);
        applyIntegerZoom(cameraZoom);
    }

    public int providerMaxZoom() {
        return providerMaxZoom;
    }

    /** Shared camera both Leaflet and the route canvas must render. */
    public MapCamera camera() {
        return MapCamera.of(this);
    }

    /** Longitude degrees shrink towards the poles; this keeps the route's aspect ratio honest. */
    public double pixelsPerDegreeLongitude() {
        return pixelsPerDegreeLatitude * MapScale.cosineCorrection(centerLatitude);
    }

    public double pixelsPerDegreeLatitude() {
        return pixelsPerDegreeLatitude;
    }

    /** Integer Web Mercator zoom Leaflet and the canvas both use in JavaFX WebView mode. */
    public int leafletZoom() {
        return cameraZoom;
    }

    public double x(double longitude) {
        return width * 0.5 + (longitude - centerLongitude) * pixelsPerDegreeLongitude();
    }

    public double y(double latitude) {
        return height * 0.5 - (latitude - centerLatitude) * pixelsPerDegreeLatitude;
    }

    public double longitudeAt(double x) {
        return centerLongitude + (x - width * 0.5) / pixelsPerDegreeLongitude();
    }

    public double latitudeAt(double y) {
        return centerLatitude - (y - height * 0.5) / pixelsPerDegreeLatitude;
    }

    /** Latitude degrees per pixel — the natural tolerance unit for route decimation. */
    public double degreesPerPixel() {
        return 1.0 / pixelsPerDegreeLatitude;
    }

    public void panByPixels(double dx, double dy) {
        centerLongitude -= dx / pixelsPerDegreeLongitude();
        centerLatitude += dy / pixelsPerDegreeLatitude;
        refreshScaleFromCamera();
    }

    /** Zooms about a screen anchor so the point under the pointer stays put. */
    public void zoomAt(double factor, double anchorX, double anchorY) {
        int delta = factor > 1.0 ? 1 : factor < 1.0 ? -1 : 0;
        zoomByLevelsAt(delta, anchorX, anchorY);
    }

    public void zoomCentered(double factor) {
        zoomAt(factor, width * 0.5, height * 0.5);
    }

    public void zoomByLevels(int delta) {
        zoomByLevelsAt(delta, width * 0.5, height * 0.5);
    }

    public void zoomByLevelsAt(int delta, double anchorX, double anchorY) {
        double anchorLongitude = longitudeAt(anchorX);
        double anchorLatitude = latitudeAt(anchorY);
        applyIntegerZoom(cameraZoom + delta);
        centerLongitude = anchorLongitude - (anchorX - width * 0.5) / pixelsPerDegreeLongitude();
        centerLatitude = anchorLatitude + (anchorY - height * 0.5) / pixelsPerDegreeLatitude;
        refreshScaleFromCamera();
    }

    /**
     * Frames the whole route with a pixel margin so start/end markers are never clipped.
     *
     * <p>A stationary or near-stationary recording is framed at {@link #MIN_FIT_SPAN_DEGREES} instead
     * of its own extent, which centres the valid GPS location at a readable street zoom rather than
     * demanding a scale no basemap can deliver.
     *
     * <p>The continuous fit zoom is then floored to a WebView-safe integer so Leaflet never CSS-scales
     * tiles. Flooring (not ceiling) keeps the requested extent fully visible.
     */
    public void fit(MapRouteModel route, double marginPixels) {
        if (route == null || route.isEmpty()) {
            return;
        }
        MapViewportCalculator.Bounds bounds = MapViewportCalculator.of(route);
        if (!bounds.present()) {
            return;
        }
        fitBounds(bounds.minLatitude(), bounds.maxLatitude(), bounds.minLongitude(), bounds.maxLongitude(), marginPixels);
    }

    /** Fits every visible independent route segment. Never invents a connecting extent. */
    public void fitAll(Iterable<MapRouteModel> routes, double marginPixels) {
        MapViewportCalculator.Bounds bounds = MapViewportCalculator.of(routes);
        if (!bounds.present()) {
            return;
        }
        fitBounds(bounds.minLatitude(), bounds.maxLatitude(), bounds.minLongitude(), bounds.maxLongitude(), marginPixels);
    }

    /**
     * Frames the driven route so the padded bounds fill a static report image.
     * Unlike {@link #fitAll}, this does not floor the scale to a Leaflet tile zoom.
     * {@code marginPixels} is unused: the margin is {@link MapViewportCalculator#PADDING_FRACTION}.
     */
    public void fitReport(Iterable<MapRouteModel> routes, double marginPixels) {
        MapViewportCalculator.Bounds bounds = MapViewportCalculator.padded(
                MapViewportCalculator.of(routes), MapViewportCalculator.PADDING_FRACTION);
        if (bounds == null || !bounds.present()) {
            return;
        }
        centerLatitude = bounds.centerLatitude();
        centerLongitude = bounds.centerLongitude();
        double cosine = MapScale.cosineCorrection(centerLatitude);
        double latitudeSpan = Math.max(1e-9, bounds.maxLatitude() - bounds.minLatitude());
        double longitudeSpan = Math.max(1e-9, bounds.maxLongitude() - bounds.minLongitude());
        double usableWidth = Math.max(1, width);
        double usableHeight = Math.max(1, height);
        double desired = Math.min(usableHeight / latitudeSpan, usableWidth / (longitudeSpan * cosine));
        if (!(desired > 0) || Double.isNaN(desired)) {
            desired = MIN_PIXELS_PER_DEGREE;
        }
        pixelsPerDegreeLatitude = desired;
        cameraZoom = MapScale.webViewSafeZoom(
                MapScale.zoomFor(desired * cosine), providerMaxZoom);
    }

    private void fitBounds(double minLat, double maxLat, double minLon, double maxLon, double marginPixels) {
        centerLatitude = (minLat + maxLat) * 0.5;
        centerLongitude = (minLon + maxLon) * 0.5;
        double cosine = MapScale.cosineCorrection(centerLatitude);
        double latitudeSpan = Math.max(MIN_FIT_SPAN_DEGREES, maxLat - minLat);
        double longitudeSpan = Math.max(MIN_FIT_SPAN_DEGREES / cosine, maxLon - minLon);
        double usableWidth = Math.max(1, width - 2 * marginPixels);
        double usableHeight = Math.max(1, height - 2 * marginPixels);
        double byLatitude = usableHeight / latitudeSpan;
        double byLongitude = usableWidth / (longitudeSpan * cosine);
        double desiredPpdLatitude = Math.min(byLatitude, byLongitude);
        if (Double.isNaN(desiredPpdLatitude) || desiredPpdLatitude <= 0) {
            desiredPpdLatitude = MIN_PIXELS_PER_DEGREE;
        }
        double desiredPpdLongitude = desiredPpdLatitude * cosine;
        int zoom = MapScale.webViewSafeZoom(MapScale.zoomFor(desiredPpdLongitude), providerMaxZoom);
        applyIntegerZoom(zoom);
    }

    /**
     * Pans just enough to bring {@code (latitude, longitude)} inside the view with a pixel margin.
     * Does not change zoom and never fits the whole route.
     *
     * @return {@code true} when the viewport actually moved
     */
    public boolean reveal(double latitude, double longitude, double marginPixels) {
        double margin = Math.max(0, marginPixels);
        double x = x(longitude);
        double y = y(latitude);
        boolean outside = x < margin || x > width - margin || y < margin || y > height - margin;
        if (!outside) {
            return false;
        }
        double dx = 0;
        double dy = 0;
        if (x < margin) {
            dx = margin - x;
        } else if (x > width - margin) {
            dx = (width - margin) - x;
        }
        if (y < margin) {
            dy = y - margin;
        } else if (y > height - margin) {
            dy = y - (height - margin);
        }
        panByPixels(dx, dy);
        return true;
    }

    void applyIntegerZoom(int zoom) {
        cameraZoom = MapScale.webViewSafeZoom(zoom, providerMaxZoom);
        refreshScaleFromCamera();
    }

    private void refreshScaleFromCamera() {
        double ppdLongitude = MapScale.pixelsPerDegreeLongitudeAt(cameraZoom);
        pixelsPerDegreeLatitude = ppdLongitude / MapScale.cosineCorrection(centerLatitude);
        if (!(pixelsPerDegreeLatitude > 0) || Double.isNaN(pixelsPerDegreeLatitude)) {
            pixelsPerDegreeLatitude = MIN_PIXELS_PER_DEGREE;
        }
    }
}
