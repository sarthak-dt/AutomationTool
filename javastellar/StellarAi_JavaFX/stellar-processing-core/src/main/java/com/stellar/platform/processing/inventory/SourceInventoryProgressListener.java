package com.stellar.platform.processing.inventory;

import com.stellar.platform.domain.inventory.CanSourceInventory;

/**
 * Progressive inventory notifications. Implementations must throttle UI updates.
 */
@FunctionalInterface
public interface SourceInventoryProgressListener {

    SourceInventoryProgressListener NOOP = inventory -> { };

    void onInventoryUpdated(CanSourceInventory inventory);
}
