package com.stellar.platform.api.dto;

public record ProjectFileDto(
        String projectId,
        String fileId,
        String path,
        String kind,
        long sizeBytes,
        String validationStatus,
        String validationMessage,
        String associatedDefinitionId,
        boolean selectedMeasurement,
        boolean selectedDefinition
) {
    public ProjectFileDto {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId must not be blank");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        kind = kind == null ? "UNKNOWN" : kind;
        sizeBytes = Math.max(0L, sizeBytes);
        validationStatus = validationStatus == null ? "" : validationStatus;
        validationMessage = validationMessage == null ? "" : validationMessage;
        associatedDefinitionId = associatedDefinitionId == null ? "" : associatedDefinitionId;
    }
}
