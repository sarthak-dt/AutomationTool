package com.stellar.platform.api.local;

import com.stellar.platform.api.FileAssetApi;
import com.stellar.platform.api.assets.FileAssetStore;
import com.stellar.platform.api.dto.BeginUploadRequest;
import com.stellar.platform.api.dto.FileAssetDto;
import com.stellar.platform.api.dto.RegisterLocalAssetRequest;
import com.stellar.platform.api.dto.UploadSessionDto;
import com.stellar.platform.api.support.MetadataStoreFactory;
import com.stellar.platform.api.support.ProcessingLog;
import com.stellar.platform.api.support.PlatformMetrics;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public final class LocalFileAssetApi implements FileAssetApi {

    private final FileAssetStore store;
    private final MetadataStoreFactory stores;

    public LocalFileAssetApi(FileAssetStore store, MetadataStoreFactory stores) {
        this.store = Objects.requireNonNull(store, "store");
        this.stores = Objects.requireNonNull(stores, "stores");
    }

    public FileAssetStore store() {
        return store;
    }

    @Override
    public UploadSessionDto beginUpload(String projectId, BeginUploadRequest request) {
        requireProject(projectId);
        UploadSessionDto session = store.beginUpload(projectId, request);
        ProcessingLog.info(
                "upload.begin",
                projectId,
                null,
                null,
                request.sourceId(),
                session.fileAssetId(),
                session.status(),
                null,
                null,
                "bytes=" + request.totalBytes()
        );
        return session;
    }

    @Override
    public UploadSessionDto putChunk(String projectId, String uploadSessionId, int chunkIndex, byte[] data) {
        requireProject(projectId);
        return store.putChunk(projectId, uploadSessionId, chunkIndex, data);
    }

    @Override
    public UploadSessionDto getUploadStatus(String projectId, String uploadSessionId) {
        requireProject(projectId);
        return store.status(projectId, uploadSessionId);
    }

    @Override
    public FileAssetDto completeUpload(String projectId, String uploadSessionId) {
        requireProject(projectId);
        FileAssetDto asset = store.complete(projectId, uploadSessionId);
        PlatformMetrics.get().increment("upload.completed");
        ProcessingLog.info(
                "upload.complete",
                projectId,
                null,
                null,
                asset.sourceId(),
                asset.fileAssetId(),
                asset.status(),
                null,
                null,
                "sha256=present"
        );
        return asset;
    }

    @Override
    public UploadSessionDto cancelUpload(String projectId, String uploadSessionId) {
        requireProject(projectId);
        return store.cancel(projectId, uploadSessionId);
    }

    @Override
    public Optional<FileAssetDto> getAsset(String projectId, String fileAssetId) {
        requireProject(projectId);
        return store.getAsset(projectId, fileAssetId);
    }

    @Override
    public FileAssetDto registerLocalAsset(String projectId, RegisterLocalAssetRequest request) {
        requireProject(projectId);
        return store.registerLocal(projectId, request);
    }

    public Path resolveReadablePath(String projectId, String fileAssetId) {
        return store.resolveReadablePath(projectId, fileAssetId);
    }

    private void requireProject(String projectId) {
        if (stores.store().loadProject(projectId).isEmpty()
                && stores.store().listProjects().stream().noneMatch(p -> p.projectId().equals(projectId))) {
            throw new IllegalArgumentException("Unknown project: " + projectId);
        }
    }
}
