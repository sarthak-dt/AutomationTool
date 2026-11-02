package com.stellar.platform.domain.inventory;

/** Lifecycle of a per-source CAN inventory job. */
public enum CanInventoryStatus {
    FILE_ACCEPTED,
    DISCOVERING_CHANNELS,
    CHANNELS_DISCOVERED,
    INDEXING_MESSAGE_IDS,
    INVENTORY_READY,
    FAILED,
    CANCELLED
}
