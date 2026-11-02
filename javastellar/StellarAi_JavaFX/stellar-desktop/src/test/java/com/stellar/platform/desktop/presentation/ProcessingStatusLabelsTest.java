package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnosticCode;
import com.stellar.platform.domain.processing.plan.ProjectExecutionStatus;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingStatusLabelsTest {

    @Test
    void informationalSuccessIsCompletedAndKeepsTheDiagnostic() {
        IndependentJobResult job = job(JobExecutionStatus.SUCCESS_WITH_WARNINGS, "UNMATCHED_CAN_ID: no DBC message");
        assertEquals(ProcessingStatusLabels.COMPLETED, ProcessingStatusLabels.forJob(job.status()));
        assertEquals(ProcessingStatusLabels.STYLE_COMPLETED, ProcessingStatusLabels.styleForJob(job.status()));
        assertEquals("UNMATCHED_CAN_ID: no DBC message", job.diagnostics().getFirst().detail());
        assertEquals(ProcessingRunStatuses.COMPLETED,
                ProcessingRunStatuses.forJobs(ProjectExecutionStatus.SUCCESS, List.of(job)));
        assertEquals(ProcessingStatusLabels.COMPLETED,
                ProcessingStatusLabels.forStored(ProcessingRunStatuses.COMPLETED_WITH_WARNINGS));
        assertTrue(ProcessingRunStatuses.isCompleted(ProcessingRunStatuses.COMPLETED_WITH_WARNINGS));
        assertFalse(ProcessingRunStatuses.isFailed(ProcessingRunStatuses.COMPLETED_WITH_WARNINGS));
    }

    @Test
    void requiredFailureIsErrorAndCancellationStaysSeparate() {
        assertEquals(ProcessingStatusLabels.ERROR, ProcessingStatusLabels.forJob(JobExecutionStatus.FAILED));
        assertEquals(ProcessingStatusLabels.STYLE_ERROR, ProcessingStatusLabels.styleForJob(JobExecutionStatus.FAILED));
        assertEquals(ProcessingStatusLabels.ERROR,
                ProcessingStatusLabels.forProject(ProjectProcessingStatus.FAILED));
        assertEquals(ProcessingStatusLabels.ERROR,
                ProcessingStatusLabels.forProject(ProjectProcessingStatus.NO_CAN_DATA));
        assertEquals(ProcessingStatusLabels.ERROR,
                ProcessingStatusLabels.forStored(ProcessingRunStatuses.COMPLETED_WITH_FAILURES));
        assertEquals(ProcessingStatusLabels.CANCELLED, ProcessingStatusLabels.forJob(JobExecutionStatus.CANCELLED));
        assertEquals(ProcessingStatusLabels.PROCESSING, ProcessingStatusLabels.forJob(JobExecutionStatus.RUNNING));
        assertEquals(ProcessingStatusLabels.COMPLETED,
                ProcessingStatusLabels.forProject(ProjectProcessingStatus.COMPLETED_WITH_WARNINGS));
    }

    @Test
    void aFailedJobIsNotReportedAsSuccess() {
        IndependentJobResult failed = job(JobExecutionStatus.FAILED, "parser rejected the measurement");
        String stored = ProcessingRunStatuses.forJobs(ProjectExecutionStatus.SUCCESS, List.of(
                job(JobExecutionStatus.SUCCESS, ""),
                failed));
        assertEquals(ProcessingRunStatuses.COMPLETED_WITH_FAILURES, stored);
        assertEquals(ProcessingStatusLabels.ERROR, ProcessingStatusLabels.forStored(stored));
        assertTrue(ProcessingRunStatuses.isFailed(stored));
        assertEquals("parser rejected the measurement", failed.diagnostics().getFirst().detail());
    }

    private static IndependentJobResult job(JobExecutionStatus status, String detail) {
        return new IndependentJobResult(
                ProcessingJobKey.of(SourceChannelRef.of("src", 1)),
                "dbc",
                "veh.dbc",
                MappingLifecycle.MANUAL,
                status,
                10, 10, 10, 4, 6, 3,
                1L,
                2L,
                null,
                Duration.ofSeconds(1),
                detail.isBlank() ? List.of() : List.of(ProcessingPlanDiagnostic.of(
                        ProcessingPlanDiagnosticCode.PIPELINE_DIAGNOSTIC,
                        SourceChannelRef.of("src", 1),
                        detail)),
                "",
                null);
    }
}
