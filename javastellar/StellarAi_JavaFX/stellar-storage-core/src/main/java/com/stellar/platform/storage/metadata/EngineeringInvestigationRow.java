package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EngineeringInvestigationRow(
        String investigationId,
        String projectId,
        String name,
        String description,
        String status,
        Long rangeStartNanos,
        Long rangeEndNanos,
        String graphTracesBlob,
        String layoutMode,
        Long viewportStartNanos,
        Long viewportEndNanos,
        Long cursorANanos,
        Long cursorBNanos,
        String focusKeyBlob,
        String conclusion,
        String reportConfig,
        Instant createdAt,
        Instant updatedAt
) {
    public EngineeringInvestigationRow {
        investigationId = investigationId == null || investigationId.isBlank()
                ? UUID.randomUUID().toString() : investigationId;
        projectId = Objects.requireNonNull(projectId, "projectId");
        name = name == null || name.isBlank() ? "Untitled investigation" : name;
        description = description == null ? "" : description;
        status = status == null || status.isBlank() ? "DRAFT" : status;
        graphTracesBlob = graphTracesBlob == null ? "" : graphTracesBlob;
        layoutMode = layoutMode == null || layoutMode.isBlank() ? "STACKED" : layoutMode;
        focusKeyBlob = focusKeyBlob == null ? "" : focusKeyBlob;
        conclusion = conclusion == null ? "" : conclusion;
        reportConfig = reportConfig == null ? "" : reportConfig;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }
}
