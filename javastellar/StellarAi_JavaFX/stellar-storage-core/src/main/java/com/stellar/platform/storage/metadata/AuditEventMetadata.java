package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;

/**
 * One append-only audit entry.
 *
 * <p>{@code auditId} is assigned by the database and gives a durable total ordering independent of
 * timestamp resolution — two events in the same millisecond still read back in write order. It is
 * {@code 0} on records that have not been persisted yet.</p>
 *
 * <p>Holds lightweight metadata only. Never sample data.</p>
 */
public record AuditEventMetadata(
        long auditId,
        String projectId,
        AuditEventType eventType,
        String entityId,
        String summary,
        String detail,
        Instant createdAt
) {
    public AuditEventMetadata {
        projectId = require(projectId, "projectId");
        eventType = Objects.requireNonNullElse(eventType, AuditEventType.UNKNOWN);
        entityId = entityId == null ? "" : entityId;
        summary = summary == null ? "" : summary;
        detail = detail == null ? "" : detail;
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
    }

    /** New unsaved event; the store assigns {@link #auditId()} on write. */
    public static AuditEventMetadata of(
            String projectId,
            AuditEventType eventType,
            String entityId,
            String summary
    ) {
        return new AuditEventMetadata(0L, projectId, eventType, entityId, summary, "", null);
    }

    public static AuditEventMetadata of(
            String projectId,
            AuditEventType eventType,
            String entityId,
            String summary,
            String detail
    ) {
        return new AuditEventMetadata(0L, projectId, eventType, entityId, summary, detail, null);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
