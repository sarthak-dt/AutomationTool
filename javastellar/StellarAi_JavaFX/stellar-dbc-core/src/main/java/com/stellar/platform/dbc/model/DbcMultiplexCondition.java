package com.stellar.platform.dbc.model;

import java.util.List;
import java.util.Objects;

/**
 * Extended multiplex gate: a leaf (or intermediate) signal is active when the named
 * multiplexor signal's raw value lies in any of the inclusive ranges.
 * <p>
 * Sourced from {@code SG_MUL_VAL_ MessageId SignalName MultiplexorName Ranges;}.
 */
public record DbcMultiplexCondition(
        String multiplexorSignalName,
        List<DbcMultiplexRange> ranges
) {

    public DbcMultiplexCondition {
        if (multiplexorSignalName == null || multiplexorSignalName.isBlank()) {
            throw new IllegalArgumentException("multiplexorSignalName must not be blank");
        }
        ranges = List.copyOf(Objects.requireNonNull(ranges, "ranges"));
        if (ranges.isEmpty()) {
            throw new IllegalArgumentException("multiplex condition requires at least one range");
        }
    }

    public boolean matches(long multiplexorRawValue) {
        for (DbcMultiplexRange range : ranges) {
            if (range.contains(multiplexorRawValue)) {
                return true;
            }
        }
        return false;
    }

    public DbcMultiplexCondition withAdditionalRanges(List<DbcMultiplexRange> more) {
        Objects.requireNonNull(more, "more");
        if (more.isEmpty()) {
            return this;
        }
        java.util.ArrayList<DbcMultiplexRange> merged = new java.util.ArrayList<>(ranges);
        merged.addAll(more);
        return new DbcMultiplexCondition(multiplexorSignalName, merged);
    }
}
