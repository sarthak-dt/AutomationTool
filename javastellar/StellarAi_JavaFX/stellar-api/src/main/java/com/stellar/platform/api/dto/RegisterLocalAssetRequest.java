package com.stellar.platform.api.dto;

import com.stellar.platform.api.identity.PlatformIds;

/**
 * LOCAL-only: bind an existing validated filesystem path as a file asset without copying.
 * Never accepted as a remote processing input path — HTTP adapters reject this operation.
 */
public record RegisterLocalAssetRequest(
        String sourceId,
        String kind,
        String localPath,
        String originalFileName,
        String fileAssetId
) {
    public RegisterLocalAssetRequest {
        sourceId = PlatformIds.requireId("sourceId", sourceId);
        kind = PlatformIds.normalizeKind(kind);
        if (localPath == null || localPath.isBlank()) {
            throw new IllegalArgumentException("localPath must not be blank");
        }
        originalFileName = originalFileName == null || originalFileName.isBlank()
                ? PlatformIds.displayFileName(localPath)
                : PlatformIds.displayFileName(originalFileName);
        fileAssetId = PlatformIds.optionalId("fileAssetId", fileAssetId);
    }

    public RegisterLocalAssetRequest(String sourceId, String kind, String localPath) {
        this(sourceId, kind, localPath, "", "");
    }
}
