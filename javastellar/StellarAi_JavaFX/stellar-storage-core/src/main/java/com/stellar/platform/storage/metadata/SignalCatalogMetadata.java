package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Metadata-only signal catalog row. Never stores sample arrays.
 * Samples remain in the project-scoped lossless artifact referenced by {@link #artifactId()}.
 */
public record SignalCatalogMetadata(
        String signalCatalogId,
        String projectId,
        String sourceId,
        int channelId,
        String definitionId,
        String artifactId,
        String signalKey,
        String signalName,
        String messageName,
        long canId,
        int signalChannel,
        String sourceType,
        String unit,
        long sampleCount,
        Long startTimestampNanos,
        Long endTimestampNanos,
        Double minValue,
        Double maxValue,
        Instant createdAt,
        Instant updatedAt
) {
    public SignalCatalogMetadata {
        signalCatalogId = blankToUuid(signalCatalogId);
        projectId = require(projectId, "projectId");
        sourceId = require(sourceId, "sourceId");
        if (channelId < 0) throw new IllegalArgumentException("channelId must be >= 0");
        definitionId = definitionId == null ? "" : definitionId;
        artifactId = artifactId == null ? "" : artifactId;
        signalKey = require(signalKey, "signalKey");
        signalName = require(signalName, "signalName");
        messageName = messageName == null ? "" : messageName;
        if (canId < 0) throw new IllegalArgumentException("canId must be >= 0");
        if (signalChannel < 0) throw new IllegalArgumentException("signalChannel must be >= 0");
        sourceType = sourceType == null ? "" : sourceType;
        unit = unit == null ? "" : unit;
        sampleCount = Math.max(0L, sampleCount);
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
        Instant created = createdAt;
        updatedAt = Objects.requireNonNullElseGet(updatedAt, () -> created);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    private static String blankToUuid(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }
}
