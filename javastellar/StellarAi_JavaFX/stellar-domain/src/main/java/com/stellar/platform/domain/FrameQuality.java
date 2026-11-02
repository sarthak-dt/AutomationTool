package com.stellar.platform.domain;

/**
 * Quality indicator for a parsed canonical CAN frame.
 */
public enum FrameQuality {
    /** Finalized / normal decode path (legacy alias used by ASC and older callers). */
    OK,
    /** Finalized / structurally normal MF4 (or other) decode. */
    VALID,
    /** Produced by MF4 recovery from unfinalized / inconsistent metadata. */
    RECOVERED,
    ERROR,
    INCOMPLETE,
    UNKNOWN
}
