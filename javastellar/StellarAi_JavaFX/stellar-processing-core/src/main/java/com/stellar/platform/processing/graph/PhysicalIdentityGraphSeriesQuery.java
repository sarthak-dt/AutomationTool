package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * All-Measurements query: every sample read is bound to the key's physical source identity.
 * Never merges sibling recordings by relative timestamp. Large ranges use the member store's
 * min/max buckets instead of materializing every sample.
 */
public final class PhysicalIdentityGraphSeriesQuery implements CancellableLodGraphSeriesQuery {

    private final GraphSeriesQuery delegate;

    public PhysicalIdentityGraphSeriesQuery(GraphSeriesQuery delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
        String scope = physicalScope(key);
        return scope.isBlank()
                ? delegate.queryRange(key, startNanos, endNanos)
                : delegate.queryRange(key, startNanos, endNanos, scope);
    }

    @Override
    public List<SignalSamplePoint> queryRange(
            SignalCatalogKey key, long startNanos, long endNanos, int maxSamples) {
        String scope = physicalScope(key);
        if (scope.isBlank()) {
            return delegate.queryRange(key, startNanos, endNanos, maxSamples);
        }
        List<SignalSamplePoint> samples = delegate.queryRange(key, startNanos, endNanos, scope);
        int limit = Math.max(1, maxSamples);
        if (samples.size() <= limit) {
            return samples;
        }
        return List.copyOf(samples.subList(0, limit));
    }

    @Override
    public List<SignalSamplePoint> queryRange(
            SignalCatalogKey key, long startNanos, long endNanos, String sourceScope) {
        String scope = sourceScope == null || sourceScope.isBlank() ? physicalScope(key) : sourceScope.trim();
        return scope.isBlank()
                ? delegate.queryRange(key, startNanos, endNanos)
                : delegate.queryRange(key, startNanos, endNanos, scope);
    }

    @Override
    public List<SignalSamplePoint> queryRange(
            SignalCatalogKey key, long startNanos, long endNanos, BooleanSupplier continueQuery) {
        if (continueQuery != null && !continueQuery.getAsBoolean()) {
            return List.of();
        }
        if (delegate instanceof CancellableLodGraphSeriesQuery cancellable) {
            return cancellable.queryRange(key, startNanos, endNanos, continueQuery);
        }
        return queryRange(key, startNanos, endNanos);
    }

    @Override
    public List<GraphBucket> queryBuckets(
            SignalCatalogKey key, long startNanos, long endNanos, int targetBuckets) {
        return queryBuckets(key, startNanos, endNanos, targetBuckets, () -> true);
    }

    @Override
    public List<GraphBucket> queryBuckets(
            SignalCatalogKey key, long startNanos, long endNanos, int targetBuckets, BooleanSupplier continueQuery) {
        int budget = Math.max(1, targetBuckets);
        BooleanSupplier cont = continueQuery == null ? () -> true : continueQuery;
        if (delegate instanceof CancellableLodGraphSeriesQuery cancellable) {
            return cancellable.queryBuckets(key, startNanos, endNanos, budget, cont);
        }
        if (delegate instanceof LodGraphSeriesQuery lod) {
            return lod.queryBuckets(key, startNanos, endNanos, budget);
        }
        return MinMaxLodReducer.reduce(queryRange(key, startNanos, endNanos, cont), budget);
    }

    @Override
    public String unit(SignalCatalogKey key) {
        return delegate.unit(key);
    }

    @Override
    public long sampleCount(SignalCatalogKey key) {
        return delegate.sampleCount(key);
    }

    static String physicalScope(SignalCatalogKey key) {
        if (key == null || key.sourceFile() == null) {
            return "";
        }
        String source = key.sourceFile().trim();
        if (source.isEmpty() || ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE.equalsIgnoreCase(source)) {
            return "";
        }
        return source;
    }
}
