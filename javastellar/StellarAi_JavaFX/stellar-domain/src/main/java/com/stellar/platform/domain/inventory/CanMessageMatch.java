package com.stellar.platform.domain.inventory;

import java.util.Objects;
import java.util.Optional;

/**
 * One observed CAN message identity after DBC Message-ID matching for a channel.
 */
public final class CanMessageMatch {
    private final long canId;
    private final boolean extended;
    private final long observedFrameCount;
    private final boolean matched;
    private final String dbcMessageName;

    public CanMessageMatch(
            long canId,
            boolean extended,
            long observedFrameCount,
            boolean matched,
            String dbcMessageName
    ) {
        if (canId < 0) {
            throw new IllegalArgumentException("canId must be >= 0");
        }
        if (observedFrameCount < 0) {
            throw new IllegalArgumentException("observedFrameCount must be >= 0");
        }
        this.canId = canId;
        this.extended = extended;
        this.observedFrameCount = observedFrameCount;
        this.matched = matched;
        this.dbcMessageName = dbcMessageName == null || dbcMessageName.isBlank() ? null : dbcMessageName;
    }

    public long canId() {
        return canId;
    }

    public boolean extended() {
        return extended;
    }

    public long observedFrameCount() {
        return observedFrameCount;
    }

    public boolean matched() {
        return matched;
    }

    public Optional<String> dbcMessageName() {
        return Optional.ofNullable(dbcMessageName);
    }

    public String idTypeLabel() {
        return extended ? "Extended" : "Standard";
    }

    public String matchLabel() {
        return matched ? "MATCHED" : "UNMATCHED";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CanMessageMatch that)) {
            return false;
        }
        return canId == that.canId
                && extended == that.extended
                && observedFrameCount == that.observedFrameCount
                && matched == that.matched
                && Objects.equals(dbcMessageName, that.dbcMessageName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(canId, extended, observedFrameCount, matched, dbcMessageName);
    }
}
