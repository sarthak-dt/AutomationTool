package com.stellar.platform.domain.processing.plan;

import java.util.Objects;

/** Stable processing-job identity: one SourceChannelRef (optionally plus definition). */
public final class ProcessingJobKey {

    private final SourceChannelRef channel;

    public ProcessingJobKey(SourceChannelRef channel) {
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    public static ProcessingJobKey of(SourceChannelRef channel) {
        return new ProcessingJobKey(channel);
    }

    public static ProcessingJobKey of(String projectSourceId, int channelId) {
        return new ProcessingJobKey(SourceChannelRef.of(projectSourceId, channelId));
    }

    public SourceChannelRef channel() {
        return channel;
    }

    public String projectSourceId() {
        return channel.projectSourceId();
    }

    public int channelId() {
        return channel.channelId();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ProcessingJobKey that && channel.equals(that.channel);
    }

    @Override
    public int hashCode() {
        return channel.hashCode();
    }

    @Override
    public String toString() {
        return channel.toString();
    }
}
