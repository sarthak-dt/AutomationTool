package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;

import java.util.List;
import java.util.Objects;

/**
 * Binds an analysis query to one canonical measurement {@code sourceScope}.
 * Graph waveforms must not merge sibling recordings by timestamp.
 */
public final class SourceScopedGraphSeriesQuery implements GraphSeriesQuery {

    private final GraphSeriesQuery delegate;
    private final String sourceScope;

    public SourceScopedGraphSeriesQuery(GraphSeriesQuery delegate, String sourceScope) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.sourceScope = sourceScope == null ? "" : sourceScope.trim();
    }

    public String sourceScope() {
        return sourceScope;
    }

    @Override
    public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
        return sourceScope.isBlank()
                ? delegate.queryRange(key, startNanos, endNanos)
                : delegate.queryRange(key, startNanos, endNanos, sourceScope);
    }

    @Override
    public List<SignalSamplePoint> queryRange(
            SignalCatalogKey key, long startNanos, long endNanos, int maxSamples) {
        return sourceScope.isBlank()
                ? delegate.queryRange(key, startNanos, endNanos, maxSamples)
                : bounded(delegate.queryRange(key, startNanos, endNanos, sourceScope), maxSamples);
    }

    @Override
    public List<SignalSamplePoint> queryRange(
            SignalCatalogKey key, long startNanos, long endNanos, String sourceScope) {
        String scope = sourceScope == null || sourceScope.isBlank() ? this.sourceScope : sourceScope.trim();
        return scope.isBlank()
                ? delegate.queryRange(key, startNanos, endNanos)
                : delegate.queryRange(key, startNanos, endNanos, scope);
    }

    @Override
    public String unit(SignalCatalogKey key) {
        return delegate.unit(key);
    }

    @Override
    public long sampleCount(SignalCatalogKey key) {
        return delegate.sampleCount(key);
    }

    private static List<SignalSamplePoint> bounded(List<SignalSamplePoint> samples, int maxSamples) {
        int limit = Math.max(1, maxSamples);
        if (samples.size() <= limit) {
            return samples;
        }
        return List.copyOf(samples.subList(0, limit));
    }
}
