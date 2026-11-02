package com.stellar.platform.desktop.ui.graph.map;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Offline default backdrop: a neutral graticule, no network access and no tile cache.
 *
 * <p>Grid spacing follows zoom so the canvas reads as a map rather than an empty panel, while the
 * whole thing costs a handful of line draws per frame.
 */
public final class NeutralMapBackground implements MapBackgroundProvider {

    private static final Color SURFACE = MapPalette.NEUTRAL_SURFACE;
    private static final Color GRID = MapPalette.NEUTRAL_GRID;
    private static final Color GRID_MAJOR = MapPalette.NEUTRAL_GRID_MAJOR;
    private static final double TARGET_GRID_PIXELS = 90;

    @Override
    public void paint(GraphicsContext gc, MapViewport viewport) {
        double width = viewport.width();
        double height = viewport.height();
        gc.setFill(SURFACE);
        gc.fillRect(0, 0, width, height);

        double step = niceStep(viewport.degreesPerPixel() * TARGET_GRID_PIXELS);
        gc.setLineWidth(1);

        double topLatitude = viewport.latitudeAt(0);
        double bottomLatitude = viewport.latitudeAt(height);
        for (double lat = Math.ceil(bottomLatitude / step) * step; lat <= topLatitude; lat += step) {
            double y = Math.floor(viewport.y(lat)) + 0.5;
            gc.setStroke(isMajor(lat, step) ? GRID_MAJOR : GRID);
            gc.strokeLine(0, y, width, y);
        }

        double leftLongitude = viewport.longitudeAt(0);
        double rightLongitude = viewport.longitudeAt(width);
        for (double lon = Math.ceil(leftLongitude / step) * step; lon <= rightLongitude; lon += step) {
            double x = Math.floor(viewport.x(lon)) + 0.5;
            gc.setStroke(isMajor(lon, step) ? GRID_MAJOR : GRID);
            gc.strokeLine(x, 0, x, height);
        }
    }

    @Override
    public String displayName() {
        return "Offline grid";
    }

    private static boolean isMajor(double value, double step) {
        return Math.abs(Math.IEEEremainder(value, step * 5)) < step * 0.1;
    }

    /** Snaps the grid to 1/2/5×10^n so labels and spacing stay stable while zooming. */
    private static double niceStep(double raw) {
        if (!(raw > 0)) {
            return 0.01;
        }
        double exponent = Math.floor(Math.log10(raw));
        double magnitude = Math.pow(10, exponent);
        double normalized = raw / magnitude;
        double factor = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
        return factor * magnitude;
    }
}
