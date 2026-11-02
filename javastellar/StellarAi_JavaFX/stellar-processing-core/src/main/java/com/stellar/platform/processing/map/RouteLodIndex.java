package com.stellar.platform.processing.map;

/**
 * Viewport-aware route decimation for rendering only.
 *
 * <p>Original samples are never discarded: this returns indices <em>into</em> the
 * {@link MapRouteModel}, so a click still resolves to the original sample and its original
 * timestamp. Simplification is Douglas-Peucker, run iteratively (no recursion) so a 1M-sample route
 * cannot overflow the stack, and applied per contiguous run so GPS gaps are never bridged.
 *
 * <p>Levels form a fixed ladder built once by {@link #prepareLevels()} on a background thread.
 * {@link #indices} is then a lookup, never a computation — which is what keeps zooming a large
 * route off the JavaFX thread. Each level is capped at {@link #MAX_RENDER_POINTS}, bounding both
 * build cost and per-frame draw cost regardless of how many samples the measurement holds.
 */
public final class RouteLodIndex {

    private static final int LEVEL_COUNT = 7;
    /** Beyond this, extra points cost frame time without being visible on any real display. */
    private static final int MAX_RENDER_POINTS = 40_000;
    private static final double LEVEL_RATIO = 4.0;

    private final MapRouteModel route;
    /**
     * Longitude compression at the route's centre latitude. Hoisted out of the inner loop: computing
     * cos() per candidate point dominated simplification cost at million-sample scale.
     */
    private final double longitudeScale;
    private volatile double[] tolerances;
    private volatile int[][] levels;

    public RouteLodIndex(MapRouteModel route) {
        this.route = route;
        double centerLatitude = route.isEmpty() ? 0 : (route.minLatitude() + route.maxLatitude()) * 0.5;
        this.longitudeScale = Math.cos(Math.toRadians(centerLatitude));
    }

    /**
     * Builds the level ladder. Idempotent, and intended to run during background route preparation
     * so the first paint and every subsequent zoom are pure lookups.
     */
    public void prepareLevels() {
        if (levels != null) {
            return;
        }
        synchronized (this) {
            if (levels != null) {
                return;
            }
            int n = route.size();
            if (n == 0) {
                tolerances = new double[]{0};
                levels = new int[][]{new int[0]};
                return;
            }
            double diagonal = Math.hypot(
                    route.maxLatitude() - route.minLatitude(),
                    route.maxLongitude() - route.minLongitude());
            double base = Math.max(1e-9, diagonal / 50.0);

            double[] builtTolerances = new double[LEVEL_COUNT];
            int[][] builtLevels = new int[LEVEL_COUNT][];
            int built = 0;
            for (int k = 0; k < LEVEL_COUNT; k++) {
                double tolerance = base / Math.pow(LEVEL_RATIO, k);
                int[] simplified = simplify(tolerance);
                builtTolerances[built] = tolerance;
                builtLevels[built] = simplified;
                built++;
                // Once a level is at the render cap (or holds every sample) finer levels add nothing.
                if (simplified.length >= Math.min(n, MAX_RENDER_POINTS)) {
                    break;
                }
            }
            double[] finalTolerances = new double[built];
            int[][] finalLevels = new int[built][];
            System.arraycopy(builtTolerances, 0, finalTolerances, 0, built);
            System.arraycopy(builtLevels, 0, finalLevels, 0, built);
            tolerances = finalTolerances;
            levels = finalLevels;
        }
    }

    /**
     * @param toleranceDegrees perpendicular tolerance in degrees, derived by the view from
     *                         degrees-per-pixel so decimation follows zoom level
     * @return ascending sample indices to draw; the same array instance for a given level, so
     *         panning and zooming allocate nothing
     */
    public int[] indices(double toleranceDegrees) {
        prepareLevels();
        double[] localTolerances = tolerances;
        int[][] localLevels = levels;
        if (localLevels.length == 0) {
            return new int[0];
        }
        for (int k = 0; k < localTolerances.length; k++) {
            if (localTolerances[k] <= toleranceDegrees) {
                return localLevels[k];
            }
        }
        return localLevels[localLevels.length - 1];
    }

    public boolean prepared() {
        return levels != null;
    }

    public synchronized void clear() {
        tolerances = null;
        levels = null;
    }

    int preparedLevelCountForTest() {
        prepareLevels();
        return levels.length;
    }

    private int[] simplify(double toleranceDegrees) {
        int n = route.size();
        if (toleranceDegrees <= 0 || n <= 2) {
            int[] all = new int[n];
            for (int i = 0; i < n; i++) {
                all[i] = i;
            }
            return all;
        }
        boolean[] keep = new boolean[n];
        // Budget shared across runs: it caps total work as well as total output.
        int[] budget = {MAX_RENDER_POINTS};
        int runStart = 0;
        for (int i = 1; i <= n; i++) {
            if (i == n || route.segmentBreakBefore(i)) {
                douglasPeucker(runStart, i - 1, toleranceDegrees, keep, budget);
                runStart = i;
            }
        }
        int kept = 0;
        for (boolean k : keep) {
            if (k) {
                kept++;
            }
        }
        int[] result = new int[kept];
        int w = 0;
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                result[w++] = i;
            }
        }
        return result;
    }

    private void douglasPeucker(int first, int last, double tolerance, boolean[] keep, int[] budget) {
        if (first > last) {
            return;
        }
        keep[first] = true;
        keep[last] = true;
        budget[0] -= 2;
        if (last - first < 2) {
            return;
        }
        int[] stack = new int[64];
        int top = 0;
        stack[top++] = first;
        stack[top++] = last;
        while (top > 0 && budget[0] > 0) {
            int end = stack[--top];
            int begin = stack[--top];
            double x1 = route.longitude(begin) * longitudeScale;
            double y1 = route.latitude(begin);
            double dx = route.longitude(end) * longitudeScale - x1;
            double dy = route.latitude(end) - y1;
            double lengthSquared = dx * dx + dy * dy;
            double maxDistance = -1;
            int maxIndex = -1;
            // Compare squared distances; the single sqrt happens once per segment, not per point.
            for (int i = begin + 1; i < end; i++) {
                double px = route.longitude(i) * longitudeScale - x1;
                double py = route.latitude(i) - y1;
                double d;
                if (lengthSquared == 0.0) {
                    d = px * px + py * py;
                } else {
                    double t = (px * dx + py * dy) / lengthSquared;
                    t = t < 0 ? 0 : Math.min(t, 1);
                    double ex = px - t * dx;
                    double ey = py - t * dy;
                    d = ex * ex + ey * ey;
                }
                if (d > maxDistance) {
                    maxDistance = d;
                    maxIndex = i;
                }
            }
            if (maxIndex > 0 && maxDistance > tolerance * tolerance) {
                keep[maxIndex] = true;
                budget[0]--;
                if (top + 4 > stack.length) {
                    int[] grown = new int[stack.length * 2];
                    System.arraycopy(stack, 0, grown, 0, top);
                    stack = grown;
                }
                stack[top++] = begin;
                stack[top++] = maxIndex;
                stack[top++] = maxIndex;
                stack[top++] = end;
            }
        }
    }

}
