package com.stellar.platform.analysis.investigation.report;

/** How a quality statement was obtained. This is not a high, medium, or low severity. */
public enum QualityStatus {
    OBSERVED("Observed"),
    CALCULATED("Calculated"),
    EVALUATED("Evaluated"),
    UNAVAILABLE("Unavailable"),
    INDETERMINATE("Indeterminate"),
    INTERPRETATION("Interpretation");

    private final String label;

    QualityStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
