package com.stellar.platform.desktop.ui;

import java.time.Duration;
import java.util.Locale;

/** Shared JavaFX display formatting for desktop screens. */
public final class UiFormat {

    private UiFormat() {
    }

    public static String bytes(long size) {
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", size / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f MB", size / (1024.0 * 1024.0));
    }

    public static String duration(Duration d) {
        if (d == null) {
            return "0 ms";
        }
        long ms = Math.max(0, d.toMillis());
        if (ms < 1000) {
            return ms + " ms";
        }
        return String.format(Locale.ROOT, "%.2f s", ms / 1000.0);
    }

    public static String canId(long id) {
        return String.format(Locale.ROOT, "0x%X", id);
    }

    /**
     * Engineering-friendly master time (seconds) derived from canonical nanoseconds.
     * Uses integer arithmetic only — no double intermediate — to avoid binary FP artefacts.
     */
    public static String nanos(long nanos) {
        return secondsFromNanos(nanos);
    }

    public static String secondsFromNanos(long nanos) {
        boolean negative = nanos < 0;
        long abs = negative ? -nanos : nanos;
        long whole = abs / 1_000_000_000L;
        long frac = abs % 1_000_000_000L;
        if (frac == 0L) {
            return (negative ? "-" : "") + Long.toString(whole);
        }
        String frac9 = String.format(Locale.ROOT, "%09d", frac);
        int end = frac9.length();
        while (end > 1 && frac9.charAt(end - 1) == '0') {
            end--;
        }
        // Keep at least 6 fractional digits for typical engineering readability when present.
        if (end < 6) {
            end = 6;
        }
        return (negative ? "-" : "") + whole + '.' + frac9.substring(0, end);
    }

    /** Exact canonical nanosecond tooltip / copy text. */
    public static String nanosExactTooltip(long nanos) {
        return "timestampNanos=" + nanos + " (" + secondsFromNanos(nanos) + " s)";
    }

    public static String physicalValueExact(double value) {
        return Double.toString(value);
    }
}
