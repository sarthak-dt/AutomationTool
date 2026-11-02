package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectChannelSelection;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.processing.execution.ProcessingPlanResultReconciler;
import com.stellar.platform.processing.execution.ProcessingResultRegistry;
import com.stellar.platform.processing.plan.ProcessingPlanBuilder;
import com.stellar.platform.processing.plan.ProcessingPlanInputs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Desktop workspace state for Processing Selection, plan review, and execution.
 * Selection identity is {@link SourceChannelRef}; Study Set is never coupled here.
 */
public final class ProcessingSelectionWorkspaceModel {

    public record Snapshot(
            ProcessingPageMode mode,
            DecisionWorkspaceMode decisionOrigin,
            List<SourceChannelRef> selectedChannels,
            ProjectProcessingPlan reviewedPlan,
            ProjectProcessingPlan executionPlanSnapshot,
            MultiSourceProcessingResult latestBatch
    ) {
    }

    private final ProjectChannelSelection selection = new ProjectChannelSelection();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile ProcessingPageMode mode = ProcessingPageMode.SELECTION;
    /**
     * Immutable workflow provenance for the currently reviewed/executed plan.
     * It is captured when the user leaves Decision Workspace and is intentionally
     * independent from whatever mode the user may choose later.
     */
    private volatile DecisionWorkspaceMode decisionOrigin = DecisionWorkspaceMode.QUICK_DECODE;
    private volatile ProjectProcessingPlan reviewedPlan = ProjectProcessingPlan.empty();
    private volatile ProjectProcessingPlan executionPlanSnapshot = ProjectProcessingPlan.empty();
    private volatile MultiSourceProcessingResult latestBatch;
    private volatile List<ExecutionJobPresentation> executionJobs = List.of();
    private volatile String planRequiresReviewMessage;
    private volatile long liveSourceFramesRead;
    private volatile long executionStartedNanos;

    public ProcessingSelectionWorkspaceModel() {
        selection.addListener(this::fireChanged);
    }

    public ProjectChannelSelection selection() {
        return selection;
    }

    public ProcessingPageMode mode() {
        return mode;
    }

    /** Workflow mode that created the current processing plan/result. */
    public DecisionWorkspaceMode decisionOrigin() {
        return decisionOrigin;
    }

    /**
     * Capture the mode before building a processing plan. This is provenance, not a UI preference:
     * subsequent Decision Workspace mode changes must not alter how an existing result is presented.
     */
    public void captureDecisionOrigin(DecisionWorkspaceMode origin) {
        decisionOrigin = origin == null ? DecisionWorkspaceMode.QUICK_DECODE : origin;
    }

    public ProjectProcessingPlan reviewedPlan() {
        return reviewedPlan;
    }

    public ProjectProcessingPlan executionPlanSnapshot() {
        return executionPlanSnapshot;
    }

    public MultiSourceProcessingResult latestBatch() {
        return latestBatch;
    }

    public List<ExecutionJobPresentation> executionJobs() {
        return executionJobs;
    }

    public String planRequiresReviewMessage() {
        return planRequiresReviewMessage;
    }

    public void clearPlanRequiresReviewMessage() {
        if (planRequiresReviewMessage != null) {
            planRequiresReviewMessage = null;
            fireChanged();
        }
    }

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public boolean select(SourceChannelRef channel) {
        return selection.select(channel);
    }

    public boolean deselect(SourceChannelRef channel) {
        return selection.deselect(channel);
    }

    public void setSelected(SourceChannelRef channel, boolean selected) {
        Objects.requireNonNull(channel, "channel");
        if (selected) {
            selection.select(channel);
        } else {
            selection.deselect(channel);
        }
    }

    public int selectAll(Collection<SourceChannelRef> channels) {
        return selection.selectAll(channels);
    }

    public void clearSelection() {
        selection.clear();
    }

    /** Reset selection, mode, reviewed plan, and execution state (new project). */
    public void clear() {
        boolean hadSelection = !selection.isEmpty();
        boolean modeChange = mode != ProcessingPageMode.SELECTION;
        boolean originChange = decisionOrigin != DecisionWorkspaceMode.QUICK_DECODE;
        boolean planChange = !reviewedPlan.isEmpty()
                || reviewedPlan.readiness() != ProcessingPlanReadiness.EMPTY
                || !executionPlanSnapshot.isEmpty()
                || latestBatch != null
                || !executionJobs.isEmpty()
                || planRequiresReviewMessage != null;
        mode = ProcessingPageMode.SELECTION;
        decisionOrigin = DecisionWorkspaceMode.QUICK_DECODE;
        reviewedPlan = ProjectProcessingPlan.empty();
        executionPlanSnapshot = ProjectProcessingPlan.empty();
        latestBatch = null;
        executionJobs = List.of();
        planRequiresReviewMessage = null;
        if (hadSelection) {
            selection.clear();
            if (modeChange || originChange || planChange) {
                fireChanged();
            }
        } else if (modeChange || originChange || planChange) {
            fireChanged();
        }
    }

    public int retainLivingSources(Collection<String> livingSourceIds) {
        return selection.retainSources(livingSourceIds);
    }

    /**
     * Drops a removed measurement from the current Processing Complete view.
     * Does not start a new run and does not change remaining job results.
     */
    public void forgetRemovedSource(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return;
        }
        boolean changed = false;
        List<ExecutionJobPresentation> kept = new ArrayList<>();
        for (ExecutionJobPresentation row : executionJobs) {
            if (row != null && sourceId.equals(row.jobKey().projectSourceId())) {
                changed = true;
                continue;
            }
            kept.add(row);
        }
        if (changed) {
            executionJobs = List.copyOf(kept);
        }
        if (latestBatch != null) {
            List<IndependentJobResult> remaining = latestBatch.jobResults().stream()
                    .filter(result -> result != null && !sourceId.equals(result.projectSourceId()))
                    .toList();
            if (remaining.size() != latestBatch.jobResults().size()) {
                latestBatch = new MultiSourceProcessingResult(
                        latestBatch.planId(),
                        latestBatch.createdAt(),
                        latestBatch.status(),
                        remaining,
                        latestBatch.diagnostics(),
                        latestBatch.elapsed());
                changed = true;
            }
        }
        if (executionPlanSnapshot != null && executionPlanSnapshot.jobs().stream()
                .anyMatch(job -> sourceId.equals(job.projectSourceId()))) {
            executionPlanSnapshot = ProjectProcessingPlan.empty();
            changed = true;
        }
        if (reviewedPlan != null && reviewedPlan.jobs().stream()
                .anyMatch(job -> sourceId.equals(job.projectSourceId()))) {
            reviewedPlan = ProjectProcessingPlan.empty();
            changed = true;
        }
        if (changed) {
            fireChanged();
        }
    }

    /** True when the reviewed snapshot still lists a source that is no longer in the workspace. */
    public boolean reviewedPlanContainsInactiveSources(Collection<String> livingSourceIds) {
        return planContainsInactiveSources(reviewedPlan, livingSourceIds);
    }

    static boolean planContainsInactiveSources(ProjectProcessingPlan plan, Collection<String> livingSourceIds) {
        if (plan == null || plan.jobs().isEmpty()) {
            return false;
        }
        if (livingSourceIds == null || livingSourceIds.isEmpty()) {
            return true;
        }
        java.util.Set<String> live = java.util.Set.copyOf(livingSourceIds);
        return plan.jobs().stream().anyMatch(job -> !live.contains(job.projectSourceId()));
    }

    /**
     * Builds a fresh immutable plan from current selection and switches to plan review.
     * Never mutates a previously reviewed plan instance.
     */
    public ProjectProcessingPlan reviewPlan(ProcessingPlanInputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        ProjectProcessingPlan plan = new ProcessingPlanBuilder().build(selection, inputs);
        reviewedPlan = plan;
        mode = ProcessingPageMode.PLAN_REVIEW;
        planRequiresReviewMessage = null;
        fireChanged();
        return plan;
    }

    /**
     * Rebuilds the immutable plan from current selection, then restores execution/result
     * rows from the session registry. Does not reparse files. No-op while executing.
     */
    public ProjectProcessingPlan hydrateFromRegistry(
            ProcessingPlanInputs inputs,
            ProcessingResultRegistry registry
    ) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(registry, "registry");
        if (mode == ProcessingPageMode.EXECUTING) {
            return reviewedPlan;
        }
        ProjectProcessingPlan plan = new ProcessingPlanBuilder().build(selection, inputs);
        if (plan.jobs().isEmpty()
                && executionPlanSnapshot != null
                && !executionPlanSnapshot.isEmpty()
                && executionPlanSnapshot.jobs().stream()
                .allMatch(job -> selection.contains(job.projectSourceId(), job.channelId()))) {
            plan = executionPlanSnapshot;
        }
        reviewedPlan = plan;
        planRequiresReviewMessage = null;
        if (plan.jobs().isEmpty()) {
            executionJobs = List.of();
            mode = ProcessingPageMode.PLAN_REVIEW;
            fireChanged();
            return plan;
        }
        List<ExecutionJobPresentation> rows = new ArrayList<>(plan.jobs().size());
        int settled = 0;
        for (ProcessingJob job : plan.jobs()) {
            IndependentJobResult result = registry.get(job.key())
                    .filter(found -> ProcessingPlanResultReconciler.sameProcessingConfiguration(job, found))
                    .filter(found -> found.successful() || found.status() == JobExecutionStatus.FAILED)
                    .orElse(null);
            if (result != null) {
                rows.add(ExecutionJobPresentation.queued(job).withResult(result));
                settled++;
            }
        }
        if (settled == 0) {
            executionJobs = List.of();
            mode = ProcessingPageMode.PLAN_REVIEW;
        } else if (settled == plan.jobs().size()) {
            executionJobs = List.copyOf(rows);
            executionPlanSnapshot = plan;
            latestBatch = registry.latestBatch().orElse(latestBatch);
            mode = ProcessingPageMode.EXECUTION_RESULT;
        } else {
            executionJobs = List.copyOf(rows);
            executionPlanSnapshot = plan;
            mode = ProcessingPageMode.PARTIAL;
        }
        fireChanged();
        return plan;
    }

    /**
     * Return to selection mode; selection is preserved.
     * No-op while {@link ProcessingPageMode#EXECUTING}.
     */
    public void backToSelection() {
        if (mode == ProcessingPageMode.SELECTION || mode == ProcessingPageMode.EXECUTING) {
            return;
        }
        mode = ProcessingPageMode.SELECTION;
        fireChanged();
    }

    /** Return to plan review from execution result; blocked while executing. */
    public void backToPlanReview() {
        if (mode == ProcessingPageMode.EXECUTING) {
            return;
        }
        if (mode == ProcessingPageMode.PLAN_REVIEW) {
            return;
        }
        mode = ProcessingPageMode.PLAN_REVIEW;
        fireChanged();
    }

    /**
     * Rebuilds the reviewed plan snapshot from current selection/inputs without leaving plan review.
     * Always allocates a new plan instance. No-op while executing.
     */
    public ProjectProcessingPlan rebuildReviewedPlan(ProcessingPlanInputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        if (mode == ProcessingPageMode.EXECUTING) {
            return reviewedPlan;
        }
        ProjectProcessingPlan plan = new ProcessingPlanBuilder().build(selection, inputs);
        reviewedPlan = plan;
        fireChanged();
        return plan;
    }

    /**
     * Drops channels whose sources are gone, then rebuilds the reviewed snapshot.
     * Removed-source configuration is not carried forward.
     */
    public ProjectProcessingPlan rebuildReviewedPlanForSources(
            ProcessingPlanInputs inputs,
            Collection<String> livingSourceIds
    ) {
        retainLivingSources(livingSourceIds);
        return rebuildReviewedPlan(inputs);
    }

    /**
     * Captures the exact plan to execute and switches to {@link ProcessingPageMode#EXECUTING}
     * with all jobs queued.
     */
    public void beginExecution(ProjectProcessingPlan plan) {
        beginExecution(plan, null, false);
    }

    /**
     * Starts normal incremental execution. Successful compatible registry rows are materialized as
     * already-completed before the background executor starts, so the UI never presents them as
     * queued/running again.
     */
    public void beginIncrementalExecution(ProjectProcessingPlan plan, ProcessingResultRegistry registry) {
        beginExecution(plan, Objects.requireNonNull(registry, "registry"), true);
    }

    private void beginExecution(
            ProjectProcessingPlan plan,
            ProcessingResultRegistry registry,
            boolean reuseCompleted
    ) {
        Objects.requireNonNull(plan, "plan");
        executionPlanSnapshot = plan;
        latestBatch = null;
        liveSourceFramesRead = 0L;
        executionStartedNanos = System.nanoTime();
        List<ExecutionJobPresentation> rows = new ArrayList<>(plan.jobs().size());
        for (ProcessingJob job : plan.jobs()) {
            IndependentJobResult reusable = reuseCompleted
                    ? ProcessingPlanResultReconciler.reusableResult(job, registry).orElse(null)
                    : null;
            rows.add(reusable == null
                    ? ExecutionJobPresentation.queued(job)
                    : ExecutionJobPresentation.queued(job).withResult(reusable));
        }
        executionJobs = List.copyOf(rows);
        planRequiresReviewMessage = null;
        mode = ProcessingPageMode.EXECUTING;
        fireChanged();
    }

    /**
     * Frames read so far by the source pass currently running. Deliberately does not notify
     * listeners: callers already schedule a coalesced UI refresh, so this cannot add repaints.
     */
    public void updateLiveSourceFramesRead(long framesRead) {
        liveSourceFramesRead = Math.max(0L, framesRead);
    }

    public long liveSourceFramesRead() {
        return liveSourceFramesRead;
    }

    /** Wall time since the current execution began; zero when nothing has been executed. */
    public java.time.Duration executionElapsed() {
        long started = executionStartedNanos;
        return started == 0L ? java.time.Duration.ZERO : java.time.Duration.ofNanos(System.nanoTime() - started);
    }

    public void updateJobStatus(ProcessingJobKey key, JobExecutionStatus status) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(status, "status");
        List<ExecutionJobPresentation> next = new ArrayList<>(executionJobs.size());
        boolean changed = false;
        for (ExecutionJobPresentation row : executionJobs) {
            if (row.jobKey().equals(key)) {
                next.add(row.withStatus(status));
                changed = true;
            } else {
                next.add(row);
            }
        }
        if (changed) {
            executionJobs = List.copyOf(next);
            fireChanged();
        }
    }

    public void updateJobStatus(ProcessingJobKey key, IndependentJobResult result) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(result, "result");
        List<ExecutionJobPresentation> next = new ArrayList<>(executionJobs.size());
        boolean changed = false;
        for (ExecutionJobPresentation row : executionJobs) {
            if (row.jobKey().equals(key)) {
                next.add(row.withResult(result));
                changed = true;
            } else {
                next.add(row);
            }
        }
        if (changed) {
            executionJobs = List.copyOf(next);
            fireChanged();
        }
    }

    /**
     * User cancel: every non-terminal row becomes {@link JobExecutionStatus#CANCELLED}.
     * Successful rows from a previous generation stay successful. Leaves EXECUTING.
     * Idempotent.
     */
    public void cancelActiveExecution() {
        List<ExecutionJobPresentation> next = new ArrayList<>(executionJobs.size());
        boolean changed = false;
        for (ExecutionJobPresentation row : executionJobs) {
            if (!row.status().isTerminal()) {
                next.add(row.withStatus(JobExecutionStatus.CANCELLED));
                changed = true;
            } else {
                next.add(row);
            }
        }
        if (changed) {
            executionJobs = List.copyOf(next);
        }
        if (mode == ProcessingPageMode.EXECUTING || changed) {
            mode = ProcessingPageMode.EXECUTION_RESULT;
            fireChanged();
        }
    }

    public int pendingJobCount() {
        int n = 0;
        for (ExecutionJobPresentation row : executionJobs) {
            JobExecutionStatus s = row.status();
            if (s == JobExecutionStatus.QUEUED || s == JobExecutionStatus.RETRY_WAIT) {
                n++;
            }
        }
        return n;
    }

    public int activeJobCount() {
        int n = 0;
        for (ExecutionJobPresentation row : executionJobs) {
            if (row.status() == JobExecutionStatus.RUNNING) {
                n++;
            }
        }
        return n;
    }

    public void completeExecution(MultiSourceProcessingResult batch) {
        Objects.requireNonNull(batch, "batch");
        latestBatch = batch;
        List<ExecutionJobPresentation> next = new ArrayList<>(executionJobs.size());
        for (ExecutionJobPresentation row : executionJobs) {
            IndependentJobResult result = batch.resultFor(row.jobKey()).orElse(null);
            if (result != null) {
                next.add(row.withResult(result));
            } else {
                next.add(row);
            }
        }
        executionJobs = List.copyOf(next);
        mode = ProcessingPageMode.EXECUTION_RESULT;
        fireChanged();
    }

    public void showExecutionResult() {
        if (mode == ProcessingPageMode.EXECUTING) {
            return;
        }
        mode = ProcessingPageMode.EXECUTION_RESULT;
        fireChanged();
    }

    /**
     * Stale-plan / mapping change path: return to plan review with an explicit message.
     * Does not clear selection.
     */
    public void markPlanRequiresReview(String message) {
        String msg = message == null || message.isBlank() ? "PLAN REQUIRES REVIEW" : message.trim();
        planRequiresReviewMessage = msg;
        mode = ProcessingPageMode.PLAN_REVIEW;
        fireChanged();
    }

    /**
     * Genuine execution rejection: keep retry-on-review, but every non-successful job
     * must leave Ready and carry a Failed result the review table can paint.
     */
    public void markExecutionFailed(String message, MultiSourceProcessingResult failedBatch) {
        if (failedBatch != null) {
            latestBatch = failedBatch;
            List<ExecutionJobPresentation> next = new ArrayList<>(Math.max(1, executionJobs.size()));
            if (executionJobs.isEmpty() && executionPlanSnapshot != null) {
                for (ProcessingJob job : executionPlanSnapshot.jobs()) {
                    IndependentJobResult result = failedBatch.resultFor(job.key()).orElse(null);
                    next.add(result == null
                            ? ExecutionJobPresentation.queued(job).withStatus(JobExecutionStatus.FAILED)
                            : ExecutionJobPresentation.queued(job).withResult(result));
                }
            } else {
                for (ExecutionJobPresentation row : executionJobs) {
                    if (row.status().isSuccessful()) {
                        next.add(row);
                        continue;
                    }
                    IndependentJobResult result = failedBatch.resultFor(row.jobKey()).orElse(null);
                    next.add(result == null
                            ? row.withStatus(JobExecutionStatus.FAILED)
                            : row.withResult(result));
                }
            }
            executionJobs = List.copyOf(next);
        }
        markPlanRequiresReview(message);
    }

    public boolean canNavigateAwayFromExecution() {
        return mode != ProcessingPageMode.EXECUTING;
    }

    public int completedJobCount() {
        int n = 0;
        for (ExecutionJobPresentation row : executionJobs) {
            if (row.terminal()) {
                n++;
            }
        }
        return n;
    }

    public int failedJobCount() {
        int n = 0;
        for (ExecutionJobPresentation row : executionJobs) {
            JobExecutionStatus s = row.status();
            if (s == JobExecutionStatus.FAILED
                    || s == JobExecutionStatus.CANCELLED
                    || s == JobExecutionStatus.BLOCKED) {
                n++;
            }
        }
        return n;
    }

    public int successfulJobCount() {
        int n = 0;
        for (ExecutionJobPresentation row : executionJobs) {
            JobExecutionStatus s = row.status();
            if (s == JobExecutionStatus.SUCCESS || s == JobExecutionStatus.SUCCESS_WITH_WARNINGS) {
                n++;
            }
        }
        return n;
    }

    public Snapshot snapshot() {
        return new Snapshot(
                mode,
                decisionOrigin,
                selection.selectedChannels(),
                reviewedPlan,
                executionPlanSnapshot,
                latestBatch
        );
    }

    private void fireChanged() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
