package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalSamplePoint;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Builds a {@link ValidatedRoute} from already-processed latitude/longitude/altitude samples.
 *
 * <p>The builder never touches measurement files: it consumes the canonical samples produced by the
 * existing processing pipeline. Pairing is a single forward pass over each role, so cost is
 * O(n) rather than O(n·m). Latitude {@code i} is never paired with longitude {@code i} by index;
 * each latitude sample is matched to the longitude sample whose timestamp is nearest, within
 * a sampling-derived tolerance.
 *
 * <p>Synchronization policy (deterministic, no geographic interpolation):
 * <ol>
 *   <li>Exact timestamp match when both roles observed the same instant.</li>
 *   <li>Otherwise nearest-neighbour within {@code max(1 ms, mean latitude interval)}.</li>
 *   <li>No previous-value hold — a stale latitude must not pair with a newer longitude.</li>
 *   <li>No interpolation of WGS84 coordinates merely to increase point count.</li>
 * </ol>
 * Time gaps larger than {@code max(configured floor, 4 × median sample interval)} are modelled as
 * telemetry dropouts, not driven distance. Geographic jumps above the configured limit, and steps
 * faster than {@link #MAX_GROUNDED_SPEED_KPH}, are impossible GPS discontinuities. The samples stay
 * in the model. The polyline restarts. Start/End remain the first/last accepted observations.
 */
public final class MapRouteBuilder {

    private static final Logger LOG = Logger.getLogger(MapRouteBuilder.class.getName());

    /**
     * GPS-derived speed is undefined for sub-millisecond intervals. Those deltas are merge
     * collisions or duplicate-channel jitter, not a vehicle sample. They must become gaps —
     * not a 10<sup>8</sup> km/h quotient.
     */
    public static final long MIN_VALID_DT_NANOS = 1_000_000L;

    /**
     * Implied speed above this is not driven distance, even when the step is shorter than the
     * geographic jump limit. 1 000 km/h is above the coarse synthetic grids used in tests and
     * above any road vehicle. It catches a few-hundred-metre hop inside one GPS sample.
     */
    public static final double MAX_GROUNDED_SPEED_KPH = 1_000.0;

    private MapRouteBuilder() {
    }

    public static MapRouteModel build(List<SignalSamplePoint> latitudeSamples,
                                      List<SignalSamplePoint> longitudeSamples,
                                      List<SignalSamplePoint> altitudeSamples) {
        return validateAndBuild(latitudeSamples, longitudeSamples, altitudeSamples,
                MapRouteBuildOptions.defaults()).model();
    }

    public static MapRouteModel build(List<SignalSamplePoint> latitudeSamples,
                                      List<SignalSamplePoint> longitudeSamples,
                                      List<SignalSamplePoint> altitudeSamples,
                                      MapRouteBuildOptions options) {
        return validateAndBuild(latitudeSamples, longitudeSamples, altitudeSamples, options).model();
    }

    public static ValidatedRoute validateAndBuild(List<SignalSamplePoint> latitudeSamples,
                                                  List<SignalSamplePoint> longitudeSamples,
                                                  List<SignalSamplePoint> altitudeSamples) {
        return validateAndBuild(latitudeSamples, longitudeSamples, altitudeSamples,
                MapRouteBuildOptions.defaults());
    }

    public static ValidatedRoute validateAndBuild(List<SignalSamplePoint> latitudeSamples,
                                                  List<SignalSamplePoint> longitudeSamples,
                                                  List<SignalSamplePoint> altitudeSamples,
                                                  MapRouteBuildOptions options) {
        int rawLat = latitudeSamples == null ? 0 : latitudeSamples.size();
        int rawLon = longitudeSamples == null ? 0 : longitudeSamples.size();
        if (latitudeSamples == null || longitudeSamples == null
                || latitudeSamples.isEmpty() || longitudeSamples.isEmpty()) {
            return new ValidatedRoute(MapRouteModel.empty(), new RouteBuildDiagnostics(
                    rawLat, rawLon, 0, 0, 0, 0, 0, 0, 0,
                    null, null, null, null, null, null, 0.0, null));
        }
        MapRouteBuildOptions opts = options == null ? MapRouteBuildOptions.defaults() : options;
        long tolerance = opts.pairToleranceNanos() != null
                ? Math.max(0L, opts.pairToleranceNanos())
                : deriveTolerance(latitudeSamples);
        long maxTimeGapNanos = deriveMaxTimeGapNanos(latitudeSamples, longitudeSamples, opts.maxTimeGapNanos());

        int capacity = latitudeSamples.size();
        long[] timestamps = new long[capacity];
        long[] longitudeTimestamps = new long[capacity];
        double[] latitudes = new double[capacity];
        double[] longitudes = new double[capacity];
        boolean altitudeAvailable = altitudeSamples != null && !altitudeSamples.isEmpty();
        double[] altitudes = altitudeAvailable ? new double[capacity] : null;

        int count = 0;
        int invalidCoordinates = 0;
        int rejectedPairs = 0;
        int duplicateOrOutOfOrder = 0;
        int lonCursor = 0;
        int altCursor = 0;
        long previousTimestamp = Long.MIN_VALUE;

        for (SignalSamplePoint lat : latitudeSamples) {
            long t = lat.timestampNanos();
            // Duplicate / backwards timestamps would break binary search; keep the first fix only.
            // Do not sort: reordering would hide an upstream identity/pairing defect.
            if (count > 0 && t <= previousTimestamp) {
                duplicateOrOutOfOrder++;
                continue;
            }
            if (!lat.usableMeasurement()) {
                invalidCoordinates++;
                continue;
            }
            double latitude = lat.physicalValue();
            if (!GeoDistance.validLatitude(latitude)) {
                invalidCoordinates++;
                continue;
            }
            lonCursor = advance(longitudeSamples, lonCursor, t);
            int lonIndex = nearest(longitudeSamples, lonCursor, t, tolerance);
            if (lonIndex < 0) {
                rejectedPairs++;
                continue;
            }
            SignalSamplePoint lonSample = longitudeSamples.get(lonIndex);
            if (!lonSample.usableMeasurement()) {
                invalidCoordinates++;
                continue;
            }
            double longitude = lonSample.physicalValue();
            if (!GeoDistance.validPosition(latitude, longitude)) {
                invalidCoordinates++;
                continue;
            }
            timestamps[count] = t;
            longitudeTimestamps[count] = lonSample.timestampNanos();
            latitudes[count] = latitude;
            longitudes[count] = longitude;
            if (altitudes != null) {
                altCursor = advance(altitudeSamples, altCursor, t);
                int altIndex = nearest(altitudeSamples, altCursor, t, tolerance);
                altitudes[count] = altIndex < 0 || !altitudeSamples.get(altIndex).usableMeasurement()
                        ? Double.NaN
                        : altitudeSamples.get(altIndex).physicalValue();
            }
            previousTimestamp = t;
            count++;
        }

        int paired = count;
        if (count == 0) {
            RouteBuildDiagnostics diagnostics = new RouteBuildDiagnostics(
                    rawLat, rawLon, 0, 0, invalidCoordinates, rejectedPairs, duplicateOrOutOfOrder,
                    0, 0, null, null, null, null, null, null, 0.0, null);
            LOG.fine(() -> "GPS route empty · " + diagnostics.summary());
            return new ValidatedRoute(MapRouteModel.empty(), diagnostics);
        }

        if (allZeroZero(latitudes, longitudes, count)) {
            RouteBuildDiagnostics diagnostics = new RouteBuildDiagnostics(
                    rawLat, rawLon, paired, 0, invalidCoordinates, rejectedPairs, duplicateOrOutOfOrder,
                    0, 0, timestamps[0], latitudes[0], longitudes[0],
                    timestamps[count - 1], latitudes[count - 1], longitudes[count - 1],
                    0.0, null);
            LOG.fine(() -> "GPS route treated as missing · all " + paired + " paired samples are (0,0)");
            return new ValidatedRoute(MapRouteModel.empty(), diagnostics);
        }
        int beforeZeroDrop = count;
        count = dropSuspiciousZeroRuns(timestamps, longitudeTimestamps, latitudes, longitudes, altitudes, count);
        if (count == 0) {
            RouteBuildDiagnostics diagnostics = new RouteBuildDiagnostics(
                    rawLat, rawLon, paired, 0, invalidCoordinates + beforeZeroDrop, rejectedPairs,
                    duplicateOrOutOfOrder, 0, 0,
                    null, null, null, null, null, null, 0.0, null);
            return new ValidatedRoute(MapRouteModel.empty(), diagnostics);
        }

        long[] finalTimestamps = trim(timestamps, count);
        long[] finalLonTimestamps = trim(longitudeTimestamps, count);
        double[] finalLatitudes = trim(latitudes, count);
        double[] finalLongitudes = trim(longitudes, count);
        double[] finalAltitudes = altitudes == null ? null : trim(altitudes, count);

        double[] cumulative = new double[count];
        boolean[] breaks = new boolean[count];
        int zeroDt = 0;
        int discontinuities = 0;
        double largestStep = 0.0;
        Double largestSpeedKph = null;
        for (int i = 1; i < count; i++) {
            double step = GeoDistance.haversineMeters(
                    finalLatitudes[i - 1], finalLongitudes[i - 1], finalLatitudes[i], finalLongitudes[i]);
            if (Double.isFinite(step) && step > largestStep) {
                largestStep = step;
            }
            long dtNanos = finalTimestamps[i] - finalTimestamps[i - 1];
            boolean invalidDt = dtNanos < MIN_VALID_DT_NANOS;
            if (invalidDt) {
                zeroDt++;
            }
            boolean impossibleSpeed = !invalidDt
                    && Double.isFinite(step)
                    && (step / (dtNanos / 1e9)) * 3.6 > MAX_GROUNDED_SPEED_KPH;
            boolean gap = invalidDt
                    || !Double.isFinite(step)
                    || step > opts.maxJumpMeters()
                    || dtNanos > maxTimeGapNanos
                    || impossibleSpeed;
            breaks[i] = gap;
            if (gap) {
                discontinuities++;
            }
            // A GPS jump or undefined dt is not driven distance.
            cumulative[i] = cumulative[i - 1] + (gap ? 0.0 : step);
            if (!gap && dtNanos >= MIN_VALID_DT_NANOS && Double.isFinite(step)) {
                double kph = (step / (dtNanos / 1e9)) * 3.6;
                if (Double.isFinite(kph) && (largestSpeedKph == null || kph > largestSpeedKph)) {
                    largestSpeedKph = kph;
                }
            }
        }

        MapRouteModel model = new MapRouteModel(
                finalTimestamps, finalLatitudes, finalLongitudes, finalAltitudes, cumulative, breaks);
        RouteBuildDiagnostics diagnostics = new RouteBuildDiagnostics(
                rawLat, rawLon, paired, count, invalidCoordinates, rejectedPairs, duplicateOrOutOfOrder,
                zeroDt, discontinuities,
                finalTimestamps[0], finalLatitudes[0], finalLongitudes[0],
                finalTimestamps[count - 1], finalLatitudes[count - 1], finalLongitudes[count - 1],
                largestStep, largestSpeedKph);
        final long firstLonDelta = finalLonTimestamps[0] - finalTimestamps[0];
        final long lastLonDelta = finalLonTimestamps[count - 1] - finalTimestamps[count - 1];
        LOG.fine(() -> "GPS route · " + diagnostics.summary()
                + " lonPairDeltaFirstNs=" + firstLonDelta
                + " lonPairDeltaLastNs=" + lastLonDelta);
        return new ValidatedRoute(model, diagnostics, finalLonTimestamps);
    }

    /** Median-ish sample interval keeps pairing correct for 1 Hz GPS and 100 Hz bus signals alike. */
    private static long deriveTolerance(List<SignalSamplePoint> samples) {
        int size = samples.size();
        if (size < 2) {
            return 1_000_000_000L;
        }
        long span = samples.get(size - 1).timestampNanos() - samples.get(0).timestampNanos();
        long average = span <= 0 ? 0 : span / (size - 1);
        return Math.max(1_000_000L, average);
    }

    /**
     * Dropout threshold from the observed GPS cadence. Two or three samples cannot distinguish a
     * valid slow rate from a hole, so the configured floor is used until at least five latitude
     * samples exist. Then the limit is {@code max(floor, 4 × median interval)}: a 1 Hz dropout of
     * several seconds stays a gap, while a legitimate 10 s CAN GPS rate remains one driven segment.
     */
    private static long deriveMaxTimeGapNanos(
            List<SignalSamplePoint> latitudeSamples,
            List<SignalSamplePoint> longitudeSamples,
            long configuredFloor) {
        long floor = Math.max(1L, configuredFloor);
        if (latitudeSamples == null || latitudeSamples.size() < 5) {
            return floor;
        }
        long latMedian = medianPositiveDelta(latitudeSamples);
        long lonMedian = medianPositiveDelta(longitudeSamples);
        long observed = Math.max(latMedian, lonMedian);
        if (observed <= 0L || observed > Long.MAX_VALUE / 4L) {
            return floor;
        }
        return Math.max(floor, observed * 4L);
    }

    private static long medianPositiveDelta(List<SignalSamplePoint> samples) {
        if (samples == null || samples.size() < 2) {
            return 0L;
        }
        long[] positive = new long[samples.size() - 1];
        int n = 0;
        for (int i = 1; i < samples.size(); i++) {
            long dt = samples.get(i).timestampNanos() - samples.get(i - 1).timestampNanos();
            if (dt > 0L) {
                positive[n++] = dt;
            }
        }
        if (n == 0) {
            return 0L;
        }
        Arrays.sort(positive, 0, n);
        return positive[n / 2];
    }

    /** Advances the cursor to the last sample at or before {@code target}. */
    private static int advance(List<SignalSamplePoint> samples, int cursor, long target) {
        int i = Math.max(0, cursor);
        int last = samples.size() - 1;
        while (i < last && samples.get(i + 1).timestampNanos() <= target) {
            i++;
        }
        return i;
    }

    /**
     * Pairs by nearest timestamp among the predecessor and successor of {@code target}.
     * Array index is never used as the pairing key.
     */
    private static int nearest(List<SignalSamplePoint> samples, int cursor, long target, long tolerance) {
        int best = -1;
        long bestDelta = Long.MAX_VALUE;
        for (int i = Math.max(0, cursor); i <= Math.min(samples.size() - 1, cursor + 1); i++) {
            long delta = Math.abs(samples.get(i).timestampNanos() - target);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = i;
            }
        }
        return bestDelta <= tolerance ? best : -1;
    }

    private static long[] trim(long[] source, int count) {
        if (count == source.length) {
            return source;
        }
        long[] copy = new long[count];
        System.arraycopy(source, 0, copy, 0, count);
        return copy;
    }

    private static double[] trim(double[] source, int count) {
        if (count == source.length) {
            return source;
        }
        double[] copy = new double[count];
        System.arraycopy(source, 0, copy, 0, count);
        return copy;
    }

    private static boolean allZeroZero(double[] latitudes, double[] longitudes, int count) {
        for (int i = 0; i < count; i++) {
            if (latitudes[i] != 0.0 || longitudes[i] != 0.0) {
                return false;
            }
        }
        return count > 0;
    }

    /**
     * Isolated (0,0) stays; a long repeated (0,0) run is treated as missing GPS, not a real track.
     */
    private static int dropSuspiciousZeroRuns(
            long[] timestamps, long[] longitudeTimestamps, double[] latitudes, double[] longitudes,
            double[] altitudes, int count) {
        boolean[] drop = new boolean[count];
        int runStart = -1;
        for (int i = 0; i <= count; i++) {
            boolean zero = i < count && latitudes[i] == 0.0 && longitudes[i] == 0.0;
            if (zero && runStart < 0) {
                runStart = i;
            }
            if (!zero && runStart >= 0) {
                int run = i - runStart;
                if (GpsSignalValidator.suspiciousZeroZeroRun(run, count)) {
                    for (int j = runStart; j < i; j++) {
                        drop[j] = true;
                    }
                }
                runStart = -1;
            }
        }
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (drop[i]) {
                continue;
            }
            timestamps[kept] = timestamps[i];
            longitudeTimestamps[kept] = longitudeTimestamps[i];
            latitudes[kept] = latitudes[i];
            longitudes[kept] = longitudes[i];
            if (altitudes != null) {
                altitudes[kept] = altitudes[i];
            }
            kept++;
        }
        return kept;
    }
}
