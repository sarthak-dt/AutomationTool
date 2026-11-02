package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.UUID;

public record InvestigationObservationRow(
        String observationId,
        String investigationId,
        Long startNanos,
        Long endNanos,
        String title,
        String note,
        Instant createdAt,
        Instant updatedAt
) {
    public InvestigationObservationRow {
        observationId = observationId == null || observationId.isBlank()
                ? UUID.randomUUID().toString() : observationId;
        investigationId = investigationId == null ? "" : investigationId;
        title = title == null ? "" : title;
        note = note == null ? "" : note;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }
}
