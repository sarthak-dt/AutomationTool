package com.stellar.platform.api.dto;

import com.stellar.platform.api.identity.PlatformIds;

/** Start a bounded chunked upload for a measurement or definition asset. */
public record BeginUploadRequest(
        String sourceId,
        String kind,
        String originalFileName,
        long totalBytes,
        String expectedSha256,
        String fileAssetId
) {
    public BeginUploadRequest {
        sourceId = PlatformIds.requireId("sourceId", sourceId);
        kind = PlatformIds.normalizeKind(kind);
        originalFileName = PlatformIds.displayFileName(originalFileName);
        if (totalBytes < 0L) {
            throw new IllegalArgumentException("totalBytes must be >= 0");
        }
        expectedSha256 = expectedSha256 == null ? "" : expectedSha256.trim().toLowerCase(java.util.Locale.ROOT);
        fileAssetId = PlatformIds.optionalId("fileAssetId", fileAssetId);
    }

    public BeginUploadRequest(String sourceId, String kind, String originalFileName, long totalBytes, String expectedSha256) {
        this(sourceId, kind, originalFileName, totalBytes, expectedSha256, "");
    }
}
