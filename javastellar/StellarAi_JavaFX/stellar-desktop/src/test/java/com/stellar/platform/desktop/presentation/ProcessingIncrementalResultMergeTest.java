package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnosticCode;
import com.stellar.platform.domain.processing.plan.ProjectExecutionStatus;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.processing.execution.ProcessingResultRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingIncrementalResultMergeTest {

    @Test
    void incrementalPublishPreservesPriorWarningStatus() {
        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        IndependentJobResult a = result("A", 1, JobExecutionStatus.SUCCESS, List.of());
        IndependentJobResult b = result("B", 1, JobExecutionStatus.SUCCESS_WITH_WARNINGS, List.of(
                ProcessingPlanDiagnostic.of(
                        ProcessingPlanDiagnosticCode.PIPELINE_DIAGNOSTIC,
                        SourceChannelRef.of("B", 1),
                        "LOW_DBC_MATCH: coverage below threshold")));
        IndependentJobResult c = result("C", 1, JobExecutionStatus.SUCCESS, List.of());
        registry.publishJob(a);
        registry.publishJob(b);
        registry.publishJob(c);

        IndependentJobResult d = result("D", 1, JobExecutionStatus.SUCCESS, List.of());
        MultiSourceProcessingResult batch = new MultiSourceProcessingResult(
                "plan-d",
                java.time.Instant.now(),
                ProjectExecutionStatus.SUCCESS,
                List.of(d),
                List.of(),
                Duration.ZERO);
        registry.publishBatch(batch);

        assertEquals(JobExecutionStatus.SUCCESS, registry.get(a.jobKey()).orElseThrow().status());
        assertEquals(JobExecutionStatus.SUCCESS_WITH_WARNINGS, registry.get(b.jobKey()).orElseThrow().status());
        assertEquals(JobExecutionStatus.SUCCESS, registry.get(c.jobKey()).orElseThrow().status());
        assertEquals(JobExecutionStatus.SUCCESS, registry.get(d.jobKey()).orElseThrow().status());
        assertEquals(4, registry.all().size());

        assertEquals(ProcessingRunStatuses.COMPLETED,
                ProcessingRunStatuses.forJobs(ProjectExecutionStatus.SUCCESS, registry.all()));
    }

    @Test
    void explicitReprocessReplacesOnlyThatSource() {
        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        IndependentJobResult bWarn = result("B", 1, JobExecutionStatus.SUCCESS_WITH_WARNINGS, List.of(
                ProcessingPlanDiagnostic.of(
                        ProcessingPlanDiagnosticCode.FRAME_COUNT_MISMATCH,
                        SourceChannelRef.of("B", 1),
                        "selected!=expected")));
        registry.publishJob(bWarn);

        IndependentJobResult bOk = result("B", 1, JobExecutionStatus.SUCCESS, List.of());
        registry.publishJob(bOk);

        assertEquals(JobExecutionStatus.SUCCESS, registry.get(bWarn.jobKey()).orElseThrow().status());
        assertTrue(registry.get(bWarn.jobKey()).orElseThrow().diagnostics().isEmpty());
    }

    @Test
    void aggregateStatusRules() {
        IndependentJobResult a = result("A", 1, JobExecutionStatus.SUCCESS, List.of());
        IndependentJobResult bOk = result("B", 1, JobExecutionStatus.SUCCESS, List.of());
        IndependentJobResult bWarn = result("B", 1, JobExecutionStatus.SUCCESS_WITH_WARNINGS, List.of(
                ProcessingPlanDiagnostic.of(ProcessingPlanDiagnosticCode.PIPELINE_DIAGNOSTIC,
                        SourceChannelRef.of("B", 1), "warn")));
        IndependentJobResult bFail = result("B", 1, JobExecutionStatus.FAILED, List.of());

        assertEquals(ProcessingRunStatuses.COMPLETED,
                ProcessingRunStatuses.forJobs(ProjectExecutionStatus.SUCCESS, List.of(a, bOk)));
        assertEquals(ProcessingRunStatuses.COMPLETED,
                ProcessingRunStatuses.forJobs(ProjectExecutionStatus.SUCCESS, List.of(a, bWarn)));
        assertEquals(ProcessingRunStatuses.COMPLETED_WITH_FAILURES,
                ProcessingRunStatuses.forJobs(ProjectExecutionStatus.PARTIAL_FAILURE, List.of(a, bFail)));
    }

    @Test
    void warningReasonSurfacesInSourceAssembler() {
        IndependentJobResult b = result("B", 1, JobExecutionStatus.SUCCESS_WITH_WARNINGS, List.of(
                ProcessingPlanDiagnostic.of(
                        ProcessingPlanDiagnosticCode.PIPELINE_DIAGNOSTIC,
                        SourceChannelRef.of("B", 1),
                        "Low DBC match coverage")));
        QuickDecodeSourceResultAssembler.SourceResult source =
                new QuickDecodeSourceResultAssembler().assembleResults(List.of(b)).getFirst();
        assertEquals("COMPLETED", source.resultLabel());
        assertEquals("badge-ready", source.resultStyle());
        assertTrue(source.primaryDiagnosticReason().contains("Low DBC match coverage"));
        assertTrue(source.resultDisplayText().contains("Low DBC match coverage"));
        assertTrue(source.resultDisplayText().startsWith("COMPLETED"));
    }

    @Test
    void diagnosticsSurviveCodecRoundTrip() {
        List<ProcessingPlanDiagnostic> original = List.of(
                ProcessingPlanDiagnostic.of(
                        ProcessingPlanDiagnosticCode.PIPELINE_DIAGNOSTIC,
                        SourceChannelRef.of("B", 1),
                        "detail Y"));
        String encoded = ProcessingArtifactDiagnosticsCodec.encode(original);
        List<ProcessingPlanDiagnostic> decoded = ProcessingArtifactDiagnosticsCodec.decode(encoded);
        assertEquals(1, decoded.size());
        assertEquals(ProcessingPlanDiagnosticCode.PIPELINE_DIAGNOSTIC, decoded.getFirst().code());
        assertEquals("detail Y", decoded.getFirst().detail());
        assertEquals(JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                ProcessingArtifactDiagnosticsCodec.parseStatus("SUCCESS_WITH_WARNINGS"));
    }

    @Test
    void warningDiagnosticsSurvivePersistEncodeAndReopenSeed() {
        List<ProcessingPlanDiagnostic> warnings = List.of(
                ProcessingPlanDiagnostic.of(
                        ProcessingPlanDiagnosticCode.PIPELINE_DIAGNOSTIC,
                        SourceChannelRef.of("B", 1),
                        "Low DBC match coverage"));
        IndependentJobResult live = result("B", 1, JobExecutionStatus.SUCCESS_WITH_WARNINGS, warnings);
        String encoded = ProcessingArtifactDiagnosticsCodec.encode(live.diagnostics());
        JobExecutionStatus status = ProcessingArtifactDiagnosticsCodec.parseStatus(live.status().name());
        List<ProcessingPlanDiagnostic> reopened = ProcessingArtifactDiagnosticsCodec.decode(encoded);

        assertEquals(JobExecutionStatus.SUCCESS_WITH_WARNINGS, status);
        assertEquals(1, reopened.size());
        assertEquals("Low DBC match coverage", reopened.getFirst().detail());

        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        IndependentJobResult a = result("A", 1, JobExecutionStatus.SUCCESS, List.of());
        IndependentJobResult c = result("C", 1, JobExecutionStatus.SUCCESS, List.of());
        IndependentJobResult bSeeded = new IndependentJobResult(
                live.jobKey(),
                "dbc",
                "veh.dbc",
                MappingLifecycle.MANUAL,
                status,
                10, 10, 10, 8, 2, 5,
                1L, 2L,
                null,
                Duration.ZERO,
                reopened,
                reopened.getFirst().detail(),
                null);
        registry.publishJob(a);
        registry.publishJob(bSeeded);
        registry.publishJob(c);

        IndependentJobResult d = result("D", 1, JobExecutionStatus.SUCCESS, List.of());
        registry.publishBatch(new MultiSourceProcessingResult(
                "plan-d",
                java.time.Instant.now(),
                ProjectExecutionStatus.SUCCESS,
                List.of(d),
                List.of(),
                Duration.ZERO));

        assertEquals(JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                registry.get(bSeeded.jobKey()).orElseThrow().status());
        assertEquals("Low DBC match coverage",
                registry.get(bSeeded.jobKey()).orElseThrow().diagnostics().getFirst().detail());
        assertEquals(ProcessingRunStatuses.COMPLETED,
                ProcessingRunStatuses.forJobs(ProjectExecutionStatus.SUCCESS, registry.all()));
    }

    private static IndependentJobResult result(
            String sourceId,
            int channel,
            JobExecutionStatus status,
            List<ProcessingPlanDiagnostic> diagnostics
    ) {
        return new IndependentJobResult(
                ProcessingJobKey.of(SourceChannelRef.of(sourceId, channel)),
                "dbc",
                "veh.dbc",
                MappingLifecycle.MANUAL,
                status,
                10, 10, 10, 8, 2, 5,
                1L, 2L,
                null,
                Duration.ZERO,
                diagnostics,
                diagnostics.isEmpty() ? "" : diagnostics.getFirst().detail(),
                null
        );
    }
}
