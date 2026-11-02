package com.stellar.platform.processing.catalog;

import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;

import java.util.List;
import java.util.function.Consumer;

/**
 * Read-only signal-series contract consumed by presentation and analysis layers.
 * <p>
 * Implementations may own samples (for example a bounded in-memory ring) or compose
 * multiple stores without copying samples. The contract stays pageable so a persistent
 * store can replace the in-memory implementation without changing callers.
 */
public interface SignalSeriesStore {

    SignalCatalog snapshot();

    List<SignalSamplePoint> querySamples(SignalCatalogKey key, int offset, int limit);

    /**
     * Visits samples once. The default pages through {@link #querySamples}. Stores that reopen
     * a segment from the first byte on every page must override this so a full pass stays linear.
     */
    default void forEachSample(SignalCatalogKey key, Consumer<SignalSamplePoint> consumer) {
        if (key == null || consumer == null) {
            return;
        }
        int offset = 0;
        while (true) {
            List<SignalSamplePoint> page = querySamples(key, offset, 4_096);
            if (page == null || page.isEmpty()) {
                return;
            }
            for (SignalSamplePoint sample : page) {
                if (sample != null) {
                    consumer.accept(sample);
                }
            }
            if (page.size() < 4_096) {
                return;
            }
            int next = offset + page.size();
            if (next <= offset) {
                return;
            }
            offset = next;
        }
    }

    int retainedSampleCount(SignalCatalogKey key);

    long retainedWindowStartIndex(SignalCatalogKey key);

    /**
     * Lowest sample index whose timestamp is {@code >= timestampNanos}.
     * Default implementation binary-searches with single-sample pages so memory stays O(1).
     */
    default long findSampleIndexAtOrAfter(SignalCatalogKey key, long timestampNanos) {
        int n = retainedSampleCount(key);
        if (n <= 0) {
            return 0L;
        }
        int lo = 0;
        int hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            List<SignalSamplePoint> page = querySamples(key, mid, 1);
            if (page.isEmpty() || page.getFirst().timestampNanos() < timestampNanos) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * Bounded timestamp-range read. Implementations that own a disk index should override.
     * The default uses {@link #findSampleIndexAtOrAfter} plus one page — never a full-series copy.
     */
    default List<SignalSamplePoint> queryTimestampRange(
            SignalCatalogKey key,
            long startNanos,
            long endNanos,
            int maxSamples
    ) {
        if (endNanos < startNanos || maxSamples <= 0) {
            return List.of();
        }
        int start = (int) Math.min(Integer.MAX_VALUE, findSampleIndexAtOrAfter(key, startNanos));
        List<SignalSamplePoint> page = querySamples(key, start, maxSamples);
        if (page.isEmpty()) {
            return List.of();
        }
        int keep = page.size();
        while (keep > 0 && page.get(keep - 1).timestampNanos() > endNanos) {
            keep--;
        }
        return keep == page.size() ? page : List.copyOf(page.subList(0, keep));
    }

    default List<SignalSamplePoint> queryPageFromIndex(SignalCatalogKey key, long index, int limit) {
        int offset = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, index));
        return querySamples(key, offset, Math.max(1, limit));
    }
}
