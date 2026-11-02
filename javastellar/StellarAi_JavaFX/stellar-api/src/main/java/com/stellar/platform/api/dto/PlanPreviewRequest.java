package com.stellar.platform.api.dto;

import com.stellar.platform.api.identity.PlatformIds;

public record PlanPreviewRequest(
        String measurementAssetId,
        String definitionAssetId,
        String selectionLabel
) {
    public PlanPreviewRequest {
        measurementAssetId = PlatformIds.requireId("measurementAssetId", measurementAssetId);
        definitionAssetId = PlatformIds.requireId("definitionAssetId", definitionAssetId);
        selectionLabel = selectionLabel == null ? "All channels" : selectionLabel;
    }
}
