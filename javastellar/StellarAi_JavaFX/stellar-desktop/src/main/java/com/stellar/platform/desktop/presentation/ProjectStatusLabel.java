package com.stellar.platform.desktop.presentation;

/**
 * The only project statuses shown to users. Internal journey/processing state names are
 * never rendered in the UI.
 */
public enum ProjectStatusLabel {
    SETUP("Setup", "badge-idle"),
    READY_TO_PROCESS("Ready to Process", "badge-running"),
    PROCESSING("Processing", "badge-running"),
    ANALYSIS_READY("Ready", "badge-ready"),
    ANALYSIS_READY_WITH_WARNINGS("Ready with warnings", "badge-warning"),
    FAILED("Failed", "badge-invalid"),
    ACTION_REQUIRED("Needs attention", "badge-attention");

    private final String display;
    private final String badgeStyleClass;

    ProjectStatusLabel(String display, String badgeStyleClass) {
        this.display = display;
        this.badgeStyleClass = badgeStyleClass;
    }

    public String display() {
        return display;
    }

    public String badgeStyleClass() {
        return badgeStyleClass;
    }
}
