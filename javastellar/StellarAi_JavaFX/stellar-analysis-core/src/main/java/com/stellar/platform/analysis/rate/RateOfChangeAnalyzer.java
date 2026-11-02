package com.stellar.platform.analysis.rate;

import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.source.AnalysisSampleSource;
import com.stellar.platform.analysis.source.SampleStreamer;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.parser.spi.CancellationToken;

/**
 * Streaming Δvalue/Δtime. Duplicate or zero Δtime is skipped — time is never invented.
 */
public final class RateOfChangeAnalyzer {

    public record Summary(
            long pairs,
            long skippedNonPositiveDt,
            double minRate,
            double maxRate,
            double meanAbsRate,
            long minRateAt,
            long maxRateAt
    ) {
    }

    private RateOfChangeAnalyzer() {
    }

    public static Summary compute(
            AnalysisSampleSource source,
            SignalCatalogKey key,
            TimeWindow window,
            CancellationToken token
    ) {
        final long[] prevTs = {Long.MIN_VALUE};
        final double[] prevVal = {Double.NaN};
        final long[] pairs = {0};
        final long[] skipped = {0};
        final double[] min = {Double.POSITIVE_INFINITY};
        final double[] max = {Double.NEGATIVE_INFINITY};
        final double[] absSum = {0};
        final long[] minAt = {0};
        final long[] maxAt = {0};
        SampleStreamer.forEach(source, key, window, token, sample -> {
            if (!sample.usableMeasurement()) {
                prevTs[0] = Long.MIN_VALUE;
                prevVal[0] = Double.NaN;
                return true;
            }
            if (prevTs[0] != Long.MIN_VALUE) {
                long dt = sample.timestampNanos() - prevTs[0];
                if (dt <= 0L) {
                    skipped[0]++;
                } else if (Double.isFinite(prevVal[0])) {
                    double rate = (sample.physicalValue() - prevVal[0]) / (dt / 1_000_000_000.0d);
                    pairs[0]++;
                    absSum[0] += Math.abs(rate);
                    if (rate < min[0]) {
                        min[0] = rate;
                        minAt[0] = sample.timestampNanos();
                    }
                    if (rate > max[0]) {
                        max[0] = rate;
                        maxAt[0] = sample.timestampNanos();
                    }
                }
            }
            prevTs[0] = sample.timestampNanos();
            prevVal[0] = sample.physicalValue();
            return true;
        });
        return new Summary(
                pairs[0],
                skipped[0],
                pairs[0] == 0 ? Double.NaN : min[0],
                pairs[0] == 0 ? Double.NaN : max[0],
                pairs[0] == 0 ? Double.NaN : absSum[0] / pairs[0],
                minAt[0],
                maxAt[0]
        );
    }
}
