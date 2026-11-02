package com.stellar.platform.processing.drive;

import com.stellar.platform.domain.CategoricalValue;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.graph.GraphSeriesQuery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Segment-level signal health from already-indexed samples. No finding is emitted without evidence.
 */
public final class DriveSignalHealthAnalyzer {

    public static final int MAX_SIGNALS = 12;
    public static final int MAX_SAMPLES = 4_096;
    static final long FLATLINE_NANOS = 3_000_000_000L;
    static final double FLATLINE_EPSILON = 1e-9;
    /** An interval longer than this multiple of the median spacing is a recording gap, not continuous time. */
    static final int RECORDING_GAP_FACTOR = 5;

    public DriveSignalHealthSummary analyze(GraphSeriesQuery query,
                                            Collection<SignalCatalogEntry> catalog,
                                            Collection<SignalCatalogKey> selectedKeys,
                                            long startNanos,
                                            long endNanos) {
        if (query == null || catalog == null || endNanos < startNanos) {
            return DriveSignalHealthSummary.none();
        }
        List<SignalCatalogEntry> targets = selectTargets(catalog, selectedKeys);
        List<DriveSignalIssue> issues = new ArrayList<>();
        for (SignalCatalogEntry entry : targets) {
            inspect(query, entry, startNanos, endNanos, issues);
        }
        return new DriveSignalHealthSummary(issues);
    }

    private static List<SignalCatalogEntry> selectTargets(Collection<SignalCatalogEntry> catalog,
                                                          Collection<SignalCatalogKey> selectedKeys) {
        LinkedHashSet<SignalCatalogKey> wanted = new LinkedHashSet<>();
        if (selectedKeys != null) {
            wanted.addAll(selectedKeys);
        }
        List<SignalCatalogEntry> out = new ArrayList<>();
        for (SignalCatalogEntry entry : catalog) {
            if (entry == null) {
                continue;
            }
            boolean selected = wanted.contains(entry.key());
            boolean diagnostic = looksDiagnostic(entry);
            if ((selected || diagnostic) && out.size() < MAX_SIGNALS) {
                out.add(entry);
            }
        }
        if (out.isEmpty()) {
            int n = 0;
            for (SignalCatalogEntry entry : catalog) {
                if (entry != null && n++ < Math.min(4, MAX_SIGNALS)) {
                    out.add(entry);
                }
            }
        }
        return out;
    }

    private static void inspect(GraphSeriesQuery query,
                                SignalCatalogEntry entry,
                                long startNanos,
                                long endNanos,
                                List<DriveSignalIssue> issues) {
        List<SignalSamplePoint> samples = query.queryRange(entry.key(), startNanos, endNanos, MAX_SAMPLES);
        long expected = Math.max(0L, endNanos - startNanos);
        if (samples == null || samples.isEmpty()) {
            issues.add(new DriveSignalIssue(entry.signalName(), DriveSignalIssueKind.MISSING_SAMPLES,
                    "No samples in the selected interval (" + formatSeconds(expected) + ")", false));
            return;
        }
        long gapLimit = recordingGapLimit(samples);
        missingGaps(entry, samples, startNanos, endNanos, gapLimit, issues);
        invalidValues(entry, samples, issues);
        flatline(entry, samples, gapLimit, issues);
        outOfRange(entry, samples, issues);
        diagnosticActive(entry, samples, issues);
    }

    private static long recordingGapLimit(List<SignalSamplePoint> samples) {
        long[] intervals = new long[Math.max(0, samples.size() - 1)];
        for (int i = 1; i < samples.size(); i++) {
            intervals[i - 1] = samples.get(i).timestampNanos() - samples.get(i - 1).timestampNanos();
        }
        long median = medianPositive(intervals);
        if (median <= 0 || median > Long.MAX_VALUE / RECORDING_GAP_FACTOR) {
            return 0;
        }
        return median * RECORDING_GAP_FACTOR;
    }

    private static void missingGaps(SignalCatalogEntry entry,
                                    List<SignalSamplePoint> samples,
                                    long startNanos,
                                    long endNanos,
                                    long gapLimit,
                                    List<DriveSignalIssue> issues) {
        if (gapLimit <= 0) {
            return;
        }
        long median = gapLimit / RECORDING_GAP_FACTOR;
        long missing = 0;
        if (samples.get(0).timestampNanos() - startNanos > gapLimit) {
            missing += samples.get(0).timestampNanos() - startNanos;
        }
        for (int i = 1; i < samples.size(); i++) {
            long interval = samples.get(i).timestampNanos() - samples.get(i - 1).timestampNanos();
            if (interval > gapLimit) {
                missing += interval - median;
            }
        }
        long last = samples.get(samples.size() - 1).timestampNanos();
        if (endNanos - last > gapLimit) {
            missing += endNanos - last;
        }
        if (missing >= 1_000_000_000L) {
            issues.add(new DriveSignalIssue(entry.signalName(), DriveSignalIssueKind.MISSING_SAMPLES,
                    "Missing data " + formatSeconds(missing), false));
        }
    }

    private static void invalidValues(SignalCatalogEntry entry,
                                      List<SignalSamplePoint> samples,
                                      List<DriveSignalIssue> issues) {
        int invalid = 0;
        for (SignalSamplePoint sample : samples) {
            if (sample.validity() == SignalValidity.INVALID_RAW_CODE
                    || sample.validity() == SignalValidity.INACTIVE_RAW_CODE
                    || sample.validity() == SignalValidity.CONVERSION_FAILED
                    || sample.validity() == SignalValidity.NON_FINITE_VALUE) {
                invalid++;
            }
        }
        if (invalid > 0) {
            issues.add(new DriveSignalIssue(entry.signalName(), DriveSignalIssueKind.INVALID_SIGNAL,
                    "Invalid status " + invalid + (invalid == 1 ? " event" : " events"), false));
        }
    }

    private static void flatline(SignalCatalogEntry entry,
                                 List<SignalSamplePoint> samples,
                                 long gapLimit,
                                 List<DriveSignalIssue> issues) {
        if (samples.size() < 8 || looksDiagnostic(entry)) {
            return;
        }
        long longest = 0;
        long run = 0;
        double anchor = Double.NaN;
        SignalSamplePoint previous = null;
        for (SignalSamplePoint sample : samples) {
            if (!sample.usableMeasurement()) {
                previous = null;
                run = 0;
                continue;
            }
            if (previous == null) {
                previous = sample;
                anchor = sample.physicalValue();
                run = 0;
                continue;
            }
            long dt = sample.timestampNanos() - previous.timestampNanos();
            boolean recordingGap = dt <= 0 || (gapLimit > 0 && dt > gapLimit);
            if (!recordingGap && Math.abs(sample.physicalValue() - anchor) <= FLATLINE_EPSILON) {
                run += dt;
                longest = Math.max(longest, run);
            } else {
                run = 0;
                anchor = sample.physicalValue();
            }
            previous = sample;
        }
        if (longest >= FLATLINE_NANOS) {
            issues.add(new DriveSignalIssue(entry.signalName(), DriveSignalIssueKind.STALE_FLATLINE,
                    "Flatline detected " + formatSeconds(longest), true));
        }
    }

    private static void outOfRange(SignalCatalogEntry entry,
                                   List<SignalSamplePoint> samples,
                                   List<DriveSignalIssue> issues) {
        Double min = entry.minPhysical();
        Double max = entry.maxPhysical();
        if (min == null || max == null || !(max > min)) {
            return;
        }
        int count = 0;
        for (SignalSamplePoint sample : samples) {
            if (!sample.usableMeasurement()) {
                continue;
            }
            double value = sample.physicalValue();
            if (value < min || value > max) {
                count++;
            }
        }
        if (count > 0) {
            issues.add(new DriveSignalIssue(entry.signalName(), DriveSignalIssueKind.OUT_OF_RANGE,
                    count + (count == 1 ? " sample" : " samples") + " outside " + min + "…" + max, false));
        }
    }

    private static void diagnosticActive(SignalCatalogEntry entry,
                                         List<SignalSamplePoint> samples,
                                         List<DriveSignalIssue> issues) {
        if (!looksDiagnostic(entry)) {
            return;
        }
        int active = 0;
        boolean categorical = false;
        boolean numeric = false;
        for (SignalSamplePoint sample : samples) {
            String label = CategoricalValue.label(
                    sample.validity(), sample.physicalValue(), sample.textualValue());
            if (label != null) {
                if (categoricalActive(label)) {
                    active++;
                    categorical = true;
                }
                continue;
            }
            if (sample.usableMeasurement() && sample.physicalValue() != 0d) {
                active++;
                numeric = true;
            }
        }
        if (active > 0) {
            issues.add(new DriveSignalIssue(entry.signalName(), DriveSignalIssueKind.DIAGNOSTIC_FAULT,
                    "Diagnostic active " + active + (active == 1 ? " sample" : " samples"), false));
            issues.add(new DriveSignalIssue(entry.signalName(), DriveSignalIssueKind.CONFIRMED_FAULT,
                    categorical && !numeric
                            ? "Diagnostic fault signal was active"
                            : "Diagnostic fault signal was non-zero", false));
        }
    }

    /**
     * Zero-equivalent labels are inactive. Known fault labels are active.
     * Any other text stays inspectable and is not turned into a numeric measurement.
     */
    private static boolean categoricalActive(String label) {
        String text = label.trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "1", "true", "on", "active", "yes", "fault", "error", "fail", "failed", "set", "high" -> true;
            default -> false;
        };
    }

    static boolean looksDiagnostic(SignalCatalogEntry entry) {
        String text = DriveSemanticResolver.tokens(entry);
        return text.contains("dtc") || text.contains("diag") || text.contains("fault")
                || (text.contains("error") && text.contains("stat"));
    }

    private static long medianPositive(long[] values) {
        List<Long> positive = new ArrayList<>();
        for (long value : values) {
            if (value > 0) {
                positive.add(value);
            }
        }
        if (positive.isEmpty()) {
            return 0;
        }
        positive.sort(Long::compareTo);
        return positive.get(positive.size() / 2);
    }

    static String formatSeconds(long nanos) {
        double seconds = nanos / 1e9;
        if (seconds >= 10) {
            return String.format(Locale.ROOT, "%.1f s", seconds);
        }
        return String.format(Locale.ROOT, "%.2f s", seconds);
    }
}
