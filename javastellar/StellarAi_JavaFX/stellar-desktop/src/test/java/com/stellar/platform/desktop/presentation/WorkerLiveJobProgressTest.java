package com.stellar.platform.desktop.presentation;

import com.stellar.platform.api.worker.WorkerMessage;
import com.stellar.platform.api.worker.WorkerProtocol;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.processing.execution.PlanJobLifecycleListener;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Isolated-worker PROGRESS events must move the matching job row live, not only the first job,
 * and in-job frame counts must update the model without adding change notifications.
 */
class WorkerLiveJobProgressTest {

    @Test
    void jobEventsUpdateTheNamedRowLive() {
        ProcessingJob a1 = job("A", 1);
        ProcessingJob a2 = job("A", 2);
        ProcessingJob b1 = job("B", 1);
        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "p", Instant.now(), List.of(a1, a2, b1), ProcessingPlanReadiness.READY, List.of());
        ProcessingSelectionWorkspaceModel model = new ProcessingSelectionWorkspaceModel();
        model.beginExecution(plan);
        List<String> started = new ArrayList<>();
        PlanJobLifecycleListener life = new PlanJobLifecycleListener() {
            @Override
            public void onJobStarting(ProcessingJob job, int index, int total) {
                started.add(job.key() + "@" + index + "/" + total);
                model.updateJobStatus(job.key(), JobExecutionStatus.RUNNING);
            }

            @Override
            public void onJobFinished(IndependentJobResult result, int index, int total) {
            }
        };

        DesktopAppService.applyWorkerJobProgress(event(WorkerProtocol.STAGE_JOB_STARTED, b1), plan, life, model);
        assertEquals(List.of(b1.key() + "@2/3"), started);
        assertEquals(JobExecutionStatus.QUEUED, status(model, a1));
        assertEquals(JobExecutionStatus.RUNNING, status(model, b1));

        DesktopAppService.applyWorkerJobProgress(event("SUCCESS_WITH_WARNINGS", b1), plan, life, model);
        assertEquals(JobExecutionStatus.SUCCESS_WITH_WARNINGS, status(model, b1));
        assertEquals(1, model.completedJobCount());

        DesktopAppService.applyWorkerJobProgress(event(WorkerProtocol.STAGE_JOB_STARTED, a1), plan, life, model);
        DesktopAppService.applyWorkerJobProgress(event(WorkerProtocol.STAGE_JOB_STARTED, a2), plan, life, model);
        assertEquals(JobExecutionStatus.RUNNING, status(model, a1));
        assertEquals(JobExecutionStatus.RUNNING, status(model, a2));

        DesktopAppService.applyWorkerJobProgress(event("FAILED", a2), plan, life, model);
        assertEquals(JobExecutionStatus.FAILED, status(model, a2));
        assertEquals(1, model.failedJobCount());
    }

    @Test
    void unknownKeysAndNonStatusStagesAreIgnored() {
        ProcessingJob a1 = job("A", 1);
        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "p", Instant.now(), List.of(a1), ProcessingPlanReadiness.READY, List.of());
        ProcessingSelectionWorkspaceModel model = new ProcessingSelectionWorkspaceModel();
        model.beginExecution(plan);
        AtomicInteger changes = new AtomicInteger();
        model.addListener(changes::incrementAndGet);

        DesktopAppService.applyWorkerJobProgress(event(WorkerProtocol.STAGE_JOB_STARTED, job("Z", 9)),
                plan, PlanJobLifecycleListener.NOOP, model);
        DesktopAppService.applyWorkerJobProgress(event("REUSED", a1), plan, PlanJobLifecycleListener.NOOP, model);
        DesktopAppService.applyWorkerJobProgress(event("RUNNING", a1), plan, PlanJobLifecycleListener.NOOP, model);
        DesktopAppService.applyWorkerJobProgress(event("Resuming", a1), plan, PlanJobLifecycleListener.NOOP, model);

        assertEquals(JobExecutionStatus.QUEUED, status(model, a1));
        assertEquals(0, changes.get());
    }

    @Test
    void inJobFramesUpdateModelWithoutNotifying() {
        ProcessingJob a1 = job("A", 1);
        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "p", Instant.now(), List.of(a1), ProcessingPlanReadiness.READY, List.of());
        ProcessingSelectionWorkspaceModel model = new ProcessingSelectionWorkspaceModel();
        model.beginExecution(plan);
        AtomicInteger changes = new AtomicInteger();
        model.addListener(changes::incrementAndGet);

        WorkerMessage frames = new WorkerMessage(
                WorkerProtocol.PROGRESS, WorkerProtocol.VERSION, null, 1L, "t", null, null,
                WorkerProtocol.STAGE_JOB_PROGRESS, 1_234_567L, null, null,
                null, null, null, null, null, null, null, true);
        DesktopAppService.applyWorkerJobProgress(frames, plan, PlanJobLifecycleListener.NOOP, model);

        assertEquals(1_234_567L, model.liveSourceFramesRead());
        assertEquals(0, changes.get());

        DesktopAppService.applyWorkerJobProgress(event(WorkerProtocol.STAGE_JOB_STARTED, a1),
                plan, PlanJobLifecycleListener.NOOP, model);
        assertEquals(0L, model.liveSourceFramesRead());
    }

    private static JobExecutionStatus status(ProcessingSelectionWorkspaceModel model, ProcessingJob job) {
        return model.executionJobs().stream()
                .filter(row -> row.jobKey().equals(job.key()))
                .findFirst()
                .orElseThrow()
                .status();
    }

    private static WorkerMessage event(String stage, ProcessingJob job) {
        return new WorkerMessage(
                WorkerProtocol.PROGRESS, WorkerProtocol.VERSION, null, 1L, "t", null, 0.5d,
                stage, null, job.key().toString(), null,
                null, null, null, null, null, null, null, true);
    }

    private static ProcessingJob job(String source, int channel) {
        return new ProcessingJob(
                ProcessingJobKey.of(SourceChannelRef.of(source, channel)),
                MeasurementSourceFormat.MF4,
                MappingLifecycle.CONFIRMED,
                "def",
                "map.dbc",
                1, 1, 0, 10, 1.0,
                null,
                true,
                List.of()
        );
    }
}
