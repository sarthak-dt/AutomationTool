package com.stellar.platform.domain.inventory;

/**
 * Unique identity of a CAN message observation within one source inventory.
 * Same numeric ID on different channels, or STANDARD vs EXTENDED, are distinct.
 */
public record CanMessageKey(int channel, long canId, boolean extended) {
    public CanMessageKey {
        if (channel < 0) {
            throw new IllegalArgumentException("channel must be >= 0");
        }
        if (canId < 0) {
            throw new IllegalArgumentException("canId must be >= 0");
        }
    }
}
