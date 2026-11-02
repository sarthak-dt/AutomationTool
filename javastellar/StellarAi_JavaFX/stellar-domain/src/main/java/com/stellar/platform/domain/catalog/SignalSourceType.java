package com.stellar.platform.domain.catalog;

/**
 * Provenance of an engineering signal in the dynamic catalog.
 */
public enum SignalSourceType {
    /** Decoded via Common DBC Decoder from a CAN/CAN FD frame. */
    DBC_CAN,
    /** Native MF4 measurement channel (CN) with CC conversion — not a synthetic CAN frame. */
    MF4_NATIVE,
    /**
     * Analysis-derived series (difference, rate, expression). Never a rewrite of source samples.
     */
    ANALYSIS_DERIVED,
    /** Reserved for future computed / derived signals. */
    COMPUTED_FUTURE
}
