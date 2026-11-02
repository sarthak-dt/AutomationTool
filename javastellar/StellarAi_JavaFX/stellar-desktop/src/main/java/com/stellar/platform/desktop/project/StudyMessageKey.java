package com.stellar.platform.desktop.project;

import java.util.Objects;

/**
 * Stable Study Set identity: one observed CAN message on one source channel.
 * Never keyed by CAN ID alone — Source A/Ch3/0x453 ≠ Source B/Ch3/0x453.
 */
public final class StudyMessageKey {

    private final SourceChannelKey channelKey;
    private final long canId;
    private final boolean extended;

    public StudyMessageKey(SourceChannelKey channelKey, long canId, boolean extended) {
        this.channelKey = Objects.requireNonNull(channelKey, "channelKey");
        this.canId = canId;
        this.extended = extended;
    }

    public static StudyMessageKey of(SourceChannelKey channelKey, long canId, boolean extended) {
        return new StudyMessageKey(channelKey, canId, extended);
    }

    public SourceChannelKey channelKey() {
        return channelKey;
    }

    public ProjectSourceId sourceId() {
        return channelKey.sourceId();
    }

    public int channelId() {
        return channelKey.channelId();
    }

    public long canId() {
        return canId;
    }

    public boolean extended() {
        return extended;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StudyMessageKey that
                && channelKey.equals(that.channelKey)
                && canId == that.canId
                && extended == that.extended;
    }

    @Override
    public int hashCode() {
        return Objects.hash(channelKey, canId, extended);
    }

    @Override
    public String toString() {
        return channelKey + "/0x" + Long.toHexString(canId).toUpperCase()
                + (extended ? "E" : "S");
    }
}
