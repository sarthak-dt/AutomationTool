package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Time-oriented source boundary for graphing. Persistent implementations can query disk/indexes;
 * the renderer remains unchanged.
 */
public interface GraphSeriesQuery {
    List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos);
    String unit(SignalCatalogKey key);
    long sampleCount(SignalCatalogKey key);

    /**
     * Time-range query with a hard response bound. Prefer overriding with indexed early-stop;
     * the default truncates after a full range read (safe for small series only).
     */
    default List<SignalSamplePoint> queryRange(
            SignalCatalogKey key, long startNanos, long endNanos, int maxSamples) {
        int limit = Math.max(1, maxSamples);
        List<SignalSamplePoint> all = queryRange(key, startNanos, endNanos);
        if (all.size() <= limit) {
            return all;
        }
        return List.copyOf(all.subList(0, limit));
    }

    /**
     * Time-range query restricted to one measurement source.
     *
     * <p>Project-merged stores concatenate equivalent engineering signals by timestamp. That is
     * correct for overlaying RPM from two files on one graph axis. It is not correct for GPS:
     * independent recordings reset their timebase, so a merge interleaves geographically unrelated
     * fixes. Implementations that own multiple measurements MUST honour {@code sourceScope}.
     * The default ignores it (single-store queries already are one source).
     */
    default List<SignalSamplePoint> queryRange(
            SignalCatalogKey key, long startNanos, long endNanos, String sourceScope) {
        return queryRange(key, startNanos, endNanos);
    }

    /** Nearest exact persisted sample; implementations may override with indexed lookup. */
    default GraphCursorSample nearest(SignalCatalogKey key, long timestampNanos, long toleranceNanos) {
        long from = timestampNanos - Math.max(0L, toleranceNanos);
        long to = timestampNanos + Math.max(0L, toleranceNanos);
        SignalSamplePoint best = null;
        long bestDistance = Long.MAX_VALUE;
        for (SignalSamplePoint p : queryRange(key, from, to, 4096)) {
            long d = p.timestampNanos() >= timestampNanos ? p.timestampNanos() - timestampNanos : timestampNanos - p.timestampNanos();
            if (d < bestDistance) { best = p; bestDistance = d; }
        }
        return best == null ? null : new GraphCursorSample(key, best.timestampNanos(), best.physicalValue(),
                best.rawValue(), best.textualValue(), unit(key));
    }

    /**
     * Streams samples in {@code [startNanos, endNanos]} without requiring the caller to retain the
     * whole series. The default implementation materializes {@link #queryRange}; disk-backed stores
     * must override with a sequential scan.
     *
     * @param consumer return {@code false} to stop
     */
    default void forEachSample(
            SignalCatalogKey key, long startNanos, long endNanos, SampleConsumer consumer) {
        forEachSample(key, startNanos, endNanos, consumer, () -> true);
    }

    default void forEachSample(
            SignalCatalogKey key,
            long startNanos,
            long endNanos,
            SampleConsumer consumer,
            BooleanSupplier continueQuery) {
        if (consumer == null) {
            return;
        }
        BooleanSupplier cont = continueQuery == null ? () -> true : continueQuery;
        for (SignalSamplePoint sample : queryRange(key, startNanos, endNanos)) {
            if (!cont.getAsBoolean()) {
                return;
            }
            if (!consumer.accept(sample)) {
                return;
            }
        }
    }

    @FunctionalInterface
    interface SampleConsumer {
        boolean accept(SignalSamplePoint sample);
    }
}
