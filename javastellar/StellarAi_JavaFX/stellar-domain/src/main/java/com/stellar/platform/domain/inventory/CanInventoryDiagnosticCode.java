package com.stellar.platform.domain.inventory;

/** Explicit inventory diagnostics. Distinct from DBC/signal processing diagnostics. */
public enum CanInventoryDiagnosticCode {
    NO_CAN_DATA,
    CHANNEL_DISCOVERY_FAILED,
    UNSUPPORTED_CAN_STRUCTURE,
    /** CAN bus-event structures exist but compressed/payload data could not be read. */
    CAN_PAYLOAD_UNREADABLE,
    INVENTORY_CANCELLED,
    SOURCE_READ_FAILED,
    FORMAT_UNSUPPORTED,
    /** A source line could not be read. Frames already accepted stay in the inventory. */
    MALFORMED_RECORD
}
