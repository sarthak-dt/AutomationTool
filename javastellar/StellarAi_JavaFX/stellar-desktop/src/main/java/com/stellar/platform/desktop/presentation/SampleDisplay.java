package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.CategoricalValue;
import com.stellar.platform.domain.EnumeratedState;
import com.stellar.platform.domain.SignalValidity;

import java.util.Locale;

/**
 * Display strings for one sample. Categorical labels stay text. Numeric formatting
 * does not change the stored value.
 */
public final class SampleDisplay {

    private SampleDisplay() {
    }

    public static String physical(Double physical, String text, WorkspacePrecision precision) {
        return physical(physical, null, text, null, precision);
    }

    /**
     * Physical column. A value-table sample is {@code raw = label}. The stored
     * physical number is unchanged and is what an unlabeled sample still shows.
     */
    public static String physical(
            Double physical,
            Long raw,
            String text,
            SignalValidity validity,
            WorkspacePrecision precision
    ) {
        WorkspacePrecision format = precision == null ? WorkspacePrecision.AUTO : precision;
        double number = physical == null ? Double.NaN : physical;
        String state = EnumeratedState.codeAndText(raw, validity, number, text);
        if (state != null) {
            return state;
        }
        String label = CategoricalValue.label(
                validity == null ? SignalValidity.VALID : validity, number, text);
        if (label != null) {
            return label;
        }
        if (physical == null) {
            return AlignedSampleRow.MISSING;
        }
        return format.formatPhysical(physical);
    }

    /** Raw code, with the decoded label beside it when one exists. */
    public static String raw(Long raw, String text, WorkspacePrecision precision) {
        if (raw == null) {
            return AlignedSampleRow.MISSING;
        }
        WorkspacePrecision format = precision == null ? WorkspacePrecision.AUTO : precision;
        String rendered = format.formatRaw(raw);
        if (text == null || text.isBlank()) {
            return rendered;
        }
        return rendered + " (" + text.trim() + ")";
    }

    public static String pointer(
            String signal,
            String measurement,
            String unit,
            double physical,
            String text,
            long timestampNanos
    ) {
        String label = CategoricalValue.label(SignalValidity.VALID, physical, text);
        if (label != null) {
            String who = measurement == null || measurement.isBlank()
                    ? signal
                    : signal + " · " + measurement;
            return String.format(Locale.ROOT, "Pointer: %.6f s · %s · %s", timestampNanos / 1e9, who, label);
        }
        String shownUnit = unit == null || unit.isBlank() ? "" : " " + unit;
        String value = Double.isFinite(physical) ? String.format(Locale.ROOT, "%.6g", physical) : AlignedSampleRow.MISSING;
        return String.format(Locale.ROOT, "Pointer: %.6f s · %s ≈ %s%s",
                timestampNanos / 1e9, signal == null ? "" : signal, value, shownUnit);
    }

    /**
     * Hover text that keeps the display-axis pointer separate from the stored sample.
     * A bucket value is named as nearest, minimum, or maximum at the sample timestamp.
     * No clock is called UTC here; callers pass recording or display-axis nanoseconds as-is.
     */
    public static String provenance(
            String signal,
            String measurement,
            String unit,
            double physical,
            String text,
            long pointerNanos,
            long sampleNanos,
            long bucketStartNanos,
            long bucketEndNanos,
            long bucketSamples,
            String role,
            String validityName,
            Long raw
    ) {
        String kind = role == null || role.isBlank() ? "exact" : role;
        boolean exact = "exact".equals(kind);
        boolean sameInstant = pointerNanos == sampleNanos;
        StringBuilder out = new StringBuilder("Pointer: ");
        if (exact && sameInstant) {
            out.append(String.format(Locale.ROOT, "sample %.6f s", sampleNanos / 1e9));
        } else if (exact) {
            out.append(String.format(Locale.ROOT, "axis %.6f s · sample %.6f s",
                    pointerNanos / 1e9, sampleNanos / 1e9));
        } else {
            out.append(String.format(Locale.ROOT, "axis %.6f s · %s sample %.6f s",
                    pointerNanos / 1e9, kind, sampleNanos / 1e9));
            if (bucketSamples > 1) {
                out.append(String.format(Locale.ROOT, " · bucket %.6f–%.6f s · %d samples",
                        bucketStartNanos / 1e9, bucketEndNanos / 1e9, bucketSamples));
            }
        }
        out.append(" · ").append(signal == null ? "" : signal);
        if (measurement != null && !measurement.isBlank()) {
            out.append(" · ").append(measurement);
        }
        SignalValidity validity = validityName == null || validityName.isBlank()
                ? SignalValidity.VALID
                : validityOf(validityName);
        String state = EnumeratedState.codeAndText(raw, validity, physical, text);
        if (state == null) {
            String stateText = EnumeratedState.text(validity, physical, text);
            if (stateText != null) {
                state = stateText;
            }
        }
        if (state != null) {
            out.append(" · ").append(state);
            if (validityName != null && !validityName.isBlank()
                    && !SignalValidity.VALID.name().equals(validityName)) {
                out.append(" · ").append(validityName);
            }
            return out.toString();
        }
        String label = CategoricalValue.label(validity, physical, text);
        if (label != null) {
            out.append(" · ").append(label);
            if (raw != null) {
                out.append(" · raw ").append(raw);
            }
            if (validityName != null && !validityName.isBlank()) {
                out.append(" · ").append(validityName);
            }
            return out.toString();
        }
        String shownUnit = unit == null || unit.isBlank() ? "" : " " + unit;
        String value = Double.isFinite(physical)
                ? String.format(Locale.ROOT, "%.6g", physical)
                : AlignedSampleRow.MISSING;
        out.append(" · ").append(value).append(shownUnit);
        if (validityName != null && !validityName.isBlank()
                && !SignalValidity.VALID.name().equals(validityName)) {
            out.append(" · ").append(validityName);
        }
        return out.toString();
    }

    private static SignalValidity validityOf(String name) {
        try {
            return SignalValidity.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return SignalValidity.VALID;
        }
    }

    public static String hover(double physical, String text, String unit, SignalValidity validity) {
        String state = EnumeratedState.text(validity, physical, text);
        if (state != null) {
            return state;
        }
        String label = CategoricalValue.label(
                validity == null ? SignalValidity.VALID : validity, physical, text);
        if (label != null) {
            return label;
        }
        String suffix = unit == null || unit.isBlank() ? "" : " " + unit;
        if (validity != null && !validity.usableWith(physical)) {
            String number = Double.isFinite(physical) ? String.format(Locale.ROOT, "%.6g", physical) : AlignedSampleRow.MISSING;
            return number + " " + validity.name() + suffix;
        }
        if (!Double.isFinite(physical)) {
            return AlignedSampleRow.MISSING;
        }
        return String.format(Locale.ROOT, "%.6g%s", physical, suffix);
    }
}
