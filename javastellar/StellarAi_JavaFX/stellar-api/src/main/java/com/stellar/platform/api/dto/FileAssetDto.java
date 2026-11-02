package com.stellar.platform.api.dto;

/** Committed file asset metadata. Physical path is never part of the cross-boundary contract. */
public record FileAssetDto(
        String projectId,
        String fileAssetId,
        String sourceId,
        String kind,
        String originalFileName,
        long sizeBytes,
        String sha256,
        String status,
        String storageMode
) {
    public FileAssetDto {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        if (fileAssetId == null || fileAssetId.isBlank()) {
            throw new IllegalArgumentException("fileAssetId must not be blank");
        }
        sourceId = sourceId == null ? "" : sourceId;
        kind = kind == null ? "" : kind;
        originalFileName = originalFileName == null ? "" : originalFileName;
        sizeBytes = Math.max(0L, sizeBytes);
        sha256 = sha256 == null ? "" : sha256;
        status = status == null ? "" : status;
        storageMode = storageMode == null ? "" : storageMode;
    }
}
