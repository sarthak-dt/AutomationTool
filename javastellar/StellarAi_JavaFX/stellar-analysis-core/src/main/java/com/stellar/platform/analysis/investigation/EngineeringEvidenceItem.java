package com.stellar.platform.analysis.investigation;

import java.time.Instant;
import java.util.UUID;

/**
 * Pointer to an existing stable entity. Does not copy waveform samples or result payloads.
 */
public record EngineeringEvidenceItem(
        String evidenceId,
        String investigationId,
        EvidenceKind kind,
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
    public EngineeringEvidenceItem {
        evidenceId = evidenceId == null || evidenceId.isBlank() ? UUID.randomUUID().toString() : evidenceId;
        investigationId = investigationId == null ? "" : investigationId;
        kind = kind == null ? EvidenceKind.ENGINEERING_NOTE : kind;
        referenceId = referenceId == null ? "" : referenceId;
        staleReason = staleReason == null ? "" : staleReason;
        sourceFingerprint = sourceFingerprint == null ? "" : sourceFingerprint;
        caption = caption == null ? "" : caption;
        summary = summary == null ? "" : summary;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public EngineeringEvidenceItem withOrder(int order) {
        return new EngineeringEvidenceItem(evidenceId, investigationId, kind, referenceId, order,
                stale, staleReason, sourceFingerprint, artifactGeneration, caption, summary, createdAt);
    }

    public EngineeringEvidenceItem withStale(boolean staleFlag, String reason) {
        return new EngineeringEvidenceItem(evidenceId, investigationId, kind, referenceId, sortOrder,
                staleFlag, reason == null ? "" : reason, sourceFingerprint, artifactGeneration,
                caption, summary, createdAt);
    }
}
