package com.stellar.platform.analysis.compare;

import com.stellar.platform.analysis.align.SampleAligner;
import com.stellar.platform.analysis.model.AlignmentPolicy;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.source.AnalysisSampleSource;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.parser.spi.CancellationToken;

import java.util.List;

public final class ComparisonAnalyzer {

    public record Summary(
            int pairCount,
            double meanAbsoluteError,
            double maxAbsoluteError,
            double rmse,
            double meanDifference
    ) {
    }

    private ComparisonAnalyzer() {
    }

    public static Summary compare(
            AnalysisSampleSource source,
            SignalCatalogKey actual,
            SignalCatalogKey requested,
            TimeWindow window,
            AlignmentPolicy policy,
            long toleranceNanos,
            CancellationToken token
    ) {
        List<SampleAligner.Pair> pairs = SampleAligner.align(
                source, actual, requested, window, policy, toleranceNanos, 250_000, token);
        if (pairs.isEmpty()) {
            return new Summary(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        double absSum = 0;
        double sqSum = 0;
        double diffSum = 0;
        double maxAbs = 0;
        for (SampleAligner.Pair p : pairs) {
            double d = p.a() - p.b();
            double ad = Math.abs(d);
            absSum += ad;
            sqSum += d * d;
            diffSum += d;
            if (ad > maxAbs) {
                maxAbs = ad;
            }
        }
        int n = pairs.size();
        return new Summary(n, absSum / n, maxAbs, Math.sqrt(sqSum / n), diffSum / n);
    }
}
