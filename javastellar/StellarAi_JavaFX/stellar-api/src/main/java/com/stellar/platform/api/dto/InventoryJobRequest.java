package com.stellar.platform.api.dto;

import com.stellar.platform.api.identity.PlatformIds;

public record InventoryJobRequest(String sourceId, String measurementAssetId, int batchSize) {
    public InventoryJobRequest {
        measurementAssetId = PlatformIds.requireId("measurementAssetId", measurementAssetId);
        sourceId = sourceId == null || sourceId.isBlank() ? measurementAssetId : PlatformIds.requireId("sourceId", sourceId);
        batchSize = batchSize <= 0 ? 512 : batchSize;
    }

    public InventoryJobRequest(String sourceId, String measurementAssetId) {
        this(sourceId, measurementAssetId, 512);
    }
}
