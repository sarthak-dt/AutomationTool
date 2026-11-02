package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One Export Center job and its outcome.
 *
 * <p>{@code exportType} and {@code scope} are free-form so new export kinds do not require a
 * migration. {@code destination} is the written file or directory, which may be inside Analysis
 * Storage or a location the user chose.</p>
 */
public record ExportJobMetadata(
        String exportId,
        String projectId,
        String exportType,
        String scope,
        ExportJobStatus status,
        String destination,
        long bytesWritten,
        String message,
        Instant createdAt,
        Instant finishedAt
) {
    public ExportJobMetadata {
        exportId = exportId == null || exportId.isBlank() ? UUID.randomUUID().toString() : exportId;
        projectId = require(projectId, "projectId");
        exportType = require(exportType, "exportType");
        scope = scope == null ? "" : scope;
        status = Objects.requireNonNullElse(status, ExportJobStatus.QUEUED);
        destination = destination == null ? "" : destination;
        bytesWritten = Math.max(0L, bytesWritten);
        message = message == null ? "" : message;
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
    }

    /** Newly queued export; the runner advances it through the remaining states. */
    public static ExportJobMetadata queued(
            String projectId,
            String exportType,
            String scope,
            String destination
    ) {
        return new ExportJobMetadata(
                null, projectId, exportType, scope, ExportJobStatus.QUEUED, destination, 0L, "", null, null);
    }

    public ExportJobMetadata withStatus(ExportJobStatus newStatus, String newMessage) {
        Instant finished = newStatus != null && newStatus.isTerminal() ? Instant.now() : finishedAt;
        return new ExportJobMetadata(
                exportId, projectId, exportType, scope, newStatus, destination,
                bytesWritten, newMessage, createdAt, finished);
    }

    public ExportJobMetadata withProgress(long newBytesWritten) {
        return new ExportJobMetadata(
                exportId, projectId, exportType, scope, status, destination,
                newBytesWritten, message, createdAt, finishedAt);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
