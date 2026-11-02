package com.stellar.platform.domain.inventory;

import java.util.Objects;

/** Aggregated unique CAN Message-ID observation for one channel. */
public final class CanMessageInventory {
    private final CanMessageKey key;
    private final long frameCount;
    private final int minObservedDlc;
    private final int maxObservedDlc;
    private final boolean canFdObserved;

    public CanMessageInventory(
            CanMessageKey key,
            long frameCount,
            int minObservedDlc,
            int maxObservedDlc,
            boolean canFdObserved
    ) {
        this.key = Objects.requireNonNull(key, "key");
        if (frameCount < 0) {
            throw new IllegalArgumentException("frameCount must be >= 0");
        }
        this.frameCount = frameCount;
        this.minObservedDlc = minObservedDlc;
        this.maxObservedDlc = maxObservedDlc;
        this.canFdObserved = canFdObserved;
    }

    public CanMessageKey key() {
        return key;
    }

    public int channel() {
        return key.channel();
    }

    public long canId() {
        return key.canId();
    }

    public boolean extended() {
        return key.extended();
    }

    public long frameCount() {
        return frameCount;
    }

    public int minObservedDlc() {
        return minObservedDlc;
    }

    public int maxObservedDlc() {
        return maxObservedDlc;
    }

    public boolean canFdObserved() {
        return canFdObserved;
    }

    public String idTypeLabel() {
        return extended() ? "Extended" : "Standard";
    }
}
