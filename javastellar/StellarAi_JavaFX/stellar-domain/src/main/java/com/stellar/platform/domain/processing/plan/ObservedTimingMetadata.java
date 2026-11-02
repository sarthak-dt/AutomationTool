package com.stellar.platform.domain.processing.plan;

import java.util.Objects;
import java.util.Optional;

/**
 * Optional original/native timing metadata discovered for a source.
 * Plan creation copies these values only — never shifts, rebases, or normalizes them.
 */
public final class ObservedTimingMetadata {

    private final Long recordingStartNanos;
    private final Long recordingEndNanos;
    private final String timeBaseLabel;
    private final String absoluteTimeMetadata;

    public ObservedTimingMetadata(
            Long recordingStartNanos,
            Long recordingEndNanos,
            String timeBaseLabel,
            String absoluteTimeMetadata
    ) {
        this.recordingStartNanos = recordingStartNanos;
        this.recordingEndNanos = recordingEndNanos;
        this.timeBaseLabel = timeBaseLabel == null ? "" : timeBaseLabel;
        this.absoluteTimeMetadata = absoluteTimeMetadata == null ? "" : absoluteTimeMetadata;
    }

    public Optional<Long> recordingStartNanos() {
        return Optional.ofNullable(recordingStartNanos);
    }

    public Optional<Long> recordingEndNanos() {
        return Optional.ofNullable(recordingEndNanos);
    }

    public String timeBaseLabel() {
        return timeBaseLabel;
    }

    public String absoluteTimeMetadata() {
        return absoluteTimeMetadata;
    }

    /** Defensive copy for immutability across plan snapshots. */
    public ObservedTimingMetadata copy() {
        return new ObservedTimingMetadata(
                recordingStartNanos, recordingEndNanos, timeBaseLabel, absoluteTimeMetadata);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ObservedTimingMetadata that
                && Objects.equals(recordingStartNanos, that.recordingStartNanos)
                && Objects.equals(recordingEndNanos, that.recordingEndNanos)
                && timeBaseLabel.equals(that.timeBaseLabel)
                && absoluteTimeMetadata.equals(that.absoluteTimeMetadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recordingStartNanos, recordingEndNanos, timeBaseLabel, absoluteTimeMetadata);
    }
}
