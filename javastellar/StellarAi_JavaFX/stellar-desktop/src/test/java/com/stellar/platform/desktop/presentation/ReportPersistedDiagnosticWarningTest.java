package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnosticCode;
import com.stellar.platform.storage.metadata.ProcessingArtifactMetadata;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportPersistedDiagnosticWarningTest {

    @Test
    void reportWarningsUsePersistedArtifactDiagnostics() {
        List<ProcessingPlanDiagnostic> diagnostics = List.of(
                line("RECOVERY_REQUIRED: Recovery required for DG (not silent zero)"),
                line("RECOVERY_STARTED: Recovery started for DG @432"),
                line("ZERO_DECLARED_CYCLES_WITH_DATA: declaredCycleCount=0 with physical data; schema usable"),
                line("NO_VALID_RECORDS_IN_REGION: Recovery inspected a physical region but found no structurally valid CAN records"),
                line("OUT_OF_RANGE_PHYSICAL_VALUE: One or more decoded physical values lie outside the DBC-defined min/max range"));
        ProcessingArtifactMetadata valid = artifact(
                ProcessingArtifactMetadata.STATUS_VALID,
                ProcessingArtifactDiagnosticsCodec.encode(diagnostics));
        ProcessingArtifactMetadata stale = artifact(
                ProcessingArtifactMetadata.STATUS_STALE,
                ProcessingArtifactDiagnosticsCodec.encode(List.of(line("STALE_ONLY: must not appear"))));

        List<String> warnings = DesktopInvestigationService.persistedDiagnosticWarnings(List.of(valid, stale));

        assertEquals(5, warnings.size());
        assertTrue(warnings.get(0).startsWith("RECOVERY_REQUIRED:"));
        assertTrue(warnings.get(4).startsWith("OUT_OF_RANGE_PHYSICAL_VALUE:"));
        assertTrue(warnings.stream().noneMatch(line -> line.contains("STALE_ONLY")));
    }

    @Test
    void emptyArtifactDiagnosticsStayEmpty() {
        assertEquals(List.of(), DesktopInvestigationService.persistedDiagnosticWarnings(List.of()));
        assertEquals(List.of(), DesktopInvestigationService.persistedDiagnosticWarnings(List.of(
                artifact(ProcessingArtifactMetadata.STATUS_VALID, ""))));
    }

    private static ProcessingPlanDiagnostic line(String detail) {
        return ProcessingPlanDiagnostic.of(ProcessingPlanDiagnosticCode.PIPELINE_DIAGNOSTIC, null, detail);
    }

    private static ProcessingArtifactMetadata artifact(String status, String diagnostics) {
        return new ProcessingArtifactMetadata(
                "artifact-1",
                "project-1",
                "source-1",
                1,
                "",
                "run-1",
                ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                "",
                "signals/store",
                ProcessingArtifactMetadata.ARTIFACT_VERSION_CURRENT,
                "",
                "",
                178,
                293696,
                0L,
                1L,
                status,
                Instant.parse("2026-10-20T10:00:00Z"),
                "AUTO",
                "SUCCESS_WITH_WARNINGS",
                diagnostics,
                "");
    }
}
