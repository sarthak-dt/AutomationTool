package com.stellar.platform.storage.metadata;

import java.util.Locale;

/** Kind of user annotation placed against the signal timeline. */
public enum GraphAnnotationType {
    /** Single instant. {@code endTimestampNanos} is unused. */
    POINT_MARKER,
    /** Span between two instants. Requires {@code endTimestampNanos}. */
    TIME_RANGE,
    /** Free-text observation anchored at an instant. */
    TEXT_NOTE;

    public static GraphAnnotationType parse(String value, GraphAnnotationType fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unrecognised) {
            return fallback;
        }
    }
}
