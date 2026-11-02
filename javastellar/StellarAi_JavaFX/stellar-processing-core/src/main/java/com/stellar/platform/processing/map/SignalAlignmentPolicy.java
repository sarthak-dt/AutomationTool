package com.stellar.platform.processing.map;

/** How a signal value is resolved when no sample exists exactly at the requested timestamp. */
public enum SignalAlignmentPolicy {
    /** Only an exact timestamp match is accepted. */
    EXACT,
    /** Closest sample within tolerance, in either direction. */
    NEAREST,
    /** Most recent sample at or before the timestamp — correct for state/enum signals. */
    HOLD_LAST,
    /** Linear interpolation between the bracketing samples — continuous numeric signals only. */
    LINEAR
}
