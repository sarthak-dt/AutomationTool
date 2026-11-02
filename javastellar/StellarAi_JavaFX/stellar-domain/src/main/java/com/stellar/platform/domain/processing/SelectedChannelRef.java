package com.stellar.platform.domain.processing;

import java.util.Objects;

/**
 * Source-scoped CAN channel identity for processing selection.
 * Channel numbers alone are not unique across measurement files.
 */
public record SelectedChannelRef(String sourceIdentity, int channelId) {

    public SelectedChannelRef {
        if (sourceIdentity == null || sourceIdentity.isBlank()) {
            throw new IllegalArgumentException("sourceIdentity must not be blank");
        }
        if (channelId < 0) {
            throw new IllegalArgumentException("channelId must be >= 0");
        }
        sourceIdentity = sourceIdentity.trim();
    }

    public static SelectedChannelRef of(String sourceIdentity, int channelId) {
        return new SelectedChannelRef(sourceIdentity, channelId);
    }

    public String displayName() {
        return "Channel " + channelId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SelectedChannelRef that)) {
            return false;
        }
        return channelId == that.channelId && Objects.equals(sourceIdentity, that.sourceIdentity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceIdentity, channelId);
    }
}
