package com.stellar.platform.domain.inventory;

/**
 * Status of DBC Message-ID matching against a {@link CanSourceInventory}.
 * Distinct from inventory and parser statuses.
 */
public enum CanDefinitionMatchStatus {
    NO_DBC_SELECTED,
    WAITING_FOR_SOURCE_INVENTORY,
    MATCHING,
    READY,
    NO_MATCHING_MESSAGE_IDS,
    FAILED
}
