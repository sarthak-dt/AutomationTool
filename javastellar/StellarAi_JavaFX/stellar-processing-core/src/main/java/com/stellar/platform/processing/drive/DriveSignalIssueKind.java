package com.stellar.platform.processing.drive;

/**
 * Evidence-backed classification. "Sensor failed" is never used without supporting facts.
 */
public enum DriveSignalIssueKind {
    CONFIRMED_FAULT("Confirmed Fault"),
    INVALID_SIGNAL("Invalid Signal"),
    MISSING_SAMPLES("Missing Samples"),
    STALE_FLATLINE("Stale / Flatline"),
    OUT_OF_RANGE("Out-of-range"),
    DIAGNOSTIC_FAULT("Diagnostic Fault"),
    INFERRED_ANOMALY("Inferred Anomaly");

    private final String label;

    DriveSignalIssueKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
