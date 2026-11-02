package com.stellar.platform.processing.map;

import java.util.Arrays;

/**
 * Immutable compact route for one processed measurement.
 *
 * <p>Stored as parallel primitive arrays: a 1M-sample route costs roughly 40 MB here versus
 * hundreds of MB as boxed point objects. No automotive signal values are copied in — the map holds
 * only what is needed to draw and index the route, and every other value is resolved from the
 * existing time-series store via {@link #timestampNanos(int)}.
 */
public final class MapRouteModel {

    private final long[] timestampsNanos;
    private final double[] latitude;
    private final double[] longitude;
    private final double[] altitude;
    private final double[] cumulativeDistanceMeters;
    private final boolean[] segmentBreak;
    /** Ascending indices of gap starts; gaps are rare, so this stays tiny and binary-searchable. */
    private final int[] breakIndices;
    private final double minLatitude;
    private final double maxLatitude;
    private final double minLongitude;
    private final double maxLongitude;

    MapRouteModel(long[] timestampsNanos,
                  double[] latitude,
                  double[] longitude,
                  double[] altitude,
                  double[] cumulativeDistanceMeters,
                  boolean[] segmentBreak) {
        this.timestampsNanos = timestampsNanos;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.cumulativeDistanceMeters = cumulativeDistanceMeters;
        this.segmentBreak = segmentBreak;
        int breakCount = 0;
        for (int i = 1; i < segmentBreak.length; i++) {
            if (segmentBreak[i]) {
                breakCount++;
            }
        }
        this.breakIndices = new int[breakCount];
        int w = 0;
        for (int i = 1; i < segmentBreak.length; i++) {
            if (segmentBreak[i]) {
                breakIndices[w++] = i;
            }
        }
        double minLat = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double minLon = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < latitude.length; i++) {
            minLat = Math.min(minLat, latitude[i]);
            maxLat = Math.max(maxLat, latitude[i]);
            minLon = Math.min(minLon, longitude[i]);
            maxLon = Math.max(maxLon, longitude[i]);
        }
        this.minLatitude = latitude.length == 0 ? 0 : minLat;
        this.maxLatitude = latitude.length == 0 ? 0 : maxLat;
        this.minLongitude = latitude.length == 0 ? 0 : minLon;
        this.maxLongitude = latitude.length == 0 ? 0 : maxLon;
    }

    public static MapRouteModel empty() {
        return new MapRouteModel(new long[0], new double[0], new double[0], null, new double[0], new boolean[0]);
    }

    public int size() {
        return timestampsNanos.length;
    }

    public boolean isEmpty() {
        return timestampsNanos.length == 0;
    }

    public boolean hasAltitude() {
        return altitude != null;
    }

    public long timestampNanos(int index) {
        return timestampsNanos[index];
    }

    public double latitude(int index) {
        return latitude[index];
    }

    public double longitude(int index) {
        return longitude[index];
    }

    /** @return altitude in metres, or {@code NaN} when the sample or the whole role is missing. */
    public double altitude(int index) {
        return altitude == null ? Double.NaN : altitude[index];
    }

    public double cumulativeDistanceMeters(int index) {
        return cumulativeDistanceMeters[index];
    }

    /** True when the link into {@code index} is a GPS gap/outlier and must not be drawn. */
    public boolean segmentBreakBefore(int index) {
        return index > 0 && segmentBreak[index];
    }

    /**
     * True when any gap falls inside {@code (fromIndex, toIndex]}. Decimated rendering skips
     * intermediate samples, so a renderer must ask about the whole span rather than one step.
     */
    public boolean hasBreakBetween(int fromIndex, int toIndex) {
        if (breakIndices.length == 0 || toIndex <= fromIndex) {
            return false;
        }
        int position = Arrays.binarySearch(breakIndices, fromIndex + 1);
        int candidate = position >= 0 ? position : -(position + 1);
        return candidate < breakIndices.length && breakIndices[candidate] <= toIndex;
    }

    public long firstTimestampNanos() {
        return timestampsNanos.length == 0 ? 0 : timestampsNanos[0];
    }

    public long lastTimestampNanos() {
        return timestampsNanos.length == 0 ? 0 : timestampsNanos[timestampsNanos.length - 1];
    }

    public double totalDistanceMeters() {
        return cumulativeDistanceMeters.length == 0 ? 0 : cumulativeDistanceMeters[cumulativeDistanceMeters.length - 1];
    }

    public double minLatitude() {
        return minLatitude;
    }

    public double maxLatitude() {
        return maxLatitude;
    }

    public double minLongitude() {
        return minLongitude;
    }

    public double maxLongitude() {
        return maxLongitude;
    }

    /**
     * Binary timestamp lookup used by cursor synchronization; never a scan from index zero.
     *
     * @return index of the route sample closest in time, or {@code -1} for an empty route.
     */
    public int nearestIndexByTime(long timestampNanos) {
        if (this.timestampsNanos.length == 0) {
            return -1;
        }
        int found = Arrays.binarySearch(this.timestampsNanos, timestampNanos);
        if (found >= 0) {
            return found;
        }
        int insertion = -(found + 1);
        if (insertion == 0) {
            return 0;
        }
        if (insertion >= this.timestampsNanos.length) {
            return this.timestampsNanos.length - 1;
        }
        long before = timestampNanos - this.timestampsNanos[insertion - 1];
        long after = this.timestampsNanos[insertion] - timestampNanos;
        return after < before ? insertion : insertion - 1;
    }

    /** @return first index whose timestamp is >= {@code timestampNanos}, or {@link #size()}. */
    public int indexAtOrAfter(long timestampNanos) {
        int found = Arrays.binarySearch(this.timestampsNanos, timestampNanos);
        return found >= 0 ? found : -(found + 1);
    }

    /** @return last index whose timestamp is <= {@code timestampNanos}, or {@code -1}. */
    public int indexAtOrBefore(long timestampNanos) {
        int found = Arrays.binarySearch(this.timestampsNanos, timestampNanos);
        return found >= 0 ? found : -(found + 1) - 1;
    }

    /**
     * O(1) route distance from the precomputed cumulative index, so moving A or B never re-runs
     * Haversine across the range.
     */
    public double distanceMetersBetween(int indexA, int indexB) {
        int lo = Math.min(indexA, indexB);
        int hi = Math.max(indexA, indexB);
        return cumulativeDistanceMeters[hi] - cumulativeDistanceMeters[lo];
    }

    /** Approximate heap footprint of this route, used by cache accounting and tests. */
    public long approximateBytes() {
        long n = timestampsNanos.length;
        long perSample = Long.BYTES + Double.BYTES * 3L + 1L + (altitude == null ? 0L : Double.BYTES);
        return n * perSample;
    }
}
