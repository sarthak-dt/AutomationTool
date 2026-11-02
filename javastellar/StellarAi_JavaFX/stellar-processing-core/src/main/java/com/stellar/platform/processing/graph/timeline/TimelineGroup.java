package com.stellar.platform.processing.graph.timeline;

/**
 * One navigable timeline. A project may hold both groups. They are never joined into one clock.
 */
public enum TimelineGroup {
    /** Recordings whose {@link com.stellar.platform.processing.map.time.RecordingClock} is comparable. */
    VERIFIED_UTC,
    /**
     * Recordings without a comparable clock. Slot order is presentation only.
     * Explicit Recording Sequence is not this group and is not implemented.
     */
    UNKNOWN_ORDER
}
