package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;

public record InvestigationWorkspaceMetadata(
        String projectId,
        String lastInvestigationId,
        Instant updatedAt
) {
    public InvestigationWorkspaceMetadata {
        projectId = Objects.requireNonNull(projectId, "projectId");
        lastInvestigationId = lastInvestigationId == null ? "" : lastInvestigationId;
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }
}
