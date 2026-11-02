package com.stellar.platform.storage.api;

import com.stellar.platform.storage.metadata.AuditEventMetadata;

import java.util.List;

/** Append-only project activity trail. Entries are never updated or deleted individually. */
public interface AuditRepository {

    /** @return the assigned audit id, which defines durable ordering. */
    default long appendAuditEvent(AuditEventMetadata event) {
        return 0L;
    }

    /** Most recent events first, bounded by {@code limit}. */
    default List<AuditEventMetadata> listRecentAuditEvents(String projectId, int limit) {
        return List.of();
    }
}
