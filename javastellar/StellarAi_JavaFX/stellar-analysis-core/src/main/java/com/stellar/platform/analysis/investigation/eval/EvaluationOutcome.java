package com.stellar.platform.analysis.investigation.eval;

/** Rule outcome. These labels are not a vehicle pass or fail. */
public enum EvaluationOutcome {
    MEETS_CRITERION("Meets criterion"),
    DOES_NOT_MEET_CRITERION("Does not meet criterion"),
    INDETERMINATE("Indeterminate"),
    NOT_EVALUATED("Not evaluated");

    private final String label;

    EvaluationOutcome(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static EvaluationOutcome parse(String text) {
        if (text == null || text.isBlank()) {
            return NOT_EVALUATED;
        }
        for (EvaluationOutcome outcome : values()) {
            if (outcome.name().equals(text) || outcome.label.equals(text)) {
                return outcome;
            }
        }
        return NOT_EVALUATED;
    }
}
