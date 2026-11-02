package com.stellar.platform.api;

import com.stellar.platform.api.dto.BeginUploadRequest;
import com.stellar.platform.api.dto.FileAssetDto;
import com.stellar.platform.api.dto.RegisterLocalAssetRequest;
import com.stellar.platform.api.dto.UploadSessionDto;

import java.util.Optional;

/**
 * Chunked file-asset transfer for measurements and definitions.
 * Cross-boundary contracts use asset ids — never desktop absolute paths.
 */
public interface FileAssetApi {

    UploadSessionDto beginUpload(String projectId, BeginUploadRequest request);

    UploadSessionDto putChunk(String projectId, String uploadSessionId, int chunkIndex, byte[] data);

    UploadSessionDto getUploadStatus(String projectId, String uploadSessionId);

    FileAssetDto completeUpload(String projectId, String uploadSessionId);

    UploadSessionDto cancelUpload(String projectId, String uploadSessionId);

    Optional<FileAssetDto> getAsset(String projectId, String fileAssetId);

    /**
     * LOCAL only: bind an existing file without copying. HTTP implementations reject this.
     */
    FileAssetDto registerLocalAsset(String projectId, RegisterLocalAssetRequest request);
}
