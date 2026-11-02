package com.stellar.platform.analysis.lag;

import com.stellar.platform.analysis.align.SampleAligner;
import com.stellar.platform.analysis.model.AlignmentPolicy;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.source.AnalysisSampleSource;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.parser.spi.CancellationToken;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded lag search. Aligns once (max 16k pairs) then evaluates a fixed number of lag steps.
 * Not O(N²) over raw samples.
 */
public final class BoundedLagAnalyzer {

    public static final int MAX_PAIRS = 16_384;
    public static final int LAG_STEPS = 21;

    public record Summary(long bestLagNanos, double bestCorrelation, int pairCount, long maxLagNanos) {
    }

    private BoundedLagAnalyzer() {
    }

    public static Summary estimate(
            AnalysisSampleSource source,
            SignalCatalogKey leadCandidate,
            SignalCatalogKey lagCandidate,
            TimeWindow window,
            AlignmentPolicy policy,
            long toleranceNanos,
            long maxLagNanos,
            CancellationToken token
    ) {
        List<SampleAligner.Pair> pairs = SampleAligner.align(
                source, leadCandidate, lagCandidate, window, policy, toleranceNanos, MAX_PAIRS, token);
        if (pairs.size() < 8) {
            return new Summary(0L, Double.NaN, pairs.size(), maxLagNanos);
        }
        double[] a = new double[pairs.size()];
        double[] b = new double[pairs.size()];
        long[] t = new long[pairs.size()];
        for (int i = 0; i < pairs.size(); i++) {
            a[i] = pairs.get(i).a();
            b[i] = pairs.get(i).b();
            t[i] = pairs.get(i).timestampNanos();
        }
        long span = Math.max(1L, t[t.length - 1] - t[0]);
        long limit = Math.min(Math.max(0L, maxLagNanos), span);
        long step = Math.max(1L, limit / Math.max(1, (LAG_STEPS - 1) / 2));
        double bestR = Double.NaN;
        long bestLag = 0L;
        for (int k = -((LAG_STEPS - 1) / 2); k <= (LAG_STEPS - 1) / 2; k++) {
            long lag = k * step;
            List<double[]> shifted = new ArrayList<>();
            int j = 0;
            for (int i = 0; i < a.length; i++) {
                long target = t[i] + lag;
                while (j + 1 < t.length && Math.abs(t[j + 1] - target) <= Math.abs(t[j] - target)) {
                    j++;
                }
                if (Math.abs(t[j] - target) <= step * 2) {
                    shifted.add(new double[]{a[i], b[j]});
                }
            }
            if (shifted.size() < 8) {
                continue;
            }
            double r = pearson(shifted);
            if (Double.isFinite(r) && (!Double.isFinite(bestR) || Math.abs(r) > Math.abs(bestR))) {
                bestR = r;
                bestLag = lag;
            }
        }
        return new Summary(bestLag, bestR, pairs.size(), limit);
    }

    private static double pearson(List<double[]> v) {
        int n = v.size();
        double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
        for (double[] p : v) {
            sx += p[0];
            sy += p[1];
            sxx += p[0] * p[0];
            syy += p[1] * p[1];
            sxy += p[0] * p[1];
        }
        double num = n * sxy - sx * sy;
        double den = Math.sqrt((n * sxx - sx * sx) * (n * syy - sy * sy));
        return den == 0d ? Double.NaN : num / den;
    }
}
