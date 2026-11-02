package com.stellar.platform.analysis.duration;

import com.stellar.platform.analysis.model.AnalysisParameters;
import com.stellar.platform.analysis.model.AnalysisSeverity;
import com.stellar.platform.analysis.model.EngineeringFinding;
import com.stellar.platform.analysis.model.FindingType;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.source.AnalysisSampleSource;
import com.stellar.platform.analysis.source.SampleStreamer;
import com.stellar.platform.analysis.state.StateTransitionAnalyzer;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.parser.spi.CancellationToken;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DurationAnalyzer {

    public record Summary(
            double totalActiveSeconds,
            double longestSeconds,
            int occurrences,
            Long firstStartNanos,
            Long lastEndNanos
    ) {
    }

    private DurationAnalyzer() {
    }

    public static Summary compute(
            AnalysisSampleSource source,
            SignalCatalogKey key,
            TimeWindow window,
            AnalysisParameters parameters,
            CancellationToken token
    ) {
        String expected = parameters.expectedState();
        boolean matchNumeric = expected.isBlank() && parameters.threshold() != null;
        final boolean[] active = {false};
        final long[] start = {0};
        final long[] last = {0};
        final long[] prevTs = {Long.MIN_VALUE};
        final double[] total = {0};
        final double[] longest = {0};
        final int[] count = {0};
        final Long[] first = {null};
        final Long[] lastEnd = {null};
        SampleStreamer.forEach(source, key, window, token, sample -> {
            boolean on = matchNumeric
                    ? sample.usableMeasurement() && parameters.thresholdOperator().matches(
                            sample.physicalValue(), parameters.threshold(), parameters.thresholdHigh())
                    : Objects.equals(
                            StateTransitionAnalyzer.stateOf(
                                    sample.textualValue(), sample.physicalValue(), sample.validity()),
                            expected.isBlank() ? "1" : expected);
            if (on) {
                if (!active[0]) {
                    active[0] = true;
                    start[0] = sample.timestampNanos();
                    if (first[0] == null) {
                        first[0] = start[0];
                    }
                    count[0]++;
                }
                last[0] = sample.timestampNanos();
            } else if (active[0]) {
                long end = prevTs[0] == Long.MIN_VALUE ? last[0] : prevTs[0];
                double dur = Math.max(0L, end - start[0]) / 1_000_000_000.0d;
                total[0] += dur;
                longest[0] = Math.max(longest[0], dur);
                lastEnd[0] = end;
                active[0] = false;
            }
            prevTs[0] = sample.timestampNanos();
            return true;
        });
        if (active[0]) {
            double dur = Math.max(0L, last[0] - start[0]) / 1_000_000_000.0d;
            total[0] += dur;
            longest[0] = Math.max(longest[0], dur);
            lastEnd[0] = last[0];
        }
        return new Summary(total[0], longest[0], count[0], first[0], lastEnd[0]);
    }

    public static EngineeringFinding toFinding(
            Summary summary,
            SignalCatalogKey key,
            String analysisId,
            AnalysisSeverity severity
    ) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("totalActiveSeconds", summary.totalActiveSeconds());
        metrics.put("longestSeconds", summary.longestSeconds());
        metrics.put("occurrences", (double) summary.occurrences());
        long start = summary.firstStartNanos() == null ? 0L : summary.firstStartNanos();
        Long end = summary.lastEndNanos();
        return new EngineeringFinding(
                null, analysisId, FindingType.DURATION_OCCURRENCE, severity, List.of(key),
                start, end,
                String.format("Active %.3f s · %d occurrence(s) · longest %.3f s",
                        summary.totalActiveSeconds(), summary.occurrences(), summary.longestSeconds()),
                "", metrics, null);
    }
}
