package com.stellar.platform.domain.processing.plan;

import java.util.Objects;

/**
 * Stable project-scoped channel identity for processing selection/plan.
 * Channel numbers alone are never unique across measurement sources.
 * Compatible with desktop {@code ProjectSourceId}/{@code SourceChannelKey} via string ids.
 */
public final class SourceChannelRef {

    private final String projectSourceId;
    private final int channelId;

    public SourceChannelRef(String projectSourceId, int channelId) {
        if (projectSourceId == null || projectSourceId.isBlank()) {
            throw new IllegalArgumentException("projectSourceId must not be blank");
        }
        if (channelId < 0) {
            throw new IllegalArgumentException("channelId must be >= 0");
        }
        this.projectSourceId = projectSourceId.trim();
        this.channelId = channelId;
    }

    public static SourceChannelRef of(String projectSourceId, int channelId) {
        return new SourceChannelRef(projectSourceId, channelId);
    }

    public String projectSourceId() {
        return projectSourceId;
    }

    public int channelId() {
        return channelId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SourceChannelRef that
                && channelId == that.channelId
                && projectSourceId.equals(that.projectSourceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectSourceId, channelId);
    }

    @Override
    public String toString() {
        return projectSourceId + "#ch" + channelId;
    }
}
