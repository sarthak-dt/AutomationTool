package com.stellar.platform.desktop.project;

/**
 * Validation lifecycle for a project file entry.
 */
public enum FileValidationStatus {
    READY,
    READY_WITH_WARNINGS,
    VALIDATING,
    INVALID,
    UNSUPPORTED;

    public boolean processingAllowed() {
        return this == READY || this == READY_WITH_WARNINGS;
    }
}
