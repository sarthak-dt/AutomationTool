package com.stellar.platform.processing.graph.timeline;

import com.stellar.platform.processing.map.time.RecordingClock;

import java.util.Objects;

/**
 * One recording as the timeline planner sees it. Stored timestamps and the clock are copied
 * through unchanged. The source file is provenance, not a sort key and not a clock.
 */
public record RecordingDescriptor(
        String measurementId,
        String sourceFile,
        RecordingClock clock,
        long storedStartNanos,
        long storedEndNanos
) {
    public RecordingDescriptor {
        if (measurementId == null || measurementId.isBlank()) {
            throw new IllegalArgumentException("measurementId");
        }
        measurementId = measurementId.trim();
        sourceFile = sourceFile == null ? "" : sourceFile;
        Objects.requireNonNull(clock, "clock");
        if (storedEndNanos < storedStartNanos) {
            throw new IllegalArgumentException("stored end before start");
        }
    }

    public boolean verifiedUtc() {
        return clock.comparable();
    }
}
