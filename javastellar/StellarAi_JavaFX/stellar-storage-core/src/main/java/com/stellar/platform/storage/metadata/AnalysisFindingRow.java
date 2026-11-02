package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AnalysisFindingRow(
        String findingId,
        String analysisId,
        String projectId,
        String findingType,
        String severity,
        long startTimestampNanos,
        Long endTimestampNanos,
        String summary,
        String details,
        String metricsBlob,
        Instant createdAt
) {
    public AnalysisFindingRow {
        findingId = findingId == null || findingId.isBlank() ? UUID.randomUUID().toString() : findingId;
        analysisId = Objects.requireNonNull(analysisId, "analysisId");
        projectId = Objects.requireNonNull(projectId, "projectId");
        findingType = findingType == null ? "" : findingType;
        severity = severity == null ? "OBSERVATION" : severity;
        summary = summary == null ? "" : summary;
        details = details == null ? "" : details;
        metricsBlob = metricsBlob == null ? "" : metricsBlob;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
