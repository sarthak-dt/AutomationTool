package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnosticCode;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectHealthStatusTest {

    @Test
    void successWithoutWarningsIsReady() {
        ProjectJourneyFacts facts = new ProjectJourneyFacts(
                true, 1, 1, true, false, false, false, true, true, false);
        ProjectJourneyState state = ProjectJourneyStateResolver.resolve(facts);
        assertEquals(ProjectJourneyState.ANALYSIS_READY, state);
        assertEquals("Ready", state.statusLabel().display());
        assertEquals("badge-ready", state.statusLabel().badgeStyleClass());
        assertTrue(state.analysisAvailable());
        assertFalse(state.readyWithWarnings());
    }

    @Test
    void informationalDiagnosticsStayReadyAndGreen() {
        ProjectJourneyFacts facts = new ProjectJourneyFacts(
                true, 1, 1, true, false, false, false, true, true, true);
        ProjectJourneyState state = ProjectJourneyStateResolver.resolve(facts);
        assertEquals(ProjectJourneyState.ANALYSIS_READY, state);
        assertEquals("Ready", state.statusLabel().display());
        assertEquals("badge-ready", state.statusLabel().badgeStyleClass());
        assertTrue(state.analysisAvailable());
        assertEquals(WorkspaceRoute.GRAPHS,
                ResumeDestinationResolver.canonicalFor(state, facts));
        ProjectJourneyFacts noGraph = new ProjectJourneyFacts(
                true, 1, 1, true, false, false, false, true, false, true);
        assertEquals(WorkspaceRoute.SIGNALS,
                ResumeDestinationResolver.canonicalFor(
                        ProjectJourneyStateResolver.resolve(noGraph), noGraph));
    }

    @Test
    void failureIsFailed() {
        ProjectJourneyFacts facts = new ProjectJourneyFacts(
                true, 1, 1, true, false, false, true, false, false, false);
        ProjectJourneyState state = ProjectJourneyStateResolver.resolve(facts);
        assertEquals(ProjectJourneyState.PROCESSING_FAILED, state);
        assertEquals("Failed", state.statusLabel().display());
        assertEquals("badge-invalid", state.statusLabel().badgeStyleClass());
    }

    @Test
    void dashboardFactsPreserveWarningStatus() {
        assertTrue(ProcessingRunStatuses.isCompletedWithWarnings(
                ProcessingRunStatuses.COMPLETED_WITH_WARNINGS));
        assertFalse(ProcessingRunStatuses.isFailed(ProcessingRunStatuses.COMPLETED_WITH_WARNINGS));
        assertFalse(ProcessingRunStatuses.isCompletedWithWarnings(
                ProcessingRunStatuses.COMPLETED_WITH_FAILURES));
    }

    @Test
    void warningReportUsesExistingDiagnostics() {
        IndependentJobResult job = new IndependentJobResult(
                ProcessingJobKey.of(SourceChannelRef.of("src-1", 1)),
                "dbc",
                "veh.dbc",
                MappingLifecycle.MANUAL,
                JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                10, 10, 10, 8, 3, 5,
                1L, 2L,
                null,
                Duration.ZERO,
                List.of(ProcessingPlanDiagnostic.of(
                        ProcessingPlanDiagnosticCode.PIPELINE_DIAGNOSTIC,
                        SourceChannelRef.of("src-1", 1),
                        "READER/RECOVERY_REQUIRED: truncated last sector")),
                "READER/RECOVERY_REQUIRED: truncated last sector; DBC/UNMATCHED_CAN_ID: 3",
                null);
        ProcessingWarningReport report = ProcessingWarningReport.fromJobs(
                List.of(job), id -> "Bengaluru.mf4");
        assertFalse(report.isEmpty());
        assertTrue(report.count() >= 2);
        assertTrue(report.grouped().containsKey(ProcessingWarningReport.Category.RECOVERED));
        assertTrue(report.grouped().containsKey(ProcessingWarningReport.Category.UNMATCHED_CAN));
        assertEquals("Bengaluru.mf4", report.items().getFirst().source());
        assertTrue(report.items().stream().allMatch(ProcessingWarningReport.Item::analysisCanContinue));
        assertFalse(report.items().stream().anyMatch(item -> item.summary().contains("COMPLETED_WITH_WARNINGS")));
    }
}
