package com.stellar.platform.analysis.investigation.eval;

/** One stored sample. {@code state} is blank when the sample has only a numeric value. */
public record EvaluationSample(long timestampNanos, double value, boolean usable, String state) {
    public EvaluationSample {
        state = state == null ? "" : state.strip();
    }
}
