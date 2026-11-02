package com.stellar.platform.analysis.band;

import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.source.AnalysisSampleSource;
import com.stellar.platform.analysis.source.SampleStreamer;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.parser.spi.CancellationToken;

public final class OperatingBandAnalyzer {

    public record Summary(
            double timeInsideSeconds,
            double observedDurationSeconds,
            double percentInside,
            int entryCount,
            int exitCount,
            double minInside,
            double maxInside
    ) {
    }

    private OperatingBandAnalyzer() {
    }

    public static Summary compute(
            AnalysisSampleSource source,
            SignalCatalogKey key,
            TimeWindow window,
            double min,
            double max,
            CancellationToken token
    ) {
        double lo = Math.min(min, max);
        double hi = Math.max(min, max);
        final boolean[] inside = {false};
        final long[] prevTs = {Long.MIN_VALUE};
        final long[] firstTs = {Long.MIN_VALUE};
        final long[] lastTs = {Long.MIN_VALUE};
        final double[] insideNanos = {0};
        final int[] entries = {0};
        final int[] exits = {0};
        final double[] minIn = {Double.POSITIVE_INFINITY};
        final double[] maxIn = {Double.NEGATIVE_INFINITY};
        SampleStreamer.forEach(source, key, window, token, sample -> {
            if (!sample.usableMeasurement()) {
                if (inside[0]) {
                    exits[0]++;
                    inside[0] = false;
                }
                prevTs[0] = Long.MIN_VALUE;
                return true;
            }
            boolean now = sample.physicalValue() >= lo && sample.physicalValue() <= hi;
            if (firstTs[0] == Long.MIN_VALUE) {
                firstTs[0] = sample.timestampNanos();
            }
            if (prevTs[0] != Long.MIN_VALUE && inside[0] && sample.timestampNanos() > prevTs[0]) {
                insideNanos[0] += (sample.timestampNanos() - prevTs[0]);
            }
            if (now && !inside[0]) {
                entries[0]++;
            } else if (!now && inside[0]) {
                exits[0]++;
            }
            if (now) {
                minIn[0] = Math.min(minIn[0], sample.physicalValue());
                maxIn[0] = Math.max(maxIn[0], sample.physicalValue());
            }
            inside[0] = now;
            prevTs[0] = sample.timestampNanos();
            lastTs[0] = sample.timestampNanos();
            return true;
        });
        double observed = firstTs[0] == Long.MIN_VALUE || lastTs[0] <= firstTs[0]
                ? 0d : (lastTs[0] - firstTs[0]) / 1_000_000_000.0d;
        double insideSec = insideNanos[0] / 1_000_000_000.0d;
        double pct = observed <= 0d ? 0d : 100d * insideSec / observed;
        return new Summary(
                insideSec,
                observed,
                pct,
                entries[0],
                exits[0],
                Double.isInfinite(minIn[0]) ? Double.NaN : minIn[0],
                Double.isInfinite(maxIn[0]) ? Double.NaN : maxIn[0]
        );
    }
}
