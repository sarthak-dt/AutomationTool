package com.stellar.platform.api.dto;

import java.util.List;

/** Upload session / asset status for chunked transfer. */
public record UploadSessionDto(
        String projectId,
        String uploadSessionId,
        String fileAssetId,
        String sourceId,
        String kind,
        String originalFileName,
        long totalBytes,
        long receivedBytes,
        int chunkSizeBytes,
        int totalChunks,
        List<Integer> receivedChunkIndexes,
        String expectedSha256,
        String status,
        String message
) {
    public UploadSessionDto {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        if (uploadSessionId == null || uploadSessionId.isBlank()) {
            throw new IllegalArgumentException("uploadSessionId must not be blank");
        }
        fileAssetId = fileAssetId == null || fileAssetId.isBlank() ? uploadSessionId : fileAssetId;
        sourceId = sourceId == null ? "" : sourceId;
        kind = kind == null ? "" : kind;
        originalFileName = originalFileName == null ? "" : originalFileName;
        totalBytes = Math.max(0L, totalBytes);
        receivedBytes = Math.max(0L, receivedBytes);
        chunkSizeBytes = Math.max(0, chunkSizeBytes);
        totalChunks = Math.max(0, totalChunks);
        receivedChunkIndexes = receivedChunkIndexes == null ? List.of() : List.copyOf(receivedChunkIndexes);
        expectedSha256 = expectedSha256 == null ? "" : expectedSha256;
        status = status == null ? "" : status;
        message = message == null ? "" : message;
    }
}
