package com.stellar.platform.analysis.threshold;

import com.stellar.platform.analysis.model.AnalysisParameters;
import com.stellar.platform.analysis.model.AnalysisSeverity;
import com.stellar.platform.analysis.model.EngineeringFinding;
import com.stellar.platform.analysis.model.FindingType;
import com.stellar.platform.analysis.model.ThresholdOperator;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.source.AnalysisSampleSource;
import com.stellar.platform.analysis.source.SampleStreamer;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.parser.spi.CancellationToken;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Continuous satisfaction of a threshold is one event. Optional hysteresis uses a
 * different exit condition so noisy chatter does not split events.
 */
public final class ThresholdAnalyzer {

    private ThresholdAnalyzer() {
    }

    public static List<EngineeringFinding> detect(
            AnalysisSampleSource source,
            SignalCatalogKey key,
            TimeWindow window,
            AnalysisParameters parameters,
            String analysisId,
            CancellationToken token
    ) {
        ThresholdOperator op = parameters.thresholdOperator();
        Double enter = parameters.threshold();
        Double high = parameters.thresholdHigh();
        Double exit = parameters.hysteresisExit();
        AnalysisSeverity severity = parameters.findingSeverity();
        List<EngineeringFinding> out = new ArrayList<>();
        final boolean[] active = {false};
        final long[] start = {0};
        final long[] last = {0};
        final double[] peak = {Double.NaN};
        final double[] trough = {Double.NaN};
        final long[] samples = {0};
        SampleStreamer.forEach(source, key, window, token, sample -> {
            if (!sample.usableMeasurement()) {
                if (active[0]) {
                    out.add(event(analysisId, key, severity, start[0], last[0], peak[0], trough[0], samples[0]));
                    active[0] = false;
                }
                return true;
            }
            double v = sample.physicalValue();
            boolean inside = op.matches(v, enter, high);
            if (!active[0]) {
                if (inside) {
                    active[0] = true;
                    start[0] = sample.timestampNanos();
                    last[0] = start[0];
                    peak[0] = v;
                    trough[0] = v;
                    samples[0] = 1;
                }
            } else {
                boolean still = exit == null
                        ? inside
                        : !exitTriggered(op, v, exit, enter, high);
                if (still) {
                    last[0] = sample.timestampNanos();
                    samples[0]++;
                    if (v > peak[0]) {
                        peak[0] = v;
                    }
                    if (v < trough[0]) {
                        trough[0] = v;
                    }
                } else {
                    out.add(event(analysisId, key, severity, start[0], last[0], peak[0], trough[0], samples[0]));
                    active[0] = false;
                }
            }
            return true;
        });
        if (active[0]) {
            out.add(event(analysisId, key, severity, start[0], last[0], peak[0], trough[0], samples[0]));
        }
        return List.copyOf(out);
    }

    private static boolean exitTriggered(
            ThresholdOperator op, double v, double exit, Double enter, Double high
    ) {
        return switch (op) {
            case GREATER_THAN, GREATER_OR_EQUAL -> v <= exit;
            case LESS_THAN, LESS_OR_EQUAL -> v >= exit;
            case INSIDE_RANGE, OUTSIDE_RANGE -> !op.matches(v, enter, high);
        };
    }

    private static EngineeringFinding event(
            String analysisId,
            SignalCatalogKey key,
            AnalysisSeverity severity,
            long start,
            long end,
            double peak,
            double trough,
            long samples
    ) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("durationSeconds", (end - start) / 1_000_000_000.0d);
        metrics.put("peak", peak);
        metrics.put("minimum", trough);
        metrics.put("sampleCount", (double) samples);
        return new EngineeringFinding(
                null, analysisId, FindingType.THRESHOLD_EVENT, severity, List.of(key),
                start, end,
                String.format("Threshold event %.6f–%.6f s", start / 1e9, end / 1e9),
                String.format("peak=%.6g min=%.6g samples=%d", peak, trough, samples),
                metrics, null);
    }
}
