package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.StudyMessageKey;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.processing.execution.ProcessingResultRegistry;
import com.stellar.platform.processing.plan.ChannelMappingView;
import com.stellar.platform.processing.plan.ProcessingPlanInputs;
import com.stellar.platform.processing.plan.SourcePlanInput;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingSelectionWorkspaceModelTest {

    @Test
    void selectionIdentityIsSourcePlusChannel() {
        ProcessingSelectionWorkspaceModel model = new ProcessingSelectionWorkspaceModel();
        SourceChannelRef a1 = SourceChannelRef.of("A", 1);
        SourceChannelRef b1 = SourceChannelRef.of("B", 1);
        model.select(a1);
        model.select(b1);
        assertEquals(2, model.selection().size());
        assertTrue(model.selection().contains("A", 1));
        assertTrue(model.selection().contains("B", 1));
    }

    @Test
    void duplicateSelectDoesNotGrowSelection() {
        ProcessingSelectionWorkspaceModel model = new ProcessingSelectionWorkspaceModel();
        SourceChannelRef ref = SourceChannelRef.of("A", 1);
        assertTrue(model.select(ref));
        assertFalse(model.select(ref));
        assertEquals(1, model.selection().size());
    }

    @Test
    void reviewPlanCreatesNewSnapshotAndSwitchesMode() {
        ProcessingSelectionWorkspaceModel model = new ProcessingSelectionWorkspaceModel();
        model.select(SourceChannelRef.of("A", 1));
        ProcessingPlanInputs inputs = confirmedInputs();
        ProjectProcessingPlan first = model.reviewPlan(inputs);
        assertEquals(ProcessingPageMode.PLAN_REVIEW, model.mode());
        assertEquals(1, first.jobs().size());
        ProjectProcessingPlan second = model.rebuildReviewedPlan(inputs);
        assertNotSame(first, second);
        assertEquals(ProcessingPageMode.PLAN_REVIEW, model.mode());
    }

    @Test
    void backToSelectionPreservesSelection() {
        ProcessingSelectionWorkspaceModel model = new ProcessingSelectionWorkspaceModel();
        model.select(SourceChannelRef.of("A", 1));
        model.select(SourceChannelRef.of("B", 2));
        model.reviewPlan(confirmedInputs());
        model.backToSelection();
        assertEquals(ProcessingPageMode.SELECTION, model.mode());
        assertEquals(2, model.selection().size());
        assertTrue(model.selection().contains("A", 1));
        assertTrue(model.selection().contains("B", 2));
    }

    @Test
    void recommendedMappingIsNotAutoConfirmedViaBuilder() {
        ProcessingSelectionWorkspaceModel model = new ProcessingSelectionWorkspaceModel();
        model.select(SourceChannelRef.of("A", 1));
        ChannelMappingView recommended = new ChannelMappingView(
                SourceChannelRef.of("A", 1),
                MappingLifecycle.RECOMMENDED,
                "def-1",
                "obd.dbc",
                3, 3, 1.0, true);
        ProcessingPlanInputs inputs = new ProcessingPlanInputs(
                List.of(new SourcePlanInput("A", 0, inventoryReady("a.mf4", channel(1, 100)), null)),
                List.of(recommended));
        ProjectProcessingPlan plan = model.reviewPlan(inputs);
        assertEquals(MappingLifecycle.RECOMMENDED, plan.jobs().getFirst().mappingLifecycle());
        assertFalse(plan.jobs().getFirst().executable());
        assertEquals(ProcessingPlanReadiness.REQUIRES_CONFIRMATION, plan.readiness());
    }

    @Test
    void studySetRemainsIndependentOfProcessingSelection() {
        InventoryStudySet study = new InventoryStudySet();
        ProcessingSelectionWorkspaceModel model = new ProcessingSelectionWorkspaceModel();
        ProjectSourceId src = ProjectSourceId.generate();
        AtomicInteger studyAdds = new AtomicInteger();
        // Study set membership must not populate processing selection.
        assertEquals(0, study.size());
        assertTrue(model.selection().isEmpty());
        model.select(SourceChannelRef.of(src.value(), 1));
        assertEquals(1, model.selection().size());
        assertEquals(0, study.size());
        studyAdds.incrementAndGet();
        assertEquals(1, studyAdds.get());
        assertTrue(study.isEmpty());
        // Explicit study membership still does not touch processing selection size beyond existing.
        SourceChannelKey key = SourceChannelKey.of(src, 1);
        assertFalse(study.contains(StudyMessageKey.of(key, 0x100, false)));
        model.clearSelection();
        assertTrue(model.selection().isEmpty());
        assertEquals(0, study.size());
    }

    @Test
    void hydrateRestoresCompletedResultsWithoutRerun() {
        ProcessingSelectionWorkspaceModel model = new ProcessingSelectionWorkspaceModel();
        model.select(SourceChannelRef.of("A", 1));
        model.select(SourceChannelRef.of("B", 2));
        ProcessingPlanInputs inputs = confirmedInputs();
        ProjectProcessingPlan plan = model.reviewPlan(inputs);
        IndependentJobResult a = stored(plan.jobs().get(0).key(), "d", JobExecutionStatus.SUCCESS);
        IndependentJobResult b = stored(plan.jobs().get(1).key(), "d", JobExecutionStatus.SUCCESS_WITH_WARNINGS);
        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        registry.publishJob(a);
        registry.publishJob(b);

        model.hydrateFromRegistry(inputs, registry);
        assertEquals(ProcessingPageMode.EXECUTION_RESULT, model.mode());
        assertEquals(2, model.executionJobs().size());
        assertSame(a, model.executionJobs().get(0).result().orElseThrow());
        assertEquals(JobExecutionStatus.SUCCESS_WITH_WARNINGS, model.executionJobs().get(1).status());
    }

    @Test
    void forgetRemovedSourceInvalidatesReviewedPlan() {
        ProcessingSelectionWorkspaceModel model = new ProcessingSelectionWorkspaceModel();
        model.select(SourceChannelRef.of("A", 1));
        model.select(SourceChannelRef.of("B", 2));
        ProjectProcessingPlan plan = model.reviewPlan(confirmedInputs());
        assertEquals(2, plan.jobs().size());
        model.forgetRemovedSource("B");
        assertTrue(model.reviewedPlan().isEmpty(), "stale reviewed plan must not survive removal");
        assertTrue(model.executionPlanSnapshot().isEmpty());
        assertFalse(model.reviewedPlanContainsInactiveSources(List.of("A")));
    }

    @Test
    void rebuildReviewedPlanForSourcesDropsRemovedChannels() {
        ProcessingSelectionWorkspaceModel model = new ProcessingSelectionWorkspaceModel();
        model.select(SourceChannelRef.of("A", 1));
        model.select(SourceChannelRef.of("B", 2));
        model.reviewPlan(confirmedInputs());
        ProjectProcessingPlan rebuilt = model.rebuildReviewedPlanForSources(confirmedInputs(), List.of("A"));
        assertEquals(1, rebuilt.jobs().size());
        assertEquals("A", rebuilt.jobs().getFirst().projectSourceId());
        assertFalse(model.reviewedPlanContainsInactiveSources(List.of("A")));
    }

    @Test
    void hydrateDoesNotReviveSnapshotWithRemovedSource() {
        ProcessingSelectionWorkspaceModel model = new ProcessingSelectionWorkspaceModel();
        model.select(SourceChannelRef.of("A", 1));
        model.select(SourceChannelRef.of("B", 2));
        ProjectProcessingPlan plan = model.reviewPlan(confirmedInputs());
        model.beginExecution(plan);
        model.completeExecution(new com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult(
                plan.planId(), java.time.Instant.parse("2026-10-12T00:00:00Z"),
                com.stellar.platform.domain.processing.plan.ProjectExecutionStatus.SUCCESS,
                List.of(), List.of(), Duration.ZERO));
        model.forgetRemovedSource("B");
        model.retainLivingSources(List.of("A"));
        ProcessingPlanInputs onlyA = new ProcessingPlanInputs(
                List.of(new SourcePlanInput("A", 0, inventoryReady("a.mf4", channel(1, 50)), null)),
                List.of(new ChannelMappingView(SourceChannelRef.of("A", 1), MappingLifecycle.CONFIRMED,
                        "d", "a.dbc", 1, 1, 1.0, true)));
        ProjectProcessingPlan hydrated = model.hydrateFromRegistry(onlyA, new ProcessingResultRegistry());
        assertTrue(hydrated.jobs().stream().noneMatch(job -> "B".equals(job.projectSourceId())));
    }

    @Test
    void hydrateLeavesRunningExecutionUntouched() {
        ProcessingSelectionWorkspaceModel model = new ProcessingSelectionWorkspaceModel();
        model.select(SourceChannelRef.of("A", 1));
        ProjectProcessingPlan plan = model.reviewPlan(confirmedInputs());
        model.beginExecution(plan);
        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        registry.publishJob(stored(plan.jobs().getFirst().key(), "d", JobExecutionStatus.SUCCESS));

        ProjectProcessingPlan after = model.hydrateFromRegistry(confirmedInputs(), registry);
        assertEquals(plan, after);
        assertEquals(ProcessingPageMode.EXECUTING, model.mode());
        assertEquals(JobExecutionStatus.QUEUED, model.executionJobs().getFirst().status());
    }

    @Test
    void cancelActiveExecutionMarksNonTerminalJobsCancelledAndLeavesExecuting() {
        ProcessingSelectionWorkspaceModel model = new ProcessingSelectionWorkspaceModel();
        model.select(SourceChannelRef.of("A", 1));
        model.select(SourceChannelRef.of("B", 2));
        ProjectProcessingPlan plan = model.reviewPlan(confirmedInputs());
        model.beginExecution(plan);
        model.updateJobStatus(plan.jobs().getFirst().key(), JobExecutionStatus.RUNNING);
        model.cancelActiveExecution();
        assertEquals(ProcessingPageMode.EXECUTION_RESULT, model.mode());
        assertEquals(JobExecutionStatus.CANCELLED, model.executionJobs().get(0).status());
        assertEquals(JobExecutionStatus.CANCELLED, model.executionJobs().get(1).status());
        assertEquals(0, model.pendingJobCount());
        assertEquals(0, model.activeJobCount());
        model.cancelActiveExecution();
        assertEquals(JobExecutionStatus.CANCELLED, model.executionJobs().get(0).status());
    }

    @Test
    void hydrateDoesNotAttachChangedDefinitionResult() {
        ProcessingSelectionWorkspaceModel model = new ProcessingSelectionWorkspaceModel();
        model.select(SourceChannelRef.of("A", 1));
        model.select(SourceChannelRef.of("B", 2));
        ProcessingPlanInputs original = confirmedInputs();
        ProjectProcessingPlan plan = model.reviewPlan(original);
        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        registry.publishJob(stored(plan.jobs().get(0).key(), "d", JobExecutionStatus.SUCCESS));
        registry.publishJob(stored(plan.jobs().get(1).key(), "d", JobExecutionStatus.SUCCESS));

        ProcessingPlanInputs changedB = new ProcessingPlanInputs(
                original.sources(),
                List.of(
                        new ChannelMappingView(SourceChannelRef.of("A", 1), MappingLifecycle.CONFIRMED,
                                "d", "a.dbc", 1, 1, 1.0, true),
                        new ChannelMappingView(SourceChannelRef.of("B", 2), MappingLifecycle.CONFIRMED,
                                "def-3", "other.dbc", 1, 1, 1.0, true)
                )
        );
        model.hydrateFromRegistry(changedB, registry);
        assertEquals(ProcessingPageMode.PARTIAL, model.mode());
        assertEquals(1, model.executionJobs().size());
        assertEquals("A", model.executionJobs().getFirst().jobKey().projectSourceId());
        assertEquals(1, model.executionJobs().getFirst().jobKey().channelId());
    }

    private static IndependentJobResult stored(
            ProcessingJobKey key, String definitionId, JobExecutionStatus status
    ) {
        return new IndependentJobResult(
                key, definitionId, "a.dbc", MappingLifecycle.CONFIRMED, status,
                10, 10, 10, 8, 2, 1, null, null, null, Duration.ofMillis(9), List.of(), "", null);
    }

    private static ProcessingPlanInputs confirmedInputs() {
        return new ProcessingPlanInputs(
                List.of(
                        new SourcePlanInput("A", 0, inventoryReady("a.mf4", channel(1, 50)), null),
                        new SourcePlanInput("B", 1, inventoryReady("b.mf4", channel(2, 20)), null)
                ),
                List.of(
                        new ChannelMappingView(SourceChannelRef.of("A", 1), MappingLifecycle.CONFIRMED,
                                "d", "a.dbc", 1, 1, 1.0, true),
                        new ChannelMappingView(SourceChannelRef.of("B", 2), MappingLifecycle.CONFIRMED,
                                "d", "a.dbc", 1, 1, 1.0, true)
                )
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