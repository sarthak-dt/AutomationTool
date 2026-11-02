package com.stellar.platform.desktop.project;

/**
 * Per-source inventory orchestration state for Project Files (MF-3).
 * Independent of DBC matching.
 */
public enum ProjectInventoryJobState {
    ADDED,
    WAITING,
    INDEXING,
    READY,
    REQUIRES_ATTENTION,
    FAILED,
    CANCELLED,
    UNSUPPORTED;

    public String displayLabel() {
        return switch (this) {
            case ADDED -> "Added";
            case WAITING -> "Waiting";
            case INDEXING -> "Indexing";
            case READY -> "Ready";
            case REQUIRES_ATTENTION -> "Requires Attention";
            case FAILED -> "Failed";
            case CANCELLED -> "Cancelled";
            case UNSUPPORTED -> "Unsupported";
        };
    }

    public boolean terminal() {
        return this == READY
                || this == FAILED
                || this == REQUIRES_ATTENTION
                || this == CANCELLED
                || this == UNSUPPORTED;
    }
}
