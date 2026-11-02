package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;

/**
 * Persisted Graphs analysis scope. {@code ALL} or {@code SINGLE} plus a physical source id.
 */
public record AnalysisScopeRecord(
        String projectId,
        String scopeKind,
        String sourceId,
        String fileName,
        Instant updatedAt
) {
    public AnalysisScopeRecord {
        projectId = Objects.requireNonNull(projectId, "projectId");
        scopeKind = scopeKind == null || scopeKind.isBlank() ? "SINGLE" : scopeKind.trim().toUpperCase();
        sourceId = sourceId == null ? "" : sourceId.trim();
        fileName = fileName == null ? "" : fileName.trim();
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public boolean allMeasurements() {
        return "ALL".equals(scopeKind);
    }
}
