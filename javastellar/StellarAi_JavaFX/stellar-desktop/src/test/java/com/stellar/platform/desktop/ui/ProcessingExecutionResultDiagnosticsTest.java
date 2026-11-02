package com.stellar.platform.desktop.ui;

import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnosticCode;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingExecutionResultDiagnosticsTest {

    @Test
    void userFacingStatusMatchesJobExecutionStatus() {
        assertEquals("COMPLETED", ProcessingExecutionResultPane.userFacingStatus(JobExecutionStatus.SUCCESS));
        assertEquals("COMPLETED",
                ProcessingExecutionResultPane.userFacingStatus(JobExecutionStatus.SUCCESS_WITH_WARNINGS));
        assertEquals("ERROR", ProcessingExecutionResultPane.userFacingStatus(JobExecutionStatus.FAILED));
        assertEquals("CANCELLED", ProcessingExecutionResultPane.userFacingStatus(JobExecutionStatus.CANCELLED));
        assertEquals("PROCESSING", ProcessingExecutionResultPane.userFacingStatus(JobExecutionStatus.RUNNING));
    }

    @Test
    void warningAndFailureResultsExposeDiagnosticsAndTechnicalDetail() {
        ProcessingPlanDiagnostic mismatch = ProcessingPlanDiagnostic.of(
                ProcessingPlanDiagnosticCode.FRAME_COUNT_MISMATCH,
                SourceChannelRef.of("src-B", 2),
                "expected 100 actual 98");
        IndependentJobResult warned = result(
                JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                List.of(mismatch),
                "parser noted truncated block",
                new InMemorySignalCatalogStore("b.mf4", "veh.dbc", 10));
        assertEquals(JobExecutionStatus.SUCCESS_WITH_WARNINGS, warned.status());
        assertEquals(1, warned.diagnostics().size());
        assertEquals(ProcessingPlanDiagnosticCode.FRAME_COUNT_MISMATCH, warned.diagnostics().getFirst().code());
        assertTrue(warned.diagnostics().getFirst().detail().contains("expected 100"));
        assertEquals("parser noted truncated block", warned.technicalDetail());
        assertTrue(warned.successful());
        assertTrue(warned.catalogHandle().isPresent());

        IndependentJobResult failed = result(
                JobExecutionStatus.FAILED,
                List.of(ProcessingPlanDiagnostic.of(ProcessingPlanDiagnosticCode.SOURCE_NOT_READY)),
                "I/O failed opening source",
                null);
        assertEquals(JobExecutionStatus.FAILED, failed.status());
        assertFalse(failed.successful());
        assertTrue(failed.catalogHandle().isEmpty());
        assertEquals("I/O failed opening source", failed.technicalDetail());
        assertFalse(failed.diagnostics().isEmpty());
    }

    @Test
    void successWithoutDiagnosticsHasNoFakeErrorMessage() {
        IndependentJobResult ok = result(JobExecutionStatus.SUCCESS, List.of(), "",
                new InMemorySignalCatalogStore("a.mf4", "veh.dbc", 10));
        assertTrue(ok.diagnostics().isEmpty());
        assertTrue(ok.technicalDetail().isBlank());
        assertTrue(ok.successful());
        assertTrue(ok.catalogHandle().isPresent());
    }

    private static IndependentJobResult result(
            JobExecutionStatus status,
            List<ProcessingPlanDiagnostic> diagnostics,
            String technical,
            Object catalog
    ) {
        return new IndependentJobResult(
                ProcessingJobKey.of("src", 1),
                "def",
                "veh.dbc",
                MappingLifecycle.RECOMMENDED_FALLBACK,
                status,
                100, 100, 100, 90, 10, catalog == null ? 0 : 1,
                1L, 2L, null, Duration.ofMillis(5), diagnostics, technical, catalog);
    }
}
