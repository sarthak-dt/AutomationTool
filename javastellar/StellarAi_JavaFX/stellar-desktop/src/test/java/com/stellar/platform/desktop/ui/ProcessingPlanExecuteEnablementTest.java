package com.stellar.platform.desktop.ui;

import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingPlanExecuteEnablementTest {

    @Test
    void readyExecutablePlanEnablesExecute() {
        ProcessingJob job = job(SourceChannelRef.of("A", 1), MappingLifecycle.CONFIRMED, true);
        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "p", Instant.now(), List.of(job), ProcessingPlanReadiness.READY, List.of());
        assertTrue(ProcessingPlanReviewPane.isExecuteEnabled(plan));
    }

    @Test
    void nonReadyOrBlockedJobDisablesExecute() {
        ProcessingJob recommended = job(SourceChannelRef.of("A", 1), MappingLifecycle.RECOMMENDED, false);
        ProjectProcessingPlan blocked = new ProjectProcessingPlan(
                "p", Instant.now(), List.of(recommended),
                ProcessingPlanReadiness.REQUIRES_CONFIRMATION, List.of());
        assertFalse(ProcessingPlanReviewPane.isExecuteEnabled(blocked));

        ProcessingJob readyButNotExec = job(SourceChannelRef.of("A", 1), MappingLifecycle.CONFIRMED, false);
        ProjectProcessingPlan mixed = new ProjectProcessingPlan(
                "p2", Instant.now(), List.of(readyButNotExec),
                ProcessingPlanReadiness.READY, List.of());
        assertFalse(ProcessingPlanReviewPane.isExecuteEnabled(mixed));

        assertFalse(ProcessingPlanReviewPane.isExecuteEnabled(ProjectProcessingPlan.empty()));
    }

    private static ProcessingJob job(SourceChannelRef channel, MappingLifecycle life, boolean executable) {
        return new ProcessingJob(
                ProcessingJobKey.of(channel),
                MeasurementSourceFormat.MF4,
                life,
                "def",
                "map.dbc",
                1, 1, 0, 10, 1.0,
                null,
                executable,
                List.of()
        );
    }
}
