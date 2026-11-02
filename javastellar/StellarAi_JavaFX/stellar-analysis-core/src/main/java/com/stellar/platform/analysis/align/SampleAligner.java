package com.stellar.platform.analysis.align;

import com.stellar.platform.analysis.model.AlignmentPolicy;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.source.AnalysisSampleSource;
import com.stellar.platform.analysis.source.SampleStreamer;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.parser.spi.CancellationToken;

import java.util.ArrayList;
import java.util.List;

/**
 * Aligns two series using an explicit policy. Never silently interpolates.
 */
public final class SampleAligner {

    public record Pair(long timestampNanos, double a, double b) {
    }

    private SampleAligner() {
    }

    public static List<Pair> align(
            AnalysisSampleSource source,
            SignalCatalogKey a,
            SignalCatalogKey b,
            TimeWindow window,
            AlignmentPolicy policy,
            long toleranceNanos,
            int maxPairs,
            CancellationToken token
    ) {
        int cap = Math.max(8, maxPairs);
        return switch (policy) {
            case EXACT_TIMESTAMP -> exact(source, a, b, window, cap, token);
            case PREVIOUS_VALUE_HOLD -> hold(source, a, b, window, cap, token);
            case NEAREST_WITH_TOLERANCE -> nearest(source, a, b, window, toleranceNanos, cap, token);
            case OPTIONAL_LINEAR_INTERPOLATION -> interpolate(source, a, b, window, cap, token);
        };
    }

    private static List<Pair> exact(
            AnalysisSampleSource source,
            SignalCatalogKey a,
            SignalCatalogKey b,
            TimeWindow window,
            int cap,
            CancellationToken token
    ) {
        List<Pair> out = new ArrayList<>();
        int nb = source.retainedSampleCount(b);
        final int[] bi = {indexAtOrAfter(source, b, nb, window.startNanos())};
        SampleStreamer.forEach(source, a, window, token, sa -> {
            while (bi[0] < nb) {
                SignalSamplePoint sb = one(source, b, bi[0]);
                if (sb == null) {
                    break;
                }
                if (sb.timestampNanos() < sa.timestampNanos()) {
                    bi[0]++;
                    continue;
                }
                if (sb.timestampNanos() == sa.timestampNanos()) {
                    if (eligible(sa) && eligible(sb)) {
                        out.add(new Pair(sa.timestampNanos(), sa.physicalValue(), sb.physicalValue()));
                    }
                    bi[0]++;
                }
                break;
            }
            return out.size() < cap;
        });
        return out;
    }

    private static List<Pair> hold(
            AnalysisSampleSource source,
            SignalCatalogKey a,
            SignalCatalogKey b,
            TimeWindow window,
            int cap,
            CancellationToken token
    ) {
        List<Pair> out = new ArrayList<>();
        int nb = source.retainedSampleCount(b);
        final int[] bi = {0};
        final SignalSamplePoint[] lastB = {null};
        SampleStreamer.forEach(source, a, window, token, sa -> {
            while (bi[0] < nb) {
                SignalSamplePoint cand = one(source, b, bi[0]);
                if (cand == null || cand.timestampNanos() > sa.timestampNanos()) {
                    break;
                }
                if (eligible(cand)) {
                    lastB[0] = cand;
                } else {
                    lastB[0] = null;
                }
                bi[0]++;
            }
            if (lastB[0] != null && eligible(sa)) {
                out.add(new Pair(sa.timestampNanos(), sa.physicalValue(), lastB[0].physicalValue()));
            }
            return out.size() < cap;
        });
        return out;
    }

    private static List<Pair> nearest(
            AnalysisSampleSource source,
            SignalCatalogKey a,
            SignalCatalogKey b,
            TimeWindow window,
            long tolerance,
            int cap,
            CancellationToken token
    ) {
        List<Pair> out = new ArrayList<>();
        SampleStreamer.forEach(source, a, window, token, sa -> {
            SignalSamplePoint sb = SampleStreamer.nearest(source, b, sa.timestampNanos());
            if (eligible(sa) && eligible(sb)
                    && Math.abs(sb.timestampNanos() - sa.timestampNanos()) <= tolerance) {
                out.add(new Pair(sa.timestampNanos(), sa.physicalValue(), sb.physicalValue()));
            }
            return out.size() < cap;
        });
        return out;
    }

    private static List<Pair> interpolate(
            AnalysisSampleSource source,
            SignalCatalogKey a,
            SignalCatalogKey b,
            TimeWindow window,
            int cap,
            CancellationToken token
    ) {
        List<Pair> out = new ArrayList<>();
        int nb = source.retainedSampleCount(b);
        final int[] bi = {0};
        final SignalSamplePoint[] prev = {null};
        final SignalSamplePoint[] next = {nb > 0 ? one(source, b, 0) : null};
        if (next[0] != null) {
            bi[0] = 1;
        }
        SampleStreamer.forEach(source, a, window, token, sa -> {
            while (next[0] != null && next[0].timestampNanos() < sa.timestampNanos()) {
                prev[0] = next[0];
                next[0] = bi[0] < nb ? one(source, b, bi[0]) : null;
                bi[0]++;
            }
            if (eligible(sa) && eligible(prev[0]) && eligible(next[0])
                    && next[0].timestampNanos() > prev[0].timestampNanos()) {
                double t = (sa.timestampNanos() - prev[0].timestampNanos())
                        / (double) (next[0].timestampNanos() - prev[0].timestampNanos());
                double bv = prev[0].physicalValue() + t * (next[0].physicalValue() - prev[0].physicalValue());
                out.add(new Pair(sa.timestampNanos(), sa.physicalValue(), bv));
            } else if (eligible(sa) && eligible(prev[0])
                    && next[0] == null
                    && prev[0].timestampNanos() == sa.timestampNanos()) {
                out.add(new Pair(sa.timestampNanos(), sa.physicalValue(), prev[0].physicalValue()));
            }
            return out.size() < cap;
        });
        return out;
    }

    private static int indexAtOrAfter(AnalysisSampleSource source, SignalCatalogKey key, int n, long ts) {
        int lo = 0;
        int hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            SignalSamplePoint p = one(source, key, mid);
            if (p == null || p.timestampNanos() < ts) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** A numeric measurement. Categorical labels, invalid codes, and legacy unrecorded samples are not. */
    private static boolean eligible(SignalSamplePoint sample) {
        return sample != null && sample.usableMeasurement();
    }

    private static SignalSamplePoint one(AnalysisSampleSource source, SignalCatalogKey key, int index) {
        List<SignalSamplePoint> page = source.querySamples(key, index, 1);
        return page.isEmpty() ? null : page.getFirst();
    }
}
