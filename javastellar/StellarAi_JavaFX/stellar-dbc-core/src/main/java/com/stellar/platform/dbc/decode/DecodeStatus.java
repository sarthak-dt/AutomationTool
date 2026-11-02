package com.stellar.platform.dbc.decode;

/**
 * Per-frame decode outcome status.
 */
public enum DecodeStatus {
    DECODED,
    UNMATCHED,
    PARTIAL,
    MALFORMED,
    ERROR
}
