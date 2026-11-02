package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.CategoricalValue;
import com.stellar.platform.domain.EnumeratedState;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pixel-budget-driven LOD reducer. It never mutates source samples and never averages away extrema. */
public final class MinMaxLodReducer {
    private MinMaxLodReducer() { }

    public static List<GraphBucket> reduce(List<SignalSamplePoint> samples, int targetBuckets) {
        Objects.requireNonNull(samples, "samples");
        if (samples.isEmpty()) return List.of();
        int target = Math.max(1, targetBuckets);
        if (samples.size() <= target) {
            return samples.stream().map(MinMaxLodReducer::single).toList();
        }
        int bucketSize = (int) Math.ceil(samples.size() / (double) target);
        List<GraphBucket> out = new ArrayList<>((samples.size() + bucketSize - 1) / bucketSize);
        for (int from = 0; from < samples.size(); from += bucketSize) {
            int to = Math.min(samples.size(), from + bucketSize);
            out.add(bucket(samples, from, to));
        }
        return List.copyOf(out);
    }

    private static GraphBucket single(SignalSamplePoint p) {
        return fromCorners(p, p, usableOrAbsent(p), usableOrAbsent(p), 1);
    }

    private static GraphBucket bucket(List<SignalSamplePoint> samples, int from, int to) {
        SignalSamplePoint first = samples.get(from);
        SignalSamplePoint last = samples.get(to - 1);
        SignalSamplePoint min = null;
        SignalSamplePoint max = null;
        for (int i = from; i < to; i++) {
            SignalSamplePoint p = samples.get(i);
            if (!p.usableMeasurement()) {
                continue;
            }
            if (min == null || p.physicalValue() < min.physicalValue()) {
                min = p;
            }
            if (max == null || p.physicalValue() > max.physicalValue()) {
                max = p;
            }
        }
        return fromCorners(first, last, min, max, to - from);
    }

    /**
     * Min and max are NaN when the bucket has no usable finite sample. The original
     * first and last values stay on the bucket for traceability.
     */
    static GraphBucket fromCorners(
            SignalSamplePoint first,
            SignalSamplePoint last,
            SignalSamplePoint min,
            SignalSamplePoint max,
            long count
    ) {
        if (min == null || max == null) {
            return new GraphBucket(
                    first.timestampNanos(), first.physicalValue(),
                    first.timestampNanos(), Double.NaN,
                    first.timestampNanos(), Double.NaN,
                    last.timestampNanos(), last.physicalValue(),
                    count,
                    first.validity(), SignalValidity.NON_FINITE_VALUE,
                    SignalValidity.NON_FINITE_VALUE, last.validity(),
                    label(first), null, null, label(last));
        }
        return new GraphBucket(
                first.timestampNanos(), first.physicalValue(),
                min.timestampNanos(), min.physicalValue(),
                max.timestampNanos(), max.physicalValue(),
                last.timestampNanos(), last.physicalValue(),
                count,
                first.validity(), min.validity(), max.validity(), last.validity(),
                label(first), label(min), label(max), label(last));
    }

    private static String label(SignalSamplePoint point) {
        if (point == null) {
            return null;
        }
        String categorical = CategoricalValue.label(
                point.validity(), point.physicalValue(), point.textualValue());
        if (categorical != null) {
            return categorical;
        }
        return EnumeratedState.text(point.validity(), point.physicalValue(), point.textualValue());
    }

    private static SignalSamplePoint usableOrAbsent(SignalSamplePoint point) {
        return point.usableMeasurement() ? point : null;
    }
}
