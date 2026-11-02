package com.stellar.platform.api.dto;

public record PlanPreviewDto(
        String projectId,
        String measurementAssetId,
        String definitionAssetId,
        String selectionLabel,
        boolean valid,
        String message
) {
    public PlanPreviewDto {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        measurementAssetId = measurementAssetId == null ? "" : measurementAssetId;
        definitionAssetId = definitionAssetId == null ? "" : definitionAssetId;
        selectionLabel = selectionLabel == null ? "" : selectionLabel;
        message = message == null ? "" : message;
    }
}
