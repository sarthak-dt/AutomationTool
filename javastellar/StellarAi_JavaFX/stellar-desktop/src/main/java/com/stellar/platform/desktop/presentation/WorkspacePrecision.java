package com.stellar.platform.desktop.presentation;

import java.util.Locale;

/** Display-only numeric precision for physical values (never mutates decoded data). */
public enum WorkspacePrecision {
    AUTO("Auto", "Automatic significant digits"),
    FULL("Full", "Full floating-point precision"),
    DEC_2("2 dp", "2 decimal places"),
    DEC_3("3 dp", "3 decimal places"),
    DEC_4("4 dp", "4 decimal places"),
    DEC_6("6 dp", "6 decimal places");

    private final String label;
    private final String tooltip;

    WorkspacePrecision(String label, String tooltip) {
        this.label = label;
        this.tooltip = tooltip;
    }

    public String label() {
        return label;
    }

    public String tooltip() {
        return tooltip;
    }

    public String formatPhysical(double value) {
        return switch (this) {
            case FULL -> Double.toString(value);
            case DEC_2 -> String.format(Locale.ROOT, "%.2f", value);
            case DEC_3 -> String.format(Locale.ROOT, "%.3f", value);
            case DEC_4 -> String.format(Locale.ROOT, "%.4f", value);
            case DEC_6 -> String.format(Locale.ROOT, "%.6f", value);
            case AUTO -> String.format(Locale.ROOT, "%.6g", value);
        };
    }

    public String formatRaw(long raw) {
        return Long.toString(raw);
    }
}
