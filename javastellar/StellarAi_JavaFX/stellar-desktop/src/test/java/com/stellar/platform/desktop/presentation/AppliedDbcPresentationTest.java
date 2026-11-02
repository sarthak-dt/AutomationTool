package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppliedDbcPresentationTest {

    @Test
    void oneAppliedDbcIgnoresNativeOnlyAndUnusedImports() {
        List<IndependentJobResult> jobs = List.of(
                job("src-A", 1, "def-obd", "11_bit_OBD2 2.dbc"),
                job("src-A", 2, null, ""));
        AppliedDbcPresentation.Summary summary = AppliedDbcPresentation.fromResults(jobs, "src-A");
        assertEquals("APPLIED DBC", summary.caption());
        assertEquals("11_bit_OBD2 2.dbc", summary.value());
        assertTrue(summary.includesNativeOnly());
        assertTrue(summary.detail().contains("did not use a DBC"));
        assertFalse(summary.detail().contains("unused.dbc"));
        assertEquals("2 Channels", summary.channelsLabel());
    }

    @Test
    void nativeOnlyMeasurementIsNotLabeledWithAnotherFilesDbc() {
        List<IndependentJobResult> jobs = List.of(
                job("src-A", 1, "def-obd", "11_bit_OBD2 2.dbc"),
                job("src-B", 1, null, ""));
        AppliedDbcPresentation.Summary nativeFile = AppliedDbcPresentation.fromResults(jobs, "src-B");
        assertEquals("Native only", nativeFile.value());
        assertFalse(nativeFile.value().contains("11_bit"));
    }

    @Test
    void multipleAppliedDbcsStayCompact() {
        List<IndependentJobResult> jobs = List.of(
                job("src-A", 1, "def-1", "body.dbc"),
                job("src-A", 2, "def-2", "powertrain.dbc"));
        AppliedDbcPresentation.Summary summary = AppliedDbcPresentation.fromResults(jobs, "src-A");
        assertEquals("APPLIED DBCS", summary.caption());
        assertEquals("2 DBCs applied", summary.value());
        assertTrue(summary.detail().contains("body.dbc"));
        assertTrue(summary.detail().contains("powertrain.dbc"));
        assertFalse(summary.value().contains("body.dbc"));
    }

    @Test
    void allMeasurementsCollapseToTheSingleAppliedDefinition() {
        List<IndependentJobResult> jobs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            jobs.add(job("src-" + i, 1, "def-obd", "11_bit_OBD2 2.dbc"));
            jobs.add(job("src-" + i, 2, null, ""));
        }
        AppliedDbcPresentation.Summary summary = AppliedDbcPresentation.fromResults(jobs, "");
        assertEquals("11_bit_OBD2 2.dbc", summary.value());
        assertEquals(16, summary.channelCount());
        assertEquals(8, summary.measurementCount());
        assertFalse(summary.value().contains("DBCs"));
    }

    private static IndependentJobResult job(String source, int channel, String definitionId, String name) {
        return new IndependentJobResult(
                ProcessingJobKey.of(source, channel),
                definitionId,
                name,
                definitionId == null ? MappingLifecycle.NO_MATCH : MappingLifecycle.MANUAL,
                JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                10, 10, 10, definitionId == null ? 0 : 4, 0, 1,
                1L, 2L, null, Duration.ofMillis(1), List.of(), "", new Object());
    }
}
