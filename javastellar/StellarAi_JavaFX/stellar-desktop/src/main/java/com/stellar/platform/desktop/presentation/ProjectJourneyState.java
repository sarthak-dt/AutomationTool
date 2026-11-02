package com.stellar.platform.desktop.presentation;

/**
 * Presentation-level journey state of a project. This is a projection over existing domain
 * processing state — it does not replace {@code ProjectProcessingStatus},
 * {@code ProcessingPageMode} or any execution enum.
 */
public enum ProjectJourneyState {
    NEW(ProjectStatusLabel.SETUP, "Add Sources"),
    SOURCES_REQUIRED(ProjectStatusLabel.ACTION_REQUIRED, "Add Sources"),
    DIRECT_PROCESS(ProjectStatusLabel.READY_TO_PROCESS, "Direct Processing"),
    DIRECT_RESULT(ProjectStatusLabel.ACTION_REQUIRED, "Review Result"),
    DECODE_SETUP_REQUIRED(ProjectStatusLabel.SETUP, "Continue Setup"),
    READY_TO_PROCESS(ProjectStatusLabel.READY_TO_PROCESS, "Review & Process"),
    PROCESSING(ProjectStatusLabel.PROCESSING, "Resume Processing"),
    PROCESSING_FAILED(ProjectStatusLabel.FAILED, "Review Issue"),
    ANALYSIS_READY(ProjectStatusLabel.ANALYSIS_READY, "Resume Analysis"),
    ANALYSIS_READY_WITH_WARNINGS(ProjectStatusLabel.ANALYSIS_READY_WITH_WARNINGS, "Resume Analysis");

    private final ProjectStatusLabel statusLabel;
    private final String primaryActionLabel;

    ProjectJourneyState(ProjectStatusLabel statusLabel, String primaryActionLabel) {
        this.statusLabel = statusLabel;
        this.primaryActionLabel = primaryActionLabel;
    }

    public ProjectStatusLabel statusLabel() {
        return statusLabel;
    }

    /** Projects Home card action, e.g. "Continue Setup" or "Resume Analysis". */
    public String primaryActionLabel() {
        return primaryActionLabel;
    }

    public boolean analysisAvailable() {
        return this == ANALYSIS_READY || this == ANALYSIS_READY_WITH_WARNINGS;
    }

    public boolean readyWithWarnings() {
        return this == ANALYSIS_READY_WITH_WARNINGS;
    }
}
