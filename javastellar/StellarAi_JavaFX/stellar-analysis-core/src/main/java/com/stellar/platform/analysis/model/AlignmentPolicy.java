package com.stellar.platform.analysis.model;

/**
 * Explicit two-signal alignment. The default is conservative: never invent values.
 */
public enum AlignmentPolicy {
    /** Pair samples only when timestamps are identical. */
    EXACT_TIMESTAMP,
    /** Hold the last observed value of the slower/discrete channel (zero-order hold). */
    PREVIOUS_VALUE_HOLD,
    /** Pair to the nearest sample within {@code nearestToleranceNanos}. */
    NEAREST_WITH_TOLERANCE,
    /** Linear interpolate numeric continuous signals only when the request opts in. */
    OPTIONAL_LINEAR_INTERPOLATION
}
