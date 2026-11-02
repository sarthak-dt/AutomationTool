package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Recording / inventory quality finding derived from real parser/inventory diagnostics.
 * Wording stays observational (gap / discontinuity) — never claims unproven dropped frames.
 */
public record DataQualityFindingMetadata(
        String findingId,
        String projectId,
        String sourceId,
        Integer channelId,
        String findingType,
        String severity,
        Long startTimestampNanos,
        Long endTimestampNanos,
        String summary,
        String technicalDetail,
        String processingRunId,
        Instant createdAt
) {
    public DataQualityFindingMetadata {
        findingId = findingId == null || findingId.isBlank() ? UUID.randomUUID().toString() : findingId;
        projectId = require(projectId, "projectId");
        sourceId = require(sourceId, "sourceId");
        findingType = require(findingType, "findingType");
        severity = severity == null || severity.isBlank() ? "INFO" : severity;
        summary = summary == null ? "" : summary;
        technicalDetail = technicalDetail == null ? "" : technicalDetail;
        processingRunId = processingRunId == null ? "" : processingRunId;
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
