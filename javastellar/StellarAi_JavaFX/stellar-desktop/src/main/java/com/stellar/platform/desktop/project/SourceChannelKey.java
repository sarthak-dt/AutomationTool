package com.stellar.platform.desktop.project;

import java.util.Objects;

/**
 * Project-safe identity for one channel within one measurement source.
 * Channel numbers alone are never globally unique across sources.
 */
public final class SourceChannelKey {

    private final ProjectSourceId sourceId;
    private final int channelId;

    public SourceChannelKey(ProjectSourceId sourceId, int channelId) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        if (channelId < 0) {
            throw new IllegalArgumentException("channelId must be >= 0");
        }
        this.channelId = channelId;
    }

    public static SourceChannelKey of(ProjectSourceId sourceId, int channelId) {
        return new SourceChannelKey(sourceId, channelId);
    }

    public ProjectSourceId sourceId() {
        return sourceId;
    }

    public int channelId() {
        return channelId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SourceChannelKey that
                && sourceId.equals(that.sourceId)
                && channelId == that.channelId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceId, channelId);
    }

    @Override
    public String toString() {
        return sourceId.value() + "#ch" + channelId;
    }
}
