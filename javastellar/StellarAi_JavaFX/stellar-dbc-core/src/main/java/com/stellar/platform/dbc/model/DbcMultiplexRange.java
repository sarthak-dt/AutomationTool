package com.stellar.platform.dbc.model;

/**
 * Inclusive multiplex value range from {@code SG_MUL_VAL_} (e.g. {@code 1-10} or {@code 65-65}).
 */
public record DbcMultiplexRange(long minimum, long maximum) {

    public DbcMultiplexRange {
        if (maximum < minimum) {
            throw new IllegalArgumentException("multiplex range maximum must be >= minimum");
        }
    }

    public boolean contains(long value) {
        return value >= minimum && value <= maximum;
    }

    public static DbcMultiplexRange ofSingle(long value) {
        return new DbcMultiplexRange(value, value);
    }
}
