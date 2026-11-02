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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingDetailsPresentationTest {

    @Test
    void groupsRepeatedPipelineCodesAndKeepsOriginals() {
        IndependentJobResult job = job(List.of(
                pipeline("UNMATCHED_CAN_ID", "no matching DBC message"),
                pipeline("UNMATCHED_CAN_ID", "no matching DBC message"),
                pipeline("UNMATCHED_CAN_ID", "no matching DBC message"),
                pipeline("OUT_OF_RANGE_PHYSICAL_VALUE", "value outside min/max"),
                pipeline("EXTENDED_UNFINALIZED_DT_RECOVERY", "recovery boundary extended")));
        ProcessingDetailsPresentation.Model model = ProcessingDetailsPresentation.fromJobs(
                "Source 1", "file.mf4", "COMPLETED", "2",
                "11_bit_OBD2 2.dbc", "100", "80", "12", "00:03", List.of(job));
        assertEquals(3, model.groups().size());
        ProcessingDetailsPresentation.DiagnosticGroup unmatched = model.groups().getFirst();
        assertEquals("UNMATCHED_CAN_ID", unmatched.code());
        assertEquals(3, unmatched.occurrences());
        assertEquals(3, unmatched.originals().size());
        assertEquals("Warning", unmatched.severity());
        assertEquals("Recovery", model.groups().get(2).severity());
        assertEquals(5, model.warningCount());
        String copy = ProcessingDetailsPresentation.copyText(model);
        assertTrue(copy.contains("Source: Source 1"));
        assertTrue(copy.contains("Elapsed: 00:03"));
        assertTrue(copy.contains("Occurrences: 3"));
        assertTrue(copy.contains("Raw Details"));
        assertTrue(copy.contains("UNMATCHED_CAN_ID"));
    }

    @Test
    void emptyAndSingleAndManyDiagnosticsStayGrouped() {
        assertEquals(0, ProcessingDetailsPresentation.fromJobs(
                "s", "", "Completed", "0", "", "0", "0", "0", "—", List.of()).warningCount());
        IndependentJobResult one = job(List.of(pipeline("VLSD_RECORD_SKIPPED", "skipped")));
        assertEquals(1, ProcessingDetailsPresentation.fromJobs(
                "s", "", "COMPLETED", "1", "dbc", "1", "1", "1", "00:01",
                List.of(one)).warningCount());
        List<ProcessingPlanDiagnostic> many = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            many.add(pipeline("UNMATCHED_CAN_ID", "id=" + (i % 3)));
        }
        ProcessingDetailsPresentation.Model hundred = ProcessingDetailsPresentation.fromJobs(
                "s", "", "COMPLETED", "1", "dbc", "1", "1", "1", "00:02",
                List.of(job(many)));
        assertEquals(120, hundred.warningCount());
        assertEquals(3, hundred.groups().size());
    }

    private static IndependentJobResult job(List<ProcessingPlanDiagnostic> diagnostics) {
        return new IndependentJobResult(
                ProcessingJobKey.of("src-a", 1),
                "d",
                "11_bit_OBD2 2.dbc",
                MappingLifecycle.CONFIRMED,
                JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                100, 100, 100, 80, 20, 12,
                1L, 2L, null, Duration.ofSeconds(3),
                diagnostics,
                "READER/RECOVERY_COMPLETED: done · shared source routed pass",
                null);
    }

    private static ProcessingPlanDiagnostic pipeline(String code, String message) {
        return ProcessingPlanDiagnostic.of(
                ProcessingPlanDiagnosticCode.PIPELINE_DIAGNOSTIC,
                SourceChannelRef.of("src-a", 1),
                code + ": " + message);
    }
}
