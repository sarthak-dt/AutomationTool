package com.stellar.platform.domain.inventory;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Per-channel DBC Message-ID match result. */
public final class CanChannelMatchSummary {
    private final int channelId;
    private final String displayName;
    private final long observedCount;
    private final long matchedCount;
    private final long unmatchedCount;
    private final double matchPercentage;
    private final List<CanMessageMatch> messages;

    public CanChannelMatchSummary(
            int channelId,
            String displayName,
            long observedCount,
            long matchedCount,
            long unmatchedCount,
            double matchPercentage,
            List<CanMessageMatch> messages
    ) {
        if (channelId < 0) {
            throw new IllegalArgumentException("channelId must be >= 0");
        }
        this.channelId = channelId;
        this.displayName = displayName == null || displayName.isBlank()
                ? ("Channel " + channelId)
                : displayName;
        this.observedCount = Math.max(0L, observedCount);
        this.matchedCount = Math.max(0L, matchedCount);
        this.unmatchedCount = Math.max(0L, unmatchedCount);
        this.matchPercentage = matchPercentage;
        this.messages = List.copyOf(Objects.requireNonNull(messages, "messages")).stream()
                .sorted(Comparator
                        .comparingLong(CanMessageMatch::canId)
                        .thenComparing(CanMessageMatch::extended))
                .toList();
    }

    public int channelId() {
        return channelId;
    }

    public String displayName() {
        return displayName;
    }

    public long observedCount() {
        return observedCount;
    }

    public long matchedCount() {
        return matchedCount;
    }

    public long unmatchedCount() {
        return unmatchedCount;
    }

    public double matchPercentage() {
        return matchPercentage;
    }

    public List<CanMessageMatch> messages() {
        return messages;
    }

    public List<CanMessageMatch> matchedMessages() {
        return messages.stream().filter(CanMessageMatch::matched).toList();
    }

    public List<CanMessageMatch> unmatchedMessages() {
        return messages.stream().filter(m -> !m.matched()).toList();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CanChannelMatchSummary that)) {
            return false;
        }
        return channelId == that.channelId
                && observedCount == that.observedCount
                && matchedCount == that.matchedCount
                && unmatchedCount == that.unmatchedCount
                && Double.compare(matchPercentage, that.matchPercentage) == 0
                && Objects.equals(displayName, that.displayName)
                && Objects.equals(messages, that.messages);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                channelId,
                displayName,
                observedCount,
                matchedCount,
                unmatchedCount,
                matchPercentage,
                messages
        );
    }

}
