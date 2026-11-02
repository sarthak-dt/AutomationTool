package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;

public record DiagnosticWorkspaceMetadata(
        String projectId,
        String lastPackId,
        String lastRunId,
        String enabledRulesBlob,
        boolean findingsVisible,
        String selectedFindingId,
        Instant updatedAt
) {
    public DiagnosticWorkspaceMetadata {
        projectId = Objects.requireNonNull(projectId, "projectId");
        lastPackId = lastPackId == null ? "" : lastPackId;
        lastRunId = lastRunId == null ? "" : lastRunId;
        enabledRulesBlob = enabledRulesBlob == null ? "" : enabledRulesBlob;
        selectedFindingId = selectedFindingId == null ? "" : selectedFindingId;
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }
}
