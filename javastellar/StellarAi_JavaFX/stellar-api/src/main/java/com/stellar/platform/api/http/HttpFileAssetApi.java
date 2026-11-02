package com.stellar.platform.api.http;

import com.stellar.platform.api.FileAssetApi;
import com.stellar.platform.api.dto.BeginUploadRequest;
import com.stellar.platform.api.dto.FileAssetDto;
import com.stellar.platform.api.dto.RegisterLocalAssetRequest;
import com.stellar.platform.api.dto.UploadSessionDto;

import java.util.Objects;
import java.util.Optional;

final class HttpFileAssetApi implements FileAssetApi {

    private final HttpTransport transport;

    HttpFileAssetApi(HttpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public UploadSessionDto beginUpload(String projectId, BeginUploadRequest request) {
        return transport.post(
                "/api/v1/projects/" + HttpTransport.enc(projectId) + "/assets/uploads",
                request,
                UploadSessionDto.class
        );
    }

    @Override
    public UploadSessionDto putChunk(String projectId, String uploadSessionId, int chunkIndex, byte[] data) {
        return transport.putBytes(
                "/api/v1/projects/" + HttpTransport.enc(projectId)
                        + "/assets/uploads/" + HttpTransport.enc(uploadSessionId)
                        + "/chunks/" + chunkIndex,
                data,
                "application/octet-stream",
                UploadSessionDto.class
        );
    }

    @Override
    public UploadSessionDto getUploadStatus(String projectId, String uploadSessionId) {
        return transport.get(
                "/api/v1/projects/" + HttpTransport.enc(projectId)
                        + "/assets/uploads/" + HttpTransport.enc(uploadSessionId),
                UploadSessionDto.class
        );
    }

    @Override
    public FileAssetDto completeUpload(String projectId, String uploadSessionId) {
        return transport.post(
                "/api/v1/projects/" + HttpTransport.enc(projectId)
                        + "/assets/uploads/" + HttpTransport.enc(uploadSessionId) + "/complete",
                null,
                FileAssetDto.class
        );
    }

    @Override
    public UploadSessionDto cancelUpload(String projectId, String uploadSessionId) {
        return transport.post(
                "/api/v1/projects/" + HttpTransport.enc(projectId)
                        + "/assets/uploads/" + HttpTransport.enc(uploadSessionId) + "/cancel",
                null,
                UploadSessionDto.class
        );
    }

    @Override
    public Optional<FileAssetDto> getAsset(String projectId, String fileAssetId) {
        try {
            return Optional.ofNullable(transport.get(
                    "/api/v1/projects/" + HttpTransport.enc(projectId)
                            + "/assets/" + HttpTransport.enc(fileAssetId),
                    FileAssetDto.class
            ));
        } catch (RuntimeException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("HTTP 404")) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    @Override
    public FileAssetDto registerLocalAsset(String projectId, RegisterLocalAssetRequest request) {
        throw new UnsupportedOperationException(
                "registerLocalAsset is LOCAL-only; use chunked upload for HTTP targets");
    }
}
