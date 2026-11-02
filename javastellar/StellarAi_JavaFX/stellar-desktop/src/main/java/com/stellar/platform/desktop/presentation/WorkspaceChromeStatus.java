package com.stellar.platform.desktop.presentation;

/**
 * Presentation copy for chrome that must stay consistent: header chip, sidebar,
 * footer, and project-dependent actions. Projects existing journey/health text;
 * it does not invent new business states.
 */
public final class WorkspaceChromeStatus {

    public static final String NO_ACTIVE_PROJECT = "No active project";
    public static final String ZERO_SAVED_PROJECTS = "0 saved projects";
    public static final String NEED_PROJECT = "Open or create a project first";

    private WorkspaceChromeStatus() {
    }

    public static String chip(boolean projectOpen, String healthDisplay, boolean processing) {
        if (!projectOpen) {
            return NO_ACTIVE_PROJECT;
        }
        if (processing) {
            return ProjectStatusLabel.PROCESSING.display();
        }
        return nonBlank(healthDisplay, ProjectStatusLabel.SETUP.display());
    }

    public static String readiness(boolean projectOpen, int savedCount, String healthDisplay, boolean processing) {
        if (!projectOpen) {
            return savedCount <= 0 ? ZERO_SAVED_PROJECTS : NO_ACTIVE_PROJECT;
        }
        return chip(true, healthDisplay, processing);
    }

    public static String projectName(boolean projectOpen, String name) {
        return projectOpen && name != null && !name.isBlank() ? name : NO_ACTIVE_PROJECT;
    }

    public static String gatedActionReason(boolean projectOpen, String journeyAction) {
        if (!projectOpen) {
            return NEED_PROJECT;
        }
        return nonBlank(journeyAction, NEED_PROJECT);
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
