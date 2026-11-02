package com.stellar.platform.analysis.diagnostics;

public record ClauseTrace(String clause, boolean passed, String observed) {
    public ClauseTrace {
        clause = clause == null ? "" : clause;
        observed = observed == null ? "" : observed;
    }
}
