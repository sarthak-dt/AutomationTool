package com.stellar.platform.analysis.investigation;

import java.util.Locale;

public final class EngineeringFormats {

    private EngineeringFormats() {
    }

    public static String number(double value) {
        if (!Double.isFinite(value)) {
            return "—";
        }
        double abs = Math.abs(value);
        if (abs == 0d) {
            return "0";
        }
        if (abs >= 1000d || abs < 0.01d) {
            return String.format(Locale.ROOT, "%.4g", value);
        }
        if (abs >= 100d) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    public static String withUnit(double value, String unit) {
        String n = number(value);
        if (unit == null || unit.isBlank()) {
            return n;
        }
        return n + " " + unit.trim();
    }

    /** Measurement-relative instant. The result already includes the second unit. */
    public static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.3f s", nanos / 1_000_000_000.0d);
    }

    /** Inclusive display span. Each end is formatted once by {@link #seconds(long)}. */
    public static String interval(long startNanos, long endNanos) {
        return seconds(startNanos) + " to " + seconds(endNanos);
    }

    public static String range(Long start, Long end) {
        if (start == null || end == null) {
            return "—";
        }
        return seconds(start) + " → " + seconds(end);
    }

    public static String duration(Long start, Long end) {
        if (start == null || end == null || end < start) {
            return "—";
        }
        return String.format(Locale.ROOT, "%.3f s", (end - start) / 1_000_000_000.0d);
    }
}
