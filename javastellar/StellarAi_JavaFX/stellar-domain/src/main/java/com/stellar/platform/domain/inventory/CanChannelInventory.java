package com.stellar.platform.domain.inventory;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Aggregated CAN bus/channel inventory for one source. */
public final class CanChannelInventory {
    private final int channelId;
    private final String displayName;
    private final long frameCount;
    private final long uniqueMessageCount;
    private final long standardMessageCount;
    private final long extendedMessageCount;
    private final long canFdFrameCount;
    private final List<CanMessageInventory> messages;

    public CanChannelInventory(
            int channelId,
            String displayName,
            long frameCount,
            long uniqueMessageCount,
            long standardMessageCount,
            long extendedMessageCount,
            long canFdFrameCount,
            List<CanMessageInventory> messages
    ) {
        if (channelId < 0) {
            throw new IllegalArgumentException("channelId must be >= 0");
        }
        this.channelId = channelId;
        this.displayName = displayName == null || displayName.isBlank()
                ? ("Channel " + channelId)
                : displayName;
        this.frameCount = Math.max(0L, frameCount);
        this.uniqueMessageCount = Math.max(0L, uniqueMessageCount);
        this.standardMessageCount = Math.max(0L, standardMessageCount);
        this.extendedMessageCount = Math.max(0L, extendedMessageCount);
        this.canFdFrameCount = Math.max(0L, canFdFrameCount);
        List<CanMessageInventory> copy = List.copyOf(Objects.requireNonNull(messages, "messages"));
        this.messages = copy.stream()
                .sorted(Comparator
                        .comparingLong(CanMessageInventory::canId)
                        .thenComparing(CanMessageInventory::extended))
                .toList();
    }

    public int channelId() {
        return channelId;
    }

    public String displayName() {
        return displayName;
    }

    public long frameCount() {
        return frameCount;
    }

    public long uniqueMessageCount() {
        return uniqueMessageCount;
    }

    public long standardMessageCount() {
        return standardMessageCount;
    }

    public long extendedMessageCount() {
        return extendedMessageCount;
    }

    public long canFdFrameCount() {
        return canFdFrameCount;
    }

    public List<CanMessageInventory> messages() {
        return messages;
    }
}
