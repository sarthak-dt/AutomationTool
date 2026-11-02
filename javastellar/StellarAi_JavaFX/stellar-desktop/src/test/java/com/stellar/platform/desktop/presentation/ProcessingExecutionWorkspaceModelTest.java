package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectExecutionStatus;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.processing.plan.ChannelMappingView;
import com.stellar.platform.processing.plan.ProcessingPlanInputs;
import com.stellar.platform.processing.plan.SourcePlanInput;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingExecutionWorkspaceModelTest {

    @Test
    void beginExecutionQueuesJobsAndBlocksBack() {
        ProcessingSelectionWorkspaceModel model = new ProcessingSelectionWorkspaceModel();
        model.select(SourceChannelRef.of("A", 1));
        ProjectProcessingPlan plan = model.reviewPlan(confirmedInputs());
        model.beginExecution(plan);

        assertEquals(ProcessingPageMode.EXECUTING, model.mode());
        assertEquals(plan, model.executionPlanSnapshot());
        assertEquals(1, model.executionJobs().size());
        assertEquals(JobExecutionStatus.QUEUED, model.executionJobs().getFirst().status());
        assertFalse(model.canNavigateAwayFromExecution());

        model.backToSelection();
        assertEquals(ProcessingPageMode.EXECUTING, model.mode());
        assertEquals(1, model.selection().size());
    }

    @Test
    void updateAndCompleteTransitionsToResult() {
        ProcessingSelectionWorkspaceModel model = new ProcessingSelectionWorkspaceModel();
        model.select(SourceChannelRef.of("A", 1));
        ProjectProcessingPlan plan = model.reviewPlan(confirmedInputs());
        model.beginExecution(plan);

        ProcessingJobKey key = plan.jobs().getFirst().key();
        model.updateJobStatus(key, JobExecutionStatus.RUNNING);
        assertEquals(JobExecutionStatus.RUNNING, model.executionJobs().getFirst().status());

        IndependentJobResult result = new IndependentJobResult(
                key, "d", "a.dbc", MappingLifecycle.CONFIRMED, JobExecutionStatus.SUCCESS,
                50, 50, 50, 40, 10, 5, null, null, null, Duration.ofMillis(12), List.of(), "", null);
        model.updateJobStatus(key, result);

        MultiSourceProcessingResult batch = new MultiSourceProcessingResult(
                plan.planId(), Instant.now(), ProjectExecutionStatus.SUCCESS,
                List.of(result), List.of(), Duration.ofMillis(12));
        model.completeExecution(batch);

        assertEquals(ProcessingPageMode.EXECUTION_RESULT, model.mode());
        assertTrue(model.canNavigateAwayFromExecution());
        assertEquals(batch, model.latestBatch());
        assertEquals(1, model.successfulJobCount());

        model.backToSelection();
        assertEquals(ProcessingPageMode.SELECTION, model.mode());
        assertEquals(1, model.selection().size());
    }

    @Test
    void markPlanRequiresReviewSetsMessageAndMode() {
        ProcessingSelectionWorkspaceModel model = new ProcessingSelectionWorkspaceModel();
        model.select(SourceChannelRef.of("A", 1));
        model.reviewPlan(confirmedInputs());
        model.markPlanRequiresReview("PLAN REQUIRES REVIEW");
        assertEquals(ProcessingPageMode.PLAN_REVIEW, model.mode());
        assertEquals("PLAN REQUIRES REVIEW", model.planRequiresReviewMessage());
    }

    @Test
    void markExecutionFailedLeavesJobsFailedInsteadOfReady() {
        ProcessingSelectionWorkspaceModel model = new ProcessingSelectionWorkspaceModel();
        model.select(SourceChannelRef.of("A", 1));
        ProjectProcessingPlan plan = model.reviewPlan(confirmedInputs());
        model.beginExecution(plan);

        IndependentJobResult failed = new IndependentJobResult(
                plan.jobs().getFirst().key(), null, "", MappingLifecycle.NO_MATCH,
                JobExecutionStatus.FAILED,
                50, 0, 0, 0, 0, 0, null, null, null, Duration.ZERO,
                List.of(), "Isolated processing requires a saved project database", null);
        MultiSourceProcessingResult batch = new MultiSourceProcessingResult(
                plan.planId(), Instant.now(), ProjectExecutionStatus.FAILED,
                List.of(failed), List.of(), Duration.ZERO);
        model.markExecutionFailed("PLAN REQUIRES REVIEW — execution failed", batch);

        assertEquals(ProcessingPageMode.PLAN_REVIEW, model.mode());
        assertEquals("PLAN REQUIRES REVIEW — execution failed", model.planRequiresReviewMessage());
        assertEquals(JobExecutionStatus.FAILED, model.executionJobs().getFirst().status());
        assertEquals(1, model.failedJobCount());
        assertEquals(0, model.successfulJobCount());
    }

    private static ProcessingPlanInputs confirmedInputs() {
        return new ProcessingPlanInputs(
                List.of(new SourcePlanInput("A", 0, inventoryReady("a.mf4", channel(1, 50)), null)),
                List.of(new ChannelMappingView(
                        SourceChannelRef.of("A", 1), MappingLifecycle.CONFIRMED,
                        "d", "a.dbc", 1, 1, 1.0, true))
        );
    }

    private static CanSourceInventory inventoryReady(String name, CanChannelInventory... channels) {
        long frames = 0;
        long ids = 0;
        for (CanChannelInventory ch : channels) {
            frames += ch.frameCount();
            ids += ch.uniqueMessageCount();
        }
        return new CanSourceInventory(
                Path.of(name), name, MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                channels.length, frames, ids, true, List.of(channels), List.of());
    }

    private static CanChannelInventory channel(int id, long frames) {
        return new CanChannelInventory(
                id, "Channel " + id, frames, 1, 1, 0, 0,
                List.of(new CanMessageInventory(new CanMessageKey(id, 0x100, false), 10, 8, 8, false)));
    }
}
