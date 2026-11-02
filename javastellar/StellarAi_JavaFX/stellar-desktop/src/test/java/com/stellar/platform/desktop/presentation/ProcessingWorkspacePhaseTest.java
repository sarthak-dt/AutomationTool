package com.stellar.platform.desktop.presentation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcessingWorkspacePhaseTest {

    @Test
    void mapsExistingPageModesWithoutReplacingThem() {
        assertEquals(ProcessingWorkspacePhase.REVIEW, ProcessingWorkspacePhase.of(ProcessingPageMode.SELECTION));
        assertEquals(ProcessingWorkspacePhase.REVIEW, ProcessingWorkspacePhase.of(ProcessingPageMode.PLAN_REVIEW));
        assertEquals(ProcessingWorkspacePhase.RUNNING, ProcessingWorkspacePhase.of(ProcessingPageMode.EXECUTING));
        assertEquals(ProcessingWorkspacePhase.COMPLETED, ProcessingWorkspacePhase.of(ProcessingPageMode.EXECUTION_RESULT));
        assertEquals(ProcessingWorkspacePhase.PARTIAL, ProcessingWorkspacePhase.of(ProcessingPageMode.PARTIAL));
        assertEquals(ProcessingWorkspacePhase.REVIEW, ProcessingWorkspacePhase.of(null));
    }
}
