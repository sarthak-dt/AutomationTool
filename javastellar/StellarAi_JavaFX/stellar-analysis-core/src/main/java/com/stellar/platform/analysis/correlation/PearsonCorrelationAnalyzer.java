package com.stellar.platform.analysis.correlation;

import com.stellar.platform.analysis.align.SampleAligner;
import com.stellar.platform.analysis.model.AlignmentPolicy;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.source.AnalysisSampleSource;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.parser.spi.CancellationToken;

import java.util.List;

public final class PearsonCorrelationAnalyzer {

    public record Summary(double coefficient, int pairCount, TimeWindow effectiveRange) {
    }

    private PearsonCorrelationAnalyzer() {
    }

    public static Summary correlate(
            AnalysisSampleSource source,
            SignalCatalogKey a,
            SignalCatalogKey b,
            TimeWindow window,
            AlignmentPolicy policy,
            long toleranceNanos,
            CancellationToken token
    ) {
        List<SampleAligner.Pair> pairs = SampleAligner.align(
                source, a, b, window, policy, toleranceNanos, 250_000, token);
        if (pairs.size() < 3) {
            return new Summary(Double.NaN, pairs.size(), window);
        }
        double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
        long t0 = pairs.getFirst().timestampNanos();
        long t1 = pairs.getLast().timestampNanos();
        for (SampleAligner.Pair p : pairs) {
            sx += p.a();
            sy += p.b();
            sxx += p.a() * p.a();
            syy += p.b() * p.b();
            sxy += p.a() * p.b();
        }
        int n = pairs.size();
        double num = n * sxy - sx * sy;
        double den = Math.sqrt((n * sxx - sx * sx) * (n * syy - sy * sy));
        double r = den == 0d ? Double.NaN : num / den;
        return new Summary(r, n, TimeWindow.of(t0, t1));
    }
}
