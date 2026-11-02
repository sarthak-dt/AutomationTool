package com.stellar.platform.domain.inventory;

/** Unique CAN-ID level match diagnostic against a parsed DBC. */
public enum CanIdMatchDiagnosticStatus {
    MATCHED,
    UNMATCHED,
    TYPE_MISMATCH
}
