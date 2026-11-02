package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.UUID;

public record InvestigationEvidenceRow(
        String evidenceId,
        String investigationId,
        String kind,
        String referenceId,
        int sortOrder,
        boolean stale,
        String staleReason,
        String sourceFingerprint,
        long artifactGeneration,
        String caption,
        String summary,
        Instant createdAt
) {
    public InvestigationEvidenceRow {
        evidenceId = evidenceId == null || evidenceId.isBlank() ? UUID.randomUUID().toString() : evidenceId;
        investigationId = investigationId == null ? "" : investigationId;
        kind = kind == null ? "ENGINEERING_NOTE" : kind;
        referenceId = referenceId == null ? "" : referenceId;
        staleReason = staleReason == null ? "" : staleReason;
        sourceFingerprint = sourceFingerprint == null ? "" : sourceFingerprint;
        caption = caption == null ? "" : caption;
        summary = summary == null ? "" : summary;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
