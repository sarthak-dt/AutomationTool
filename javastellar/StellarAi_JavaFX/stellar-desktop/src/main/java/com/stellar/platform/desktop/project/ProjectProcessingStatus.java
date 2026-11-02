package com.stellar.platform.desktop.project;

/**
 * Coarse project-level processing status for the workspace.
 */
public enum ProjectProcessingStatus {
    IDLE,
    READY,
    RUNNING,
    COMPLETED,
    COMPLETED_WITH_WARNINGS,
    NO_CAN_DATA,
    RECOVERY_FAILED,
    UNSUPPORTED_STRUCTURE,
    FAILED,
    CANCELLED
}
