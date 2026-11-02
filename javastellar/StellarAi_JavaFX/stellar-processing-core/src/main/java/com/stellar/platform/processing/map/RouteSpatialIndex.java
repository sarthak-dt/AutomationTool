package com.stellar.platform.processing.map;

/**
 * Uniform grid over the route bounding box for bounded nearest-point lookup.
 *
 * <p>Click and hover resolution must not scan the whole route. The grid is sized to average a few
 * samples per occupied cell, so a lookup inspects a small local neighbourhood instead of all
 * n points. Built from primitive arrays only — no per-sample objects.
 */
public final class RouteSpatialIndex {

    /** Expanding-ring search stops here; beyond this the route genuinely has no nearby point. */
    private static final int MAX_RINGS = 24;
    private static final int TARGET_PER_CELL = 4;

    private final MapRouteModel route;
    private final int columns;
    private final int rows;
    private final double minLatitude;
    private final double minLongitude;
    private final double latitudeSpan;
    private final double longitudeSpan;
    /** CSR layout: cellStart[c]..cellStart[c+1] indexes into cellItems. */
    private final int[] cellStart;
    private final int[] cellItems;

    public RouteSpatialIndex(MapRouteModel route) {
        this.route = route;
        int n = route.size();
        int cells = Math.max(1, n / TARGET_PER_CELL);
        int side = Math.max(1, (int) Math.ceil(Math.sqrt(cells)));
        this.columns = side;
        this.rows = side;
        this.minLatitude = route.minLatitude();
        this.minLongitude = route.minLongitude();
        this.latitudeSpan = Math.max(1e-9, route.maxLatitude() - route.minLatitude());
        this.longitudeSpan = Math.max(1e-9, route.maxLongitude() - route.minLongitude());

        int cellCount = columns * rows;
        int[] counts = new int[cellCount + 1];
        for (int i = 0; i < n; i++) {
            counts[cellOf(route.latitude(i), route.longitude(i)) + 1]++;
        }
        for (int c = 0; c < cellCount; c++) {
            counts[c + 1] += counts[c];
        }
        this.cellStart = counts;
        this.cellItems = new int[n];
        int[] cursor = new int[cellCount];
        for (int i = 0; i < n; i++) {
            int cell = cellOf(route.latitude(i), route.longitude(i));
            cellItems[cellStart[cell] + cursor[cell]++] = i;
        }
    }

    /**
     * @return index of the nearest route sample, or {@code -1} when the route is empty. Distance is
     *         measured in scaled degree space, which is monotonic with real distance at vehicle
     *         scale and avoids trigonometry per candidate.
     */
    public int nearest(double latitude, double longitude) {
        int n = route.size();
        if (n == 0) {
            return -1;
        }
        int centerColumn = clamp((int) ((longitude - minLongitude) / longitudeSpan * columns), 0, columns - 1);
        int centerRow = clamp((int) ((latitude - minLatitude) / latitudeSpan * rows), 0, rows - 1);
        double scale = Math.cos(Math.toRadians(latitude));
        int best = -1;
        double bestDistance = Double.MAX_VALUE;

        for (int ring = 0; ring <= Math.min(MAX_RINGS, Math.max(columns, rows)); ring++) {
            boolean inspected = false;
            for (int r = centerRow - ring; r <= centerRow + ring; r++) {
                if (r < 0 || r >= rows) {
                    continue;
                }
                for (int c = centerColumn - ring; c <= centerColumn + ring; c++) {
                    if (c < 0 || c >= columns) {
                        continue;
                    }
                    boolean onRingBoundary = ring == 0
                            || r == centerRow - ring || r == centerRow + ring
                            || c == centerColumn - ring || c == centerColumn + ring;
                    if (!onRingBoundary) {
                        continue;
                    }
                    inspected = true;
                    int cell = r * columns + c;
                    for (int k = cellStart[cell]; k < cellStart[cell + 1]; k++) {
                        int i = cellItems[k];
                        double dx = (route.longitude(i) - longitude) * scale;
                        double dy = route.latitude(i) - latitude;
                        double d = dx * dx + dy * dy;
                        if (d < bestDistance) {
                            bestDistance = d;
                            best = i;
                        }
                    }
                }
            }
            // One extra ring after the first hit guards against a closer point just across a border.
            if (best >= 0 && inspected && ring > 0) {
                break;
            }
        }
        return best;
    }

    private int cellOf(double latitude, double longitude) {
        int column = clamp((int) ((longitude - minLongitude) / longitudeSpan * columns), 0, columns - 1);
        int row = clamp((int) ((latitude - minLatitude) / latitudeSpan * rows), 0, rows - 1);
        return row * columns + column;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }
}
