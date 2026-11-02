package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.graph.GraphSeriesQuery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves "value of these signals at timestamp T" through the existing indexed time-series store.
 *
 * <p>This never scans a signal from index zero and never materialises a signal copy for the map: it
 * issues a bounded range query around T and picks the sample the alignment policy requires.
 */
public final class SignalSnapshotService {

    /** Window around T; wide enough for slow signals, small enough to stay a bounded index read. */
    private static final long DEFAULT_TOLERANCE_NANOS = 2_000_000_000L;

    private final GraphSeriesQuery query;

    public SignalSnapshotService(GraphSeriesQuery query) {
        this.query = Objects.requireNonNull(query, "query");
    }

    public List<SignalSnapshotValue> snapshotAt(long timestampNanos, Collection<SignalCatalogKey> keys) {
        return snapshotAt(timestampNanos, keys, null, DEFAULT_TOLERANCE_NANOS);
    }

    /**
     * @param policy    {@code null} selects per-signal defaults: state/enum signals hold the last
     *                  value, continuous numeric signals interpolate
     * @param toleranceNanos maximum distance from T that may be consulted
     */
    public List<SignalSnapshotValue> snapshotAt(long timestampNanos,
                                                Collection<SignalCatalogKey> keys,
                                                SignalAlignmentPolicy policy,
                                                long toleranceNanos) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        long tolerance = Math.max(0L, toleranceNanos);
        List<SignalSnapshotValue> values = new ArrayList<>(keys.size());
        for (SignalCatalogKey key : keys) {
            if (key == null) {
                continue;
            }
            resolve(key, timestampNanos, policy, tolerance).ifPresent(values::add);
        }
        return List.copyOf(values);
    }

    public Optional<SignalSnapshotValue> resolve(SignalCatalogKey key,
                                                 long timestampNanos,
                                                 SignalAlignmentPolicy requestedPolicy,
                                                 long toleranceNanos) {
        List<SignalSamplePoint> window =
                query.queryRange(key, timestampNanos - toleranceNanos, timestampNanos + toleranceNanos);
        if (window == null || window.isEmpty()) {
            return Optional.empty();
        }
        String unit = query.unit(key);
        SignalAlignmentPolicy policy = requestedPolicy != null ? requestedPolicy : defaultPolicyFor(window);

        int before = -1;
        int after = -1;
        int exact = -1;
        for (int i = 0; i < window.size(); i++) {
            long t = window.get(i).timestampNanos();
            if (t == timestampNanos) {
                exact = i;
                break;
            }
            if (t < timestampNanos) {
                before = i;
            } else {
                after = i;
                break;
            }
        }

        if (exact >= 0) {
            return Optional.of(value(key, timestampNanos, window.get(exact), unit, policy));
        }
        if (policy == SignalAlignmentPolicy.EXACT) {
            return Optional.empty();
        }

        SignalSamplePoint low = before >= 0 ? window.get(before) : null;
        SignalSamplePoint high = after >= 0 ? window.get(after) : null;

        if (policy == SignalAlignmentPolicy.LINEAR && low != null && high != null) {
            long span = high.timestampNanos() - low.timestampNanos();
            double ratio = span == 0 ? 0.0 : (double) (timestampNanos - low.timestampNanos()) / span;
            double interpolated = low.physicalValue() + ratio * (high.physicalValue() - low.physicalValue());
            return Optional.of(new SignalSnapshotValue(
                    key, timestampNanos, timestampNanos, interpolated, null, unit, SignalAlignmentPolicy.LINEAR));
        }
        if (policy == SignalAlignmentPolicy.HOLD_LAST && low != null) {
            return Optional.of(value(key, timestampNanos, low, unit, SignalAlignmentPolicy.HOLD_LAST));
        }

        SignalSamplePoint nearest = pickNearest(low, high, timestampNanos);
        if (nearest == null) {
            return Optional.empty();
        }
        return Optional.of(value(key, timestampNanos, nearest, unit, SignalAlignmentPolicy.NEAREST));
    }

    /**
     * Enum/state signals carry decoded text or integral raw codes; interpolating those would invent
     * a state that never occurred, so they hold the last value instead.
     */
    static SignalAlignmentPolicy defaultPolicyFor(List<SignalSamplePoint> window) {
        boolean discrete = false;
        boolean fractionalSeen = false;
        for (SignalSamplePoint point : window) {
            if (point.textualValue() != null && !point.textualValue().isBlank()) {
                discrete = true;
                break;
            }
            double v = point.physicalValue();
            if (!Double.isNaN(v) && v != Math.rint(v)) {
                fractionalSeen = true;
            }
        }
        if (discrete) {
            return SignalAlignmentPolicy.HOLD_LAST;
        }
        return fractionalSeen ? SignalAlignmentPolicy.LINEAR : SignalAlignmentPolicy.HOLD_LAST;
    }

    private static SignalSamplePoint pickNearest(SignalSamplePoint low, SignalSamplePoint high, long timestampNanos) {
        if (low == null) {
            return high;
        }
        if (high == null) {
            return low;
        }
        long dLow = timestampNanos - low.timestampNanos();
        long dHigh = high.timestampNanos() - timestampNanos;
        return dHigh < dLow ? high : low;
    }

    private static SignalSnapshotValue value(SignalCatalogKey key,
                                             long requested,
                                             SignalSamplePoint point,
                                             String unit,
                                             SignalAlignmentPolicy policy) {
        return new SignalSnapshotValue(key, requested, point.timestampNanos(), point.physicalValue(),
                point.textualValue(), unit, policy);
    }
}
