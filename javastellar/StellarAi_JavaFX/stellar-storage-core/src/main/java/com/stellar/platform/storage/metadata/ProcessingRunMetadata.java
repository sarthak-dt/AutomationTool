package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;

/** Processing execution audit metadata. No signal payloads are persisted. */
public record ProcessingRunMetadata(
        String runId,
        String projectId,
        String status,
        boolean forceReprocess,
        int jobCount,
        Instant startedAt,
        Instant finishedAt,
        String message
) {
    public ProcessingRunMetadata {
        runId = requireText(runId, "runId");
        projectId = requireText(projectId, "projectId");
        status = status == null ? "" : status;
        jobCount = Math.max(0, jobCount);
        startedAt = Objects.requireNonNullElseGet(startedAt, Instant::now);
        message = message == null ? "" : message;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
