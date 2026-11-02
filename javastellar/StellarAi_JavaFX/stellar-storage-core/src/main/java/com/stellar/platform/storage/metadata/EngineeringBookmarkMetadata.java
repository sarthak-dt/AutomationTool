package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persisted Engineering Analysis bookmark (existing BookmarkRow workflow). */
public record EngineeringBookmarkMetadata(
        String bookmarkId,
        String projectId,
        String sourceId,
        String artifactId,
        String signalName,
        String signalKey,
        long timestampNanos,
        String eventType,
        String observation,
        Instant createdAt,
        Instant updatedAt
) {
    public EngineeringBookmarkMetadata {
        bookmarkId = bookmarkId == null || bookmarkId.isBlank() ? UUID.randomUUID().toString() : bookmarkId;
        projectId = require(projectId, "projectId");
        sourceId = sourceId == null ? "" : sourceId;
        artifactId = artifactId == null ? "" : artifactId;
        signalName = signalName == null ? "" : signalName;
        signalKey = signalKey == null ? "" : signalKey;
        eventType = eventType == null ? "" : eventType;
        observation = observation == null ? "" : observation;
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
        Instant created = createdAt;
        updatedAt = Objects.requireNonNullElseGet(updatedAt, () -> created);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
