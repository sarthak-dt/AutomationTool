package com.stellar.platform.analysis.investigation;

/** How a report statement was obtained. Renderers print this label and do not invent another kind. */
public enum ReportFactKind {
    MEASURED("Measured"),
    CALCULATED("Calculated"),
    RULE_RESULT("Rule result"),
    ENGINEER_INTERPRETATION("Engineer interpretation"),
    ASSUMPTION("Assumption"),
    UNAVAILABLE("Unavailable");

    private final String label;

    ReportFactKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
