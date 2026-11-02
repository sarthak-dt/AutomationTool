package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;

public record DiagnosticFindingReviewRow(
        String findingId,
        String projectId,
        String reviewState,
        String engineerNote,
        Instant updatedAt
) {
    public DiagnosticFindingReviewRow {
        findingId = Objects.requireNonNull(findingId, "findingId");
        projectId = projectId == null ? "" : projectId;
        reviewState = reviewState == null || reviewState.isBlank() ? "NEW" : reviewState;
        engineerNote = engineerNote == null ? "" : engineerNote;
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
    }
}
