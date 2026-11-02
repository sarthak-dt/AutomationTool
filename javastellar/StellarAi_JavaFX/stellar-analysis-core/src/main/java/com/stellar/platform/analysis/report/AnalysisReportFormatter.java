package com.stellar.platform.analysis.report;

import com.stellar.platform.analysis.model.AnalysisResult;
import com.stellar.platform.analysis.model.EngineeringFinding;
import com.stellar.platform.analysis.model.SignalStatistics;

import java.util.Locale;
import java.util.Map;

public final class AnalysisReportFormatter {

    private AnalysisReportFormatter() {
    }

    public static String format(AnalysisResult result) {
        if (result == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("ENGINEERING ANALYSIS\n\n");
        sb.append("Status    ").append(result.status()).append('\n');
        sb.append("Alignment ").append(result.alignmentPolicy()).append('\n');
        sb.append("Window    ").append(sec(result.effectiveRange().startNanos()))
                .append(" – ").append(sec(result.effectiveRange().endNanos()))
                .append('\n');
        sb.append("Duration  ").append(String.format(Locale.ROOT, "%.3f s",
                result.effectiveRange().durationSeconds())).append("\n\n");
        for (SignalStatistics s : result.statistics()) {
            sb.append(s.signalKey().signalName()).append('\n');
            sb.append(String.format(Locale.ROOT, "  Samples   %d  valid %d  missing %d%n",
                    s.sampleCount(), s.validCount(), s.missingCount()));
            sb.append("  Min       ").append(EngineeringValueFormatter.decimal(s.min())).append('\n');
            sb.append("  Max       ").append(EngineeringValueFormatter.decimal(s.max())).append('\n');
            sb.append("  Delta     ").append(EngineeringValueFormatter.decimal(s.delta())).append('\n');
            sb.append("  Mean      ").append(EngineeringValueFormatter.decimal(s.mean())).append('\n');
            sb.append("  StdDev    ").append(EngineeringValueFormatter.decimal(s.standardDeviation())).append('\n');
            sb.append("  RMS       ").append(EngineeringValueFormatter.decimal(s.rms())).append('\n');
            sb.append('\n');
        }
        if (!result.metrics().isEmpty() && result.statistics().isEmpty()) {
            appendMetrics(sb, result.metrics());
        }
        if (!result.findings().isEmpty()) {
            sb.append("Events\n");
            for (EngineeringFinding f : result.findings()) {
                sb.append(String.format(Locale.ROOT, "  %-22s  %s%n",
                        sec(f.startTimestampNanos()), f.summary()));
            }
        }
        return sb.toString();
    }

    private static void appendMetrics(StringBuilder sb, Map<String, Double> metrics) {
        sb.append("Metrics\n");
        String currentSignal = null;
        for (Map.Entry<String, Double> entry : metrics.entrySet()) {
            String key = entry.getKey();
            int dot = key.lastIndexOf('.');
            String suffix = dot > 0 ? key.substring(dot + 1) : key;
            String signal = dot > 0 ? key.substring(0, dot) : "";
            MetricText known = metricText(suffix);
            if (known == null) {
                sb.append(String.format(Locale.ROOT, "  %-22s %s%n",
                        key, EngineeringValueFormatter.decimal(entry.getValue())));
                currentSignal = null;
                continue;
            }
            if (!signal.isEmpty() && !signal.equals(currentSignal)) {
                sb.append(signal).append('\n');
                currentSignal = signal;
            }
            sb.append(String.format(Locale.ROOT, "  %-22s %s%n",
                    known.label(), known.format(entry.getValue())));
        }
        sb.append('\n');
    }

    private static MetricText metricText(String suffix) {
        return switch (suffix) {
            case "timestampA" -> new MetricText("Timestamp A", EngineeringValueFormatter::timestamp);
            case "timestampB" -> new MetricText("Timestamp B", EngineeringValueFormatter::timestamp);
            case "timeDeltaSeconds" -> new MetricText("Time delta", EngineeringValueFormatter::seconds);
            case "valueA" -> new MetricText("Value A", EngineeringValueFormatter::decimal);
            case "valueB" -> new MetricText("Value B", EngineeringValueFormatter::decimal);
            case "valueDelta" -> new MetricText("Value delta", EngineeringValueFormatter::decimal);
            case "bestLagNanos" -> new MetricText("Best lag", EngineeringValueFormatter::nanos);
            case "bestLagSeconds" -> new MetricText("Best lag", EngineeringValueFormatter::seconds);
            case "durationSeconds", "totalActiveSeconds", "longestSeconds", "timeInsideSeconds" ->
                    new MetricText(switch (suffix) {
                        case "durationSeconds" -> "Duration";
                        case "totalActiveSeconds" -> "Total active";
                        case "longestSeconds" -> "Longest";
                        default -> "Time inside";
                    }, EngineeringValueFormatter::seconds);
            case "percentInside" -> new MetricText("Percent inside", EngineeringValueFormatter::percent);
            case "occurrences", "eventCount", "transitionCount", "entryCount", "exitCount",
                    "pairCount", "sampleCount", "derivedSamples", "ratePairs", "skippedNonPositiveDt" ->
                    new MetricText(countLabel(suffix), EngineeringValueFormatter::integer);
            case "mean", "min", "max", "delta" ->
                    new MetricText(switch (suffix) {
                        case "mean" -> "Mean";
                        case "min" -> "Min";
                        case "max" -> "Max";
                        default -> "Delta";
                    }, EngineeringValueFormatter::decimal);
            default -> null;
        };
    }

    private static String countLabel(String suffix) {
        return switch (suffix) {
            case "occurrences" -> "Occurrences";
            case "eventCount" -> "Event count";
            case "transitionCount" -> "Transition count";
            case "entryCount" -> "Entry count";
            case "exitCount" -> "Exit count";
            case "pairCount" -> "Pair count";
            case "sampleCount" -> "Sample count";
            case "derivedSamples" -> "Derived samples";
            case "ratePairs" -> "Rate pairs";
            default -> "Skipped non-positive dt";
        };
    }

    private record MetricText(String label, java.util.function.DoubleFunction<String> format) {
        String format(double value) {
            return format.apply(value);
        }
    }

    private static String sec(long nanos) {
        if (nanos == Long.MIN_VALUE || nanos == Long.MAX_VALUE) {
            return "full";
        }
        return String.format(Locale.ROOT, "%.3f s", nanos / 1_000_000_000.0d);
    }
}
