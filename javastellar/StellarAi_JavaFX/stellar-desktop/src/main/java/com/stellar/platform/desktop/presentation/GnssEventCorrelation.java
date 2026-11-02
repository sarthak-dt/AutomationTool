package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineeringFormats;
import com.stellar.platform.processing.map.GeoDistance;
import com.stellar.platform.processing.map.MapRouteModel;

/**
 * Matches an engineer event to a GNSS sample already on the route.
 * The coordinate is the stored sample. Nothing is interpolated.
 */
public final class GnssEventCorrelation {

    /** A point event may use a sample this far from its timestamp. Windows must contain the sample. */
    public static final long POINT_TOLERANCE_NANOS = 1_000_000_000L;

    public static final String POLICY_POINT = "Nearest stored GNSS sample within 1.000 s";
    public static final String POLICY_WINDOW = "First stored GNSS sample inside the window";

    public record Result(
            boolean located,
            double latitude,
            double longitude,
            long sampleTimestampNanos,
            String statement,
            long deltaNanos,
            String policy,
            int sampleIndex
    ) {
    }

    private GnssEventCorrelation() {
    }

    public static Result locate(MapRouteModel route, long startNanos, Long endNanos, String label) {
        String name = label == null || label.isBlank() ? "Event" : label;
        if (route == null || route.isEmpty()) {
            return miss(name + " has no GNSS sample on this route. No location was invented.");
        }
        boolean window = endNanos != null && endNanos >= startNanos;
        if (window) {
            return locateWindow(route, startNanos, endNanos, name);
        }
        return locatePoint(route, startNanos, name);
    }

    private static Result locateWindow(MapRouteModel route, long startNanos, long endNanos, String name) {
        int index = firstAtOrAfter(route, startNanos);
        while (index < route.size() && route.timestampNanos(index) <= endNanos) {
            if (valid(route, index)) {
                long sample = route.timestampNanos(index);
                return hit(route, index, sample - startNanos, POLICY_WINDOW,
                        "First GNSS sample inside the engineer-selected window. "
                                + "The marker is that sample, not the whole event. The coordinate was not interpolated. "
                                + "GNSS sample " + EngineeringFormats.seconds(sample) + ".");
            }
            index++;
        }
        return miss(name + " has no GNSS sample inside its time window. It is not marked on the route.");
    }

    private static Result locatePoint(MapRouteModel route, long startNanos, String name) {
        int from = route.indexAtOrAfter(startNanos - POINT_TOLERANCE_NANOS);
        int to = route.indexAtOrBefore(startNanos + POINT_TOLERANCE_NANOS);
        int best = -1;
        long bestAbs = Long.MAX_VALUE;
        long bestDelta = 0L;
        for (int index = Math.max(0, from); index <= to && index < route.size(); index++) {
            if (!valid(route, index)) {
                continue;
            }
            long delta = route.timestampNanos(index) - startNanos;
            long abs = Math.abs(delta);
            if (abs > POINT_TOLERANCE_NANOS || abs >= bestAbs) {
                continue;
            }
            best = index;
            bestAbs = abs;
            bestDelta = delta;
        }
        if (best >= 0) {
            String difference = bestDelta == 0L
                    ? " The GNSS sample is at the event timestamp."
                    : " Time difference " + EngineeringFormats.seconds(Math.abs(bestDelta))
                    + (bestDelta > 0L ? " after the event." : " before the event.")
                    + " The sample is not at the event timestamp.";
            return hit(route, best, bestDelta, POLICY_POINT,
                    "Nearest GNSS sample within 1 s of this point event. The coordinate was not interpolated."
                            + difference);
        }
        String gap = nearestGap(route, startNanos);
        return miss(name + " has no GNSS sample within 1 s. It is not marked on the route." + gap);
    }

    private static String nearestGap(MapRouteModel route, long startNanos) {
        int nearest = route.nearestIndexByTime(startNanos);
        int validIndex = nearestValid(route, nearest, startNanos);
        if (validIndex < 0) {
            return " No valid GNSS coordinate was found.";
        }
        long abs = Math.abs(route.timestampNanos(validIndex) - startNanos);
        return " The nearest valid GNSS sample is " + EngineeringFormats.seconds(abs)
                + " away, outside the 1.000 s tolerance.";
    }

    private static int nearestValid(MapRouteModel route, int start, long eventNanos) {
        if (start < 0) {
            return -1;
        }
        if (valid(route, start)) {
            return start;
        }
        for (int step = 1; step < route.size(); step++) {
            int right = start + step;
            int left = start - step;
            boolean rightOpen = right < route.size();
            boolean leftOpen = left >= 0;
            if (!rightOpen && !leftOpen) {
                return -1;
            }
            if (rightOpen && valid(route, right)) {
                return right;
            }
            if (leftOpen && valid(route, left)) {
                return left;
            }
            long rightAway = rightOpen ? Math.abs(route.timestampNanos(right) - eventNanos) : Long.MAX_VALUE;
            long leftAway = leftOpen ? Math.abs(route.timestampNanos(left) - eventNanos) : Long.MAX_VALUE;
            if (rightAway > POINT_TOLERANCE_NANOS * 8L && leftAway > POINT_TOLERANCE_NANOS * 8L) {
                return -1;
            }
        }
        return -1;
    }

    /** First stored sample at {@code timestamp}, or the first sample at or after it. */
    private static int firstAtOrAfter(MapRouteModel route, long timestamp) {
        int index = route.indexAtOrAfter(timestamp);
        if (index < 0 || index >= route.size()) {
            return index;
        }
        long found = route.timestampNanos(index);
        while (index > 0 && route.timestampNanos(index - 1) == found) {
            index--;
        }
        return index;
    }

    private static boolean valid(MapRouteModel route, int index) {
        return index >= 0 && index < route.size()
                && GeoDistance.validPosition(route.latitude(index), route.longitude(index));
    }

    private static Result hit(MapRouteModel route, int index, long deltaNanos, String policy, String statement) {
        return new Result(true, route.latitude(index), route.longitude(index),
                route.timestampNanos(index), statement, deltaNanos, policy, index);
    }

    private static Result miss(String statement) {
        return new Result(false, Double.NaN, Double.NaN, 0L, statement, Long.MIN_VALUE, "", -1);
    }
}
