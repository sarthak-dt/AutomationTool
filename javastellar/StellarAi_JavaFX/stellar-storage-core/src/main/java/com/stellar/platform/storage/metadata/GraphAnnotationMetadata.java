package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A user annotation on the signal timeline.
 *
 * <p>Anchored to timestamps and a stable signal key, never to Canvas geometry or pixel positions,
 * so it survives a zoom change, a window resize or a re-render. An empty {@code signalKey} means
 * the annotation applies to the whole graph rather than one trace.</p>
 */
public record GraphAnnotationMetadata(
        String annotationId,
        String projectId,
        String signalKey,
        GraphAnnotationType annotationType,
        long startTimestampNanos,
        Long endTimestampNanos,
        Double value,
        String label,
        String note,
        Instant createdAt,
        Instant updatedAt
) {
    public GraphAnnotationMetadata {
        annotationId = annotationId == null || annotationId.isBlank()
                ? UUID.randomUUID().toString() : annotationId;
        projectId = require(projectId, "projectId");
        signalKey = signalKey == null ? "" : signalKey;
        annotationType = Objects.requireNonNullElse(annotationType, GraphAnnotationType.POINT_MARKER);
        label = label == null ? "" : label;
        note = note == null ? "" : note;
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
        updatedAt = Objects.requireNonNullElse(updatedAt, createdAt);
        if (annotationType == GraphAnnotationType.TIME_RANGE) {
            if (endTimestampNanos == null) {
                throw new IllegalArgumentException("TIME_RANGE annotation requires endTimestampNanos");
            }
            if (endTimestampNanos < startTimestampNanos) {
                throw new IllegalArgumentException("endTimestampNanos must not precede startTimestampNanos");
            }
        }
    }

    /** Inclusive end for a range, or the anchor instant for point and note annotations. */
    public long effectiveEndTimestampNanos() {
        return endTimestampNanos == null ? startTimestampNanos : endTimestampNanos;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
