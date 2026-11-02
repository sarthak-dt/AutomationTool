package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;

/**
 * Persisted Map Route view state for one measurement.
 *
 * <p>Timestamps are the durable identity of a map selection: coordinates are re-resolved against
 * the rebuilt route on reload, so the map never stores GPS arrays in SQLite.
 *
 * @param pointTimestampNanos single-point selection, or {@code null}
 * @param rangeStartNanos     inclusive A→B range start, or {@code null}
 * @param rangeEndNanos       inclusive A→B range end, or {@code null}
 */
public record MapRouteStateMetadata(
        String projectId,
        String sourceId,
        String mode,
        Long pointTimestampNanos,
        Long rangeStartNanos,
        Long rangeEndNanos,
        boolean autoSync,
        Instant updatedAt
) {
    public MapRouteStateMetadata {
        projectId = Objects.requireNonNull(projectId, "projectId");
        sourceId = sourceId == null ? "" : sourceId;
        mode = mode == null || mode.isBlank() ? "ALL_ROUTE" : mode;
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }
}
