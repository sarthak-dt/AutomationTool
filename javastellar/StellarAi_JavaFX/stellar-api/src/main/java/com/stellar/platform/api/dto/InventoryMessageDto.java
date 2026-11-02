package com.stellar.platform.api.dto;

import java.util.List;

public record InventoryMessageDto(long canId, boolean extended, long frameCount) {
    public InventoryMessageDto {
        frameCount = Math.max(0L, frameCount);
    }
}
