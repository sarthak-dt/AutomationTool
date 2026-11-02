package com.stellar.platform.api.dto;

import java.util.List;

public record InventoryChannelDto(
        int channelId,
        String displayName,
        long frameCount,
        long uniqueMessageCount,
        List<InventoryMessageDto> messages
) {
    public InventoryChannelDto {
        if (channelId < 0) {
            throw new IllegalArgumentException("channelId must be >= 0");
        }
        displayName = displayName == null ? ("Channel " + channelId) : displayName;
        frameCount = Math.max(0L, frameCount);
        uniqueMessageCount = Math.max(0L, uniqueMessageCount);
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
