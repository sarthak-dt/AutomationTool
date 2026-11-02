package com.stellar.platform.processing.execution;

import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingPlanResultReconcilerTest {

    @Test
    void reusesResultForSameSourceChannelAndDefinition() {
        ProcessingJob job = job("src-a", 1, "def-1");
        IndependentJobResult stored = result(job.key(), "def-1", JobExecutionStatus.SUCCESS);
        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        registry.publishJob(stored);

        IndependentJobResult reused = ProcessingPlanResultReconciler.reusableResult(job, registry).orElseThrow();
        assertSame(stored, reused);
    }

    @Test
    void doesNotAttachResultWhenDefinitionChanged() {
        ProcessingJob job = job("src-a", 1, "def-3");
        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        registry.publishJob(result(job.key(), "def-2", JobExecutionStatus.SUCCESS));

        assertTrue(ProcessingPlanResultReconciler.reusableResult(job, registry).isEmpty());
    }

    @Test
    void identicalFilenamesDoNotReuseAcrossSourceIds() {
        ProcessingJob jobB = job("src-b", 1, "def-1");
        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        registry.publishJob(result(ProcessingJobKey.of("src-a", 1), "def-1", JobExecutionStatus.SUCCESS));

        assertTrue(ProcessingPlanResultReconciler.reusableResult(jobB, registry).isEmpty());
        assertEquals("src-a", registry.get(ProcessingJobKey.of("src-a", 1)).orElseThrow().projectSourceId());
    }

    @Test
    void preservesWarningStatusWithoutRewritingToSuccess() {
        ProcessingJob job = job("src-a", 3, "def-1");
        IndependentJobResult stored = result(job.key(), "def-1", JobExecutionStatus.SUCCESS_WITH_WARNINGS);
        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        registry.publishJob(stored);

        IndependentJobResult reused = ProcessingPlanResultReconciler.reusableResult(job, registry).orElseThrow();
        assertEquals(JobExecutionStatus.SUCCESS_WITH_WARNINGS, reused.status());
        assertFalse(reused.status() == JobExecutionStatus.SUCCESS);
        assertSame(stored, reused);
    }

    private static ProcessingJob job(String sourceId, int channel, String definitionId) {
        return new ProcessingJob(
                ProcessingJobKey.of(sourceId, channel),
                MeasurementSourceFormat.MF4,
                MappingLifecycle.CONFIRMED,
                definitionId,
                "map.dbc",
                1, 1, 0, 10, 1.0,
                null, true, List.of());
    }

    private static IndependentJobResult result(
            ProcessingJobKey key, String definitionId, JobExecutionStatus status
    ) {
        return new IndependentJobResult(
                key, definitionId, "map.dbc", MappingLifecycle.CONFIRMED, status,
                10, 10, 10, 8, 2, 1, null, null, null, Duration.ofMillis(5), List.of(), "", null);
    }
}
