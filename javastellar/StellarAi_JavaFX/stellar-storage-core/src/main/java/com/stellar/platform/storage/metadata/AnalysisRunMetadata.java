package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AnalysisRunMetadata(
        String analysisId,
        String projectId,
        String analysisType,
        String status,
        String alignmentPolicy,
        String analysisVersion,
        String rangeSource,
        Long startTimestampNanos,
        Long endTimestampNanos,
        String signalKeysBlob,
        String parametersBlob,
        String invalidationKey,
        String artifactRef,
        String message,
        Instant createdAt,
        Instant completedAt
) {
    public AnalysisRunMetadata {
        analysisId = analysisId == null || analysisId.isBlank() ? UUID.randomUUID().toString() : analysisId;
        projectId = Objects.requireNonNull(projectId, "projectId");
        analysisType = analysisType == null ? "" : analysisType;
        status = status == null ? "" : status;
        alignmentPolicy = alignmentPolicy == null ? "EXACT_TIMESTAMP" : alignmentPolicy;
        analysisVersion = analysisVersion == null ? "1" : analysisVersion;
        rangeSource = rangeSource == null ? "FULL" : rangeSource;
        signalKeysBlob = signalKeysBlob == null ? "" : signalKeysBlob;
        parametersBlob = parametersBlob == null ? "" : parametersBlob;
        invalidationKey = invalidationKey == null ? "" : invalidationKey;
        artifactRef = artifactRef == null ? "" : artifactRef;
        message = message == null ? "" : message;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
