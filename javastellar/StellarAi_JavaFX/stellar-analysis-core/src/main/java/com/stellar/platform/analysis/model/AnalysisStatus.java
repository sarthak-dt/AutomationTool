package com.stellar.platform.analysis.model;

public enum AnalysisStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    COMPLETED_WITH_WARNINGS,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == COMPLETED_WITH_WARNINGS || this == FAILED || this == CANCELLED;
    }

    public boolean successful() {
        return this == COMPLETED || this == COMPLETED_WITH_WARNINGS;
    }
}
