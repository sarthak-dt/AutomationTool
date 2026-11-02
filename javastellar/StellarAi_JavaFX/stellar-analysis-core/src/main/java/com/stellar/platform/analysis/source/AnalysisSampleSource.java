package com.stellar.platform.analysis.source;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.catalog.SignalSeriesStore;

import java.util.List;
import java.util.Objects;

/**
 * Read-only paged sample access. Implementations must never rewrite source timestamps or values.
 */
public interface AnalysisSampleSource {

    int retainedSampleCount(SignalCatalogKey key);

    List<SignalSamplePoint> querySamples(SignalCatalogKey key, int offset, int limit);

    default List<SignalSamplePoint> queryTimestampRange(
            SignalCatalogKey key,
            long startNanos,
            long endNanos,
            int maxSamples
    ) {
        if (endNanos < startNanos || maxSamples <= 0) {
            return List.of();
        }
        int n = retainedSampleCount(key);
        int lo = 0;
        int hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            List<SignalSamplePoint> page = querySamples(key, mid, 1);
            if (page.isEmpty() || page.getFirst().timestampNanos() < startNanos) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return querySamples(key, lo, maxSamples).stream()
                .filter(p -> p.timestampNanos() <= endNanos)
                .toList();
    }

    static AnalysisSampleSource of(SignalSeriesStore store) {
        Objects.requireNonNull(store, "store");
        return new AnalysisSampleSource() {
            @Override
            public int retainedSampleCount(SignalCatalogKey key) {
                return store.retainedSampleCount(key);
            }

            @Override
            public List<SignalSamplePoint> querySamples(SignalCatalogKey key, int offset, int limit) {
                return store.querySamples(key, offset, limit);
            }

            @Override
            public List<SignalSamplePoint> queryTimestampRange(
                    SignalCatalogKey key, long startNanos, long endNanos, int maxSamples
            ) {
                return store.queryTimestampRange(key, startNanos, endNanos, maxSamples);
            }
        };
    }
}
