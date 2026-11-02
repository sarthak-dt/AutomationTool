package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.processing.map.GeoDistance;
import com.stellar.platform.processing.map.MapRouteModel;

/**
 * Geographic frame shared by the live Map Route view and the HTML report image.
 *
 * <p>Bounds come only from the route being shown. A single GPS sample isolated by a gap is not
 * part of the driven route, so it does not pull the frame out to a world view. The report then
 * pads that frame by {@link #PADDING_FRACTION} on each side and fills the image with the padded
 * frame. There is no default world, country, or provider viewport.
 */
public final class MapViewportCalculator {

    /** Geographic padding on each side of the driven route. Within the 5–10% report margin. */
    public static final double PADDING_FRACTION = 0.08;

    /**
     * A GPS-gap run farther than this from the main drive is not part of the framed route.
     * Real legs of one recording stay inside; a handful of samples near longitude 0 do not.
     */
    private static final double NEAR_RUN_METERS = 40_000;

    /** A second run this large is a real leg even when it sits far from the first. */
    private static final double SUBSTANTIAL_RUN_FRACTION = 0.05;

    /**
     * Smallest extent a fit will frame, about 200 m. A stationary vehicle has no extent, and
     * framing zero would ask for an unbounded scale.
     */
    static final double MIN_FIT_SPAN_DEGREES = 200.0 / 111_320.0;

    private MapViewportCalculator() {
    }

    public record Bounds(double minLatitude, double maxLatitude, double minLongitude, double maxLongitude) {
        public boolean present() {
            return Double.isFinite(minLatitude) && Double.isFinite(maxLatitude)
                    && Double.isFinite(minLongitude) && Double.isFinite(maxLongitude)
                    && maxLatitude >= minLatitude && maxLongitude >= minLongitude;
        }

        public double centerLatitude() {
            return (minLatitude + maxLatitude) * 0.5;
        }

        public double centerLongitude() {
            return (minLongitude + maxLongitude) * 0.5;
        }
    }

    /** First sample of the framed route, or {@code -1} when the model is empty. */
    public static int firstIncluded(MapRouteModel model) {
        boolean[] included = included(model);
        if (included == null) {
            return -1;
        }
        for (int index = 0; index < included.length; index++) {
            if (included[index]) {
                return index;
            }
        }
        return 0;
    }

    /** Last sample of the framed route, or {@code -1} when the model is empty. */
    public static int lastIncluded(MapRouteModel model) {
        boolean[] included = included(model);
        if (included == null) {
            return -1;
        }
        for (int index = included.length - 1; index >= 0; index--) {
            if (included[index]) {
                return index;
            }
        }
        return model.size() - 1;
    }

    public static Bounds of(MapRouteModel route) {
        return of(route == null ? null : java.util.List.of(route));
    }

    public static Bounds of(Iterable<MapRouteModel> routes) {
        Accumulator accumulator = new Accumulator();
        if (routes != null) {
            for (MapRouteModel route : routes) {
                accumulate(route, accumulator);
            }
        }
        return accumulator.bounds();
    }

    /**
     * Expands the driven frame by {@code fraction} of its span on every side.
     * A span below the readable minimum is raised first, then padded.
     */
    public static Bounds padded(Bounds raw, double fraction) {
        if (raw == null || !raw.present()) {
            return raw;
        }
        double pad = fraction > 0 && Double.isFinite(fraction) ? fraction : 0;
        double centerLatitude = raw.centerLatitude();
        double cosine = MapScale.cosineCorrection(centerLatitude);
        double latitudeSpan = Math.max(MIN_FIT_SPAN_DEGREES, raw.maxLatitude() - raw.minLatitude());
        double longitudeSpan = Math.max(MIN_FIT_SPAN_DEGREES / cosine, raw.maxLongitude() - raw.minLongitude());
        double latitudePad = latitudeSpan * pad;
        double longitudePad = longitudeSpan * pad;
        return new Bounds(
                centerLatitude - latitudeSpan * 0.5 - latitudePad,
                centerLatitude + latitudeSpan * 0.5 + latitudePad,
                raw.centerLongitude() - longitudeSpan * 0.5 - longitudePad,
                raw.centerLongitude() + longitudeSpan * 0.5 + longitudePad);
    }

    private static void accumulate(MapRouteModel model, Accumulator accumulator) {
        boolean[] include = included(model);
        if (include == null) {
            return;
        }
        for (int index = 0; index < include.length; index++) {
            if (include[index]) {
                accumulator.add(model.latitude(index), model.longitude(index));
            }
        }
    }

    /**
     * Samples that belong on the framed route. The longest contiguous run is the drive.
     * Another run is kept when it is a substantial leg or lies near that drive.
     * A short GPS-gap cluster thousands of kilometres away is left out.
     */
    private static boolean[] included(MapRouteModel model) {
        if (model == null || model.isEmpty()) {
            return null;
        }
        int count = model.size();
        java.util.List<int[]> runs = new java.util.ArrayList<>();
        int runStart = 0;
        for (int index = 1; index <= count; index++) {
            if (index == count || model.segmentBreakBefore(index)) {
                runs.add(new int[]{runStart, index});
                runStart = index;
            }
        }
        int primary = 0;
        for (int run = 1; run < runs.size(); run++) {
            if (runs.get(run)[1] - runs.get(run)[0] > runs.get(primary)[1] - runs.get(primary)[0]) {
                primary = run;
            }
        }
        int[] main = runs.get(primary);
        Bounds mainBounds = boundsOf(model, main[0], main[1]);
        int substantial = Math.max(50, (int) Math.ceil(count * SUBSTANTIAL_RUN_FRACTION));
        boolean[] include = new boolean[count];
        for (int run = 0; run < runs.size(); run++) {
            int[] span = runs.get(run);
            int length = span[1] - span[0];
            boolean keep = run == primary
                    || length >= substantial
                    || near(model, span[0], span[1], mainBounds);
            if (!keep) {
                continue;
            }
            for (int sample = span[0]; sample < span[1]; sample++) {
                include[sample] = true;
            }
        }
        return include;
    }

    private static Bounds boundsOf(MapRouteModel model, int from, int to) {
        Accumulator accumulator = new Accumulator();
        for (int index = from; index < to; index++) {
            accumulator.add(model.latitude(index), model.longitude(index));
        }
        return accumulator.bounds();
    }

    private static boolean near(MapRouteModel model, int from, int to, Bounds main) {
        if (main == null || !main.present()) {
            return false;
        }
        for (int index = from; index < to; index++) {
            double latitude = clamp(model.latitude(index), main.minLatitude(), main.maxLatitude());
            double longitude = clamp(model.longitude(index), main.minLongitude(), main.maxLongitude());
            double meters = GeoDistance.haversineMeters(
                    model.latitude(index), model.longitude(index), latitude, longitude);
            if (meters <= NEAR_RUN_METERS) {
                return true;
            }
        }
        return false;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Accumulator {
        private double minLatitude = Double.POSITIVE_INFINITY;
        private double maxLatitude = Double.NEGATIVE_INFINITY;
        private double minLongitude = Double.POSITIVE_INFINITY;
        private double maxLongitude = Double.NEGATIVE_INFINITY;
        private boolean seen;

        void add(double latitude, double longitude) {
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
                return;
            }
            minLatitude = Math.min(minLatitude, latitude);
            maxLatitude = Math.max(maxLatitude, latitude);
            minLongitude = Math.min(minLongitude, longitude);
            maxLongitude = Math.max(maxLongitude, longitude);
            seen = true;
        }

        Bounds bounds() {
            if (!seen) {
                return new Bounds(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            }
            return new Bounds(minLatitude, maxLatitude, minLongitude, maxLongitude);
        }
    }
}
