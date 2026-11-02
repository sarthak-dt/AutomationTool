package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DiagnosticRunMetadata(
        String runId,
        String projectId,
        String packId,
        String packVersion,
        String status,
        String rangeSource,
        Long startTimestampNanos,
        Long endTimestampNanos,
        int rulesRequested,
        int rulesExecuted,
        int rulesSkipped,
        String invalidationKey,
        long artifactGeneration,
        String engineVersion,
        String message,
        Instant createdAt,
        Instant completedAt
) {
    public DiagnosticRunMetadata {
        runId = runId == null || runId.isBlank() ? UUID.randomUUID().toString() : runId;
        projectId = Objects.requireNonNull(projectId, "projectId");
        packId = packId == null ? "" : packId;
        packVersion = packVersion == null ? "" : packVersion;
        status = status == null ? "" : status;
        rangeSource = rangeSource == null ? "FULL" : rangeSource;
        invalidationKey = invalidationKey == null ? "" : invalidationKey;
        engineVersion = engineVersion == null ? "1" : engineVersion;
        message = message == null ? "" : message;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
