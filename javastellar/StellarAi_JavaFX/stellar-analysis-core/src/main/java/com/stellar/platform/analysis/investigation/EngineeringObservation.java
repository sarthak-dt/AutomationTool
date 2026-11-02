package com.stellar.platform.analysis.investigation;

import java.time.Instant;
import java.util.UUID;

/** User-authored note. Never mixed with deterministic diagnostic findings. */
public record EngineeringObservation(
        String observationId,
        String investigationId,
        Long startNanos,
        Long endNanos,
        String title,
        String note,
        Instant createdAt,
        Instant updatedAt
) {
    public EngineeringObservation {
        observationId = observationId == null || observationId.isBlank()
                ? UUID.randomUUID().toString() : observationId;
        investigationId = investigationId == null ? "" : investigationId;
        title = title == null ? "" : title;
        note = note == null ? "" : note;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }
}
