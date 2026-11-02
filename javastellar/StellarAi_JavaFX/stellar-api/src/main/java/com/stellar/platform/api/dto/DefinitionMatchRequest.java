package com.stellar.platform.api.dto;

import com.stellar.platform.api.identity.PlatformIds;

public record DefinitionMatchRequest(String sourceId, String definitionAssetId) {
    public DefinitionMatchRequest {
        sourceId = PlatformIds.requireId("sourceId", sourceId);
        definitionAssetId = PlatformIds.requireId("definitionAssetId", definitionAssetId);
    }
}
