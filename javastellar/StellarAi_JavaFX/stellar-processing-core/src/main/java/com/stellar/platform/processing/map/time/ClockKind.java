package com.stellar.platform.processing.map.time;

/**
 * Recording-time class. Relative sample time is never stored as UTC.
 * A future explicit user offset may promote {@link #LOCAL_UNKNOWN_ZONE}; nothing else may.
 */
public enum ClockKind {
    UTC,
    LOCAL_WITH_OFFSET,
    LOCAL_UNKNOWN_ZONE,
    RELATIVE_ONLY,
    ABSOLUTE_SAMPLE,
    MISSING
}
