package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One committed processing unit within a job.
 *
 * <p>{@code inputStart}/{@code inputEnd} are parser-defined positions (byte offset, record index or
 * block index). Storage keeps them opaque so the format stays generic across MF4/ASC/BLF.</p>
 */
public record ProcessingBatchMetadata(
        String batchId,
        String projectId,
        String jobId,
        long sequence,
        long inputStart,
        long inputEnd,
        long recordsProcessed,
        long samplesProduced,
        ProcessingBatchStatus status,
        String artifactRef,
        Instant startedAt,
        Instant committedAt
) {
    public ProcessingBatchMetadata {
        batchId = batchId == null || batchId.isBlank() ? UUID.randomUUID().toString() : batchId;
        projectId = require(projectId, "projectId");
        jobId = require(jobId, "jobId");
        sequence = Math.max(0L, sequence);
        recordsProcessed = Math.max(0L, recordsProcessed);
        samplesProduced = Math.max(0L, samplesProduced);
        status = Objects.requireNonNullElse(status, ProcessingBatchStatus.OPEN);
        artifactRef = artifactRef == null ? "" : artifactRef;
        startedAt = Objects.requireNonNullElseGet(startedAt, Instant::now);
    }

    public boolean isCommitted() {
        return status == ProcessingBatchStatus.COMMITTED;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
