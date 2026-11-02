package com.stellar.platform.api.dto;

import com.stellar.platform.api.identity.PlatformIds;

/** Process a measurement+definition pair identified by committed file assets. */
public record ProcessJobRequest(
        String sourceId,
        String measurementAssetId,
        String definitionAssetId,
        int batchSize,
        int maxSamplesPerSignal
) {
    public ProcessJobRequest {
        sourceId = PlatformIds.optionalId("sourceId", sourceId);
        measurementAssetId = PlatformIds.requireId("measurementAssetId", measurementAssetId);
        definitionAssetId = PlatformIds.requireId("definitionAssetId", definitionAssetId);
        batchSize = batchSize <= 0 ? 4096 : batchSize;
        maxSamplesPerSignal = maxSamplesPerSignal <= 0 ? 50_000 : maxSamplesPerSignal;
    }

    public ProcessJobRequest(String measurementAssetId, String definitionAssetId) {
        this("", measurementAssetId, definitionAssetId, 4096, 50_000);
    }

    public ProcessJobRequest(String sourceId, String measurementAssetId, String definitionAssetId) {
        this(sourceId, measurementAssetId, definitionAssetId, 4096, 50_000);
    }
}
