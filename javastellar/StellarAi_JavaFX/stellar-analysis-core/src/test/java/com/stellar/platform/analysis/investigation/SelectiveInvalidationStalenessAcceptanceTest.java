package com.stellar.platform.analysis.investigation;

import com.stellar.platform.analysis.diagnostics.DiagnosticRunRequest;
import com.stellar.platform.analysis.invalidate.AnalysisInvalidationKey;
import com.stellar.platform.analysis.invalidate.DiagnosticInvalidationKey;
import com.stellar.platform.analysis.model.AnalysisRequest;
import com.stellar.platform.analysis.model.AnalysisType;
import com.stellar.platform.analysis.model.TimeRangeSource;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.rules.GenericEngineeringPack;
import com.stellar.platform.analysis.workflow.StaleRunReason;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("release-acceptance")
class SelectiveInvalidationStalenessAcceptanceTest {

    @Test
    void analysisDiagnosticAndInvestigationStalenessAfterOnlyBChanges() {
        String fpA = "fp-A";
        String fpB1 = "fp-B1";
        String fpB2 = "fp-B2";
        String fpC = "fp-C";
        String fpD = "fp-D";
        Set<String> afterB = Set.of(fpA, fpB2, fpC, fpD);

        AnalysisRequest analysisA = AnalysisRequest.builder(AnalysisType.STATISTICS)
                .projectId("p")
                .sourceFingerprint(fpA)
                .artifactGeneration(1)
                .build();
        AnalysisRequest analysisB = AnalysisRequest.builder(AnalysisType.STATISTICS)
                .projectId("p")
                .sourceFingerprint(fpB1)
                .artifactGeneration(1)
                .build();
        AnalysisRequest analysisAB = AnalysisRequest.builder(AnalysisType.STATISTICS)
                .projectId("p")
                .sourceFingerprint(InvestigationStaleness.join(List.of(fpA, fpB1)))
                .artifactGeneration(1)
                .build();
        assertTrue(AnalysisInvalidationKey.of(analysisA).matches(
                AnalysisInvalidationKey.of(AnalysisRequest.builder(AnalysisType.STATISTICS)
                        .projectId("p").sourceFingerprint(fpA).artifactGeneration(1).build())),
                "analysis depending on A only stays CURRENT");
        assertFalse(AnalysisInvalidationKey.of(analysisB).matches(
                AnalysisInvalidationKey.of(AnalysisRequest.builder(AnalysisType.STATISTICS)
                        .projectId("p").sourceFingerprint(fpB2).artifactGeneration(2).build())),
                "analysis depending on B becomes STALE");
        assertFalse(AnalysisInvalidationKey.of(analysisAB).matches(
                AnalysisInvalidationKey.of(AnalysisRequest.builder(AnalysisType.STATISTICS)
                        .projectId("p")
                        .sourceFingerprint(InvestigationStaleness.join(List.of(fpA, fpB2)))
                        .artifactGeneration(2)
                        .build())),
                "analysis depending on A+B is stale after B changes");

        DiagnosticRunRequest diagA = new DiagnosticRunRequest(
                "p", "dA", GenericEngineeringPack.pack(), List.of(),
                TimeRangeSource.FULL, TimeWindow.unbounded(), Map.of(), 1L, fpA);
        DiagnosticRunRequest diagB1 = new DiagnosticRunRequest(
                "p", "dB1", GenericEngineeringPack.pack(), List.of(),
                TimeRangeSource.FULL, TimeWindow.unbounded(), Map.of(), 1L, fpB1);
        DiagnosticRunRequest diagB2 = new DiagnosticRunRequest(
                "p", "dB2", GenericEngineeringPack.pack(), List.of(),
                TimeRangeSource.FULL, TimeWindow.unbounded(), Map.of(), 2L, fpB2);
        assertEquals(fpA, DiagnosticInvalidationKey.of(diagA).sourceFingerprint());
        assertTrue(DiagnosticInvalidationKey.of(diagA).compact().equals(
                DiagnosticInvalidationKey.of(new DiagnosticRunRequest(
                        "p", "dA2", GenericEngineeringPack.pack(), List.of(),
                        TimeRangeSource.FULL, TimeWindow.unbounded(), Map.of(), 1L, fpA)).compact()));
        assertFalse(DiagnosticInvalidationKey.of(diagB1).compact()
                .equals(DiagnosticInvalidationKey.of(diagB2).compact()));
        assertTrue(StaleRunReason.staleStatus("INVALIDATED"));
        assertTrue(StaleRunReason.staleMessage(StaleRunReason.MESSAGE));

        EngineeringEvidenceItem evidenceA = new EngineeringEvidenceItem(
                "ea", "inv", EvidenceKind.ANALYSIS_RESULT, "anA", 0, false, "", fpA, 1L,
                "A", "stats", Instant.now());
        EngineeringEvidenceItem evidenceB = new EngineeringEvidenceItem(
                "eb", "inv", EvidenceKind.DIAGNOSTIC_FINDING, "fB", 1, false, "", fpB1, 1L,
                "B", "finding", Instant.now());
        List<EngineeringEvidenceItem> refreshed = InvestigationStaleness.refreshAll(
                List.of(evidenceA, evidenceB), afterB);
        assertFalse(refreshed.get(0).stale(), "Evidence A must remain CURRENT");
        assertTrue(refreshed.get(1).stale(), "Evidence B must become STALE");
        assertEquals(InvestigationHealth.PARTIALLY_STALE, InvestigationValidity.health(refreshed));
    }
}
