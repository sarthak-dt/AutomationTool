package com.stellar.platform.desktop.presentation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkspaceChromeStatusTest {

    @Test
    void noProjectUsesExistingEmptyCopy() {
        assertEquals("No active project", WorkspaceChromeStatus.chip(false, "Ready", false));
        assertEquals("0 saved projects", WorkspaceChromeStatus.readiness(false, 0, "Ready", false));
        assertEquals("No active project", WorkspaceChromeStatus.readiness(false, 3, "Ready", false));
        assertEquals("No active project", WorkspaceChromeStatus.projectName(false, "BMW"));
    }

    @Test
    void openProjectReusesHealthAndProcessingLabels() {
        assertEquals("Ready", WorkspaceChromeStatus.chip(true, "Ready", false));
        assertEquals("Ready with warnings", WorkspaceChromeStatus.readiness(true, 2, "Ready with warnings", false));
        assertEquals(ProjectStatusLabel.PROCESSING.display(),
                WorkspaceChromeStatus.chip(true, "Ready", true));
        assertEquals("BMW", WorkspaceChromeStatus.projectName(true, "BMW"));
    }

    @Test
    void gatedActionsReuseExistingNeedProjectCopy() {
        assertEquals(WorkspaceChromeStatus.NEED_PROJECT,
                WorkspaceChromeStatus.gatedActionReason(false, "Add Sources"));
        assertEquals("Add Sources", WorkspaceChromeStatus.gatedActionReason(true, "Add Sources"));
    }
}
