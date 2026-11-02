package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;

public record AnalysisWorkspaceMetadata(
        String projectId,
        String lastAnalysisType,
        String lastAnalysisId,
        boolean resultVisible,
        String selectedFindingId,
        Instant updatedAt
) {
    public AnalysisWorkspaceMetadata {
        projectId = Objects.requireNonNull(projectId, "projectId");
        lastAnalysisType = lastAnalysisType == null ? "" : lastAnalysisType;
        lastAnalysisId = lastAnalysisId == null ? "" : lastAnalysisId;
        selectedFindingId = selectedFindingId == null ? "" : selectedFindingId;
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }
}
