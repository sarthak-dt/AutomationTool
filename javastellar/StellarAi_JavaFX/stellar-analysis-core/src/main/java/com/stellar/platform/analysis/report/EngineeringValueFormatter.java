package com.stellar.platform.analysis.report;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Presentation-only numeric text. Does not round values for storage or calculation.
 * Callers choose the category from an explicit unit or metric name.
 */
public final class EngineeringValueFormatter {

    private static final double SCIENTIFIC_SMALL = 1e-6d;
    private static final double SCIENTIFIC_LARGE = 1e15d;
    private static final DecimalFormatSymbols US = DecimalFormatSymbols.getInstance(Locale.US);

    private EngineeringValueFormatter() {
    }

    public static String decimal(double value) {
        if (!Double.isFinite(value)) {
            return "—";
        }
        double abs = Math.abs(value);
        if ((abs > 0d && abs < SCIENTIFIC_SMALL) || abs >= SCIENTIFIC_LARGE) {
            return scientific(value);
        }
        if (isWhole(value) && abs >= 1000d) {
            return integer(value);
        }
        DecimalFormat format = new DecimalFormat("#,##0.######", US);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value);
    }

    public static String integer(double value) {
        if (!Double.isFinite(value)) {
            return "—";
        }
        if (Math.abs(value) >= SCIENTIFIC_LARGE) {
            return scientific(value);
        }
        DecimalFormat format = new DecimalFormat("#,##0", US);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value);
    }

    /** Timestamp counts stay in the unit the caller already has. No scaling. */
    public static String timestamp(double value) {
        if (!Double.isFinite(value)) {
            return "—";
        }
        if (isWhole(value)) {
            return integer(value);
        }
        return decimal(value);
    }

    public static String seconds(double value) {
        if (!Double.isFinite(value)) {
            return "—";
        }
        return decimal(value) + " s";
    }

    public static String nanos(double value) {
        if (!Double.isFinite(value)) {
            return "—";
        }
        return (isWhole(value) ? integer(value) : decimal(value)) + " ns";
    }

    public static String percent(double value) {
        if (!Double.isFinite(value)) {
            return "—";
        }
        return decimal(value) + " %";
    }

    public static String hertz(double value) {
        if (!Double.isFinite(value)) {
            return "—";
        }
        return decimal(value) + " Hz";
    }

    private static boolean isWhole(double value) {
        return Double.isFinite(value) && Math.abs(value) < SCIENTIFIC_LARGE && value == Math.rint(value);
    }

    private static String scientific(double value) {
        return String.format(Locale.ROOT, "%.6g", value);
    }
}
