package com.stellar.platform.analysis.source;

import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.parser.spi.CancellationToken;

import java.util.List;
import java.util.Objects;

/**
 * Pages a series through a window. Never materializes the entire signal.
 */
public final class SampleStreamer {

    public static final int PAGE = 4_096;

    @FunctionalInterface
    public interface Visitor {
        /**
         * @return {@code false} to stop early
         */
        boolean onSample(SignalSamplePoint sample) throws AnalysisCancelledException;
    }

    private SampleStreamer() {
    }

    public static long forEach(
            AnalysisSampleSource source,
            SignalCatalogKey key,
            TimeWindow window,
            CancellationToken token,
            Visitor visitor
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(visitor, "visitor");
        int retained = source.retainedSampleCount(key);
        if (retained <= 0) {
            return 0L;
        }
        int start = 0;
        if (!window.isUnbounded() && window.startNanos() != Long.MIN_VALUE) {
            start = indexAtOrAfter(source, key, retained, window.startNanos());
        }
        long visited = 0L;
        for (int offset = start; offset < retained; offset += PAGE) {
            throwIfCancelled(token);
            List<SignalSamplePoint> page = source.querySamples(key, offset, PAGE);
            if (page.isEmpty()) {
                break;
            }
            for (SignalSamplePoint sample : page) {
                throwIfCancelled(token);
                long ts = sample.timestampNanos();
                if (ts < window.startNanos()) {
                    continue;
                }
                if (ts > window.endNanos()) {
                    return visited;
                }
                visited++;
                if (!visitor.onSample(sample)) {
                    return visited;
                }
            }
        }
        return visited;
    }

    public static SignalSamplePoint nearest(
            AnalysisSampleSource source,
            SignalCatalogKey key,
            long timestampNanos
    ) {
        int n = source.retainedSampleCount(key);
        if (n <= 0) {
            return null;
        }
        int idx = indexAtOrAfter(source, key, n, timestampNanos);
        SignalSamplePoint at = idx < n ? first(source.querySamples(key, idx, 1)) : null;
        SignalSamplePoint before = idx > 0 ? first(source.querySamples(key, idx - 1, 1)) : null;
        if (at == null) {
            return before;
        }
        if (before == null) {
            return at;
        }
        long da = Math.abs(at.timestampNanos() - timestampNanos);
        long db = Math.abs(before.timestampNanos() - timestampNanos);
        return db <= da ? before : at;
    }

    public static void throwIfCancelled(CancellationToken token) {
        if (token != null && token.isCancellationRequested()) {
            throw new AnalysisCancelledException();
        }
    }

    private static int indexAtOrAfter(AnalysisSampleSource source, SignalCatalogKey key, int n, long timestamp) {
        int lo = 0;
        int hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            List<SignalSamplePoint> page = source.querySamples(key, mid, 1);
            if (page.isEmpty() || page.getFirst().timestampNanos() < timestamp) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static SignalSamplePoint first(List<SignalSamplePoint> page) {
        return page == null || page.isEmpty() ? null : page.getFirst();
    }
}
