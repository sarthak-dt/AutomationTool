package com.stellar.platform.processing.drive;

/**
 * Braking over A→B. {@link Classification#MEASURED} requires a mapped brake signal;
 * GPS deceleration is {@link Classification#INFERRED}.
 */
public record DriveBrakingSummary(
        Classification classification,
        int eventCount,
        long activeNanos,
        Integer harshEventCount,
        String sourceSignal
) {
    public enum Classification {
        MEASURED,
        INFERRED,
        NONE
    }

    public static DriveBrakingSummary measured(int events, long activeNanos, Integer harsh, String source) {
        return new DriveBrakingSummary(Classification.MEASURED, Math.max(0, events), Math.max(0L, activeNanos),
                harsh, source == null ? "" : source);
    }

    public static DriveBrakingSummary inferred(int events, long activeNanos, Integer harsh) {
        return new DriveBrakingSummary(Classification.INFERRED, Math.max(0, events), Math.max(0L, activeNanos),
                harsh, "");
    }

    public static DriveBrakingSummary none() {
        return new DriveBrakingSummary(Classification.NONE, 0, 0L, null, "");
    }

    public boolean present() {
        return classification != Classification.NONE;
    }

    public String eventLabel() {
        return switch (classification) {
            case MEASURED -> "Braking events";
            case INFERRED -> "Inferred braking events";
            case NONE -> "Braking events";
        };
    }

    public double activeSeconds() {
        return activeNanos / 1e9;
    }
}
