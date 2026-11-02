package com.stellar.platform.storage.metadata;

import com.stellar.platform.domain.processing.ProcessingFailureClass;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One durable unit of work in the processing ledger.
 *
 * <p>Survives termination of the worker and of the application, so restart recovery can find jobs
 * that were {@link JobExecutionStatus#RUNNING} in a previous session. {@code processing_runs} keeps
 * the run-level summary; this record is the per-job lifecycle.</p>
 */
public record ProcessingJobMetadata(
        String jobId,
        String projectId,
        String runId,
        String sourceId,
        int channelId,
        String definitionId,
        JobExecutionStatus status,
        int attempt,
        int maxAttempts,
        double progress,
        String stage,
        String workerId,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        ProcessingFailureClass failureClass,
        String message
) {
    public ProcessingJobMetadata {
        jobId = jobId == null || jobId.isBlank() ? UUID.randomUUID().toString() : jobId;
        projectId = require(projectId, "projectId");
        runId = runId == null ? "" : runId;
        sourceId = require(sourceId, "sourceId");
        definitionId = definitionId == null ? "" : definitionId;
        status = Objects.requireNonNullElse(status, JobExecutionStatus.QUEUED);
        attempt = Math.max(0, attempt);
        maxAttempts = Math.max(1, maxAttempts);
        progress = clampProgress(progress);
        stage = stage == null ? "" : stage;
        workerId = workerId == null ? "" : workerId;
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
        message = message == null ? "" : message;
    }

    /** True when another attempt is still permitted and the failure class allows retrying. */
    public boolean canRetry() {
        return failureClass != null && failureClass.isRetryable() && attempt < maxAttempts;
    }

    private static double clampProgress(double value) {
        if (Double.isNaN(value) || value < 0d) {
            return 0d;
        }
        return Math.min(1d, value);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
