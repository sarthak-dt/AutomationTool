package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Live presentation row for one processing job during / after plan execution.
 */
public final class ExecutionJobPresentation {

    private final ProcessingJobKey jobKey;
    private final ProcessingJob planJob;
    private final JobExecutionStatus status;
    private final Long selectedChannelFrames;
    private final Long dbcMatchedFrames;
    private final Long signalsProduced;
    private final Duration elapsed;
    private final IndependentJobResult result;

    private ExecutionJobPresentation(
            ProcessingJobKey jobKey,
            ProcessingJob planJob,
            JobExecutionStatus status,
            Long selectedChannelFrames,
            Long dbcMatchedFrames,
            Long signalsProduced,
            Duration elapsed,
            IndependentJobResult result
    ) {
        this.jobKey = Objects.requireNonNull(jobKey, "jobKey");
        this.planJob = Objects.requireNonNull(planJob, "planJob");
        this.status = Objects.requireNonNull(status, "status");
        this.selectedChannelFrames = selectedChannelFrames;
        this.dbcMatchedFrames = dbcMatchedFrames;
        this.signalsProduced = signalsProduced;
        this.elapsed = elapsed;
        this.result = result;
    }

    public static ExecutionJobPresentation queued(ProcessingJob job) {
        Objects.requireNonNull(job, "job");
        return new ExecutionJobPresentation(
                job.key(),
                job,
                JobExecutionStatus.QUEUED,
                null,
                null,
                null,
                null,
                null
        );
    }

    public ExecutionJobPresentation withStatus(JobExecutionStatus newStatus) {
        Objects.requireNonNull(newStatus, "newStatus");
        return new ExecutionJobPresentation(
                jobKey, planJob, newStatus,
                selectedChannelFrames, dbcMatchedFrames, signalsProduced, elapsed, result);
    }

    public ExecutionJobPresentation withResult(IndependentJobResult jobResult) {
        Objects.requireNonNull(jobResult, "jobResult");
        return new ExecutionJobPresentation(
                jobKey,
                planJob,
                jobResult.status(),
                jobResult.selectedChannelFrames(),
                jobResult.dbcMatchedFrames(),
                jobResult.signalsProduced(),
                jobResult.elapsed(),
                jobResult
        );
    }

    public ProcessingJobKey jobKey() {
        return jobKey;
    }

    public ProcessingJob planJob() {
        return planJob;
    }

    public JobExecutionStatus status() {
        return status;
    }

    public Optional<Long> selectedChannelFrames() {
        return Optional.ofNullable(selectedChannelFrames);
    }

    public Optional<Long> dbcMatchedFrames() {
        return Optional.ofNullable(dbcMatchedFrames);
    }

    public Optional<Long> signalsProduced() {
        return Optional.ofNullable(signalsProduced);
    }

    public Optional<Duration> elapsed() {
        return Optional.ofNullable(elapsed);
    }

    public Optional<IndependentJobResult> result() {
        return Optional.ofNullable(result);
    }

    /** Expected frames from plan while queued/running; actual when finished. */
    public long displayFrameCount() {
        if (selectedChannelFrames != null) {
            return selectedChannelFrames;
        }
        return planJob.frameCount();
    }

    public boolean terminal() {
        return status != JobExecutionStatus.QUEUED && status != JobExecutionStatus.RUNNING;
    }
}
