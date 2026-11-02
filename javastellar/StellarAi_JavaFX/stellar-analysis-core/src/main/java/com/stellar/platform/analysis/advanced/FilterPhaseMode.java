package com.stellar.platform.analysis.advanced;

public enum FilterPhaseMode {
    /** Forward-backward (filtfilt). Approximately zero phase; edge transients remain. */
    ZERO_PHASE,
    /** Causal IIR. Phase delay is present and reported. */
    CAUSAL
}
