package com.stellar.platform.api.assets;

import com.stellar.platform.api.FileAssetApi;
import com.stellar.platform.api.dto.BeginUploadRequest;
import com.stellar.platform.api.dto.FileAssetDto;
import com.stellar.platform.api.dto.RegisterLocalAssetRequest;
import com.stellar.platform.api.dto.UploadSessionDto;
import com.stellar.platform.api.identity.FileAssetKinds;
import com.stellar.platform.api.identity.PlatformIds;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Uploads a local file through {@link FileAssetApi} using bounded chunks, or registers a
 * LOCAL_LINK when the adapter supports {@link FileAssetApi#registerLocalAsset}.
 */
public final class FileAssetUploader {

    private FileAssetUploader() {
    }

    public static FileAssetDto ensureMeasurementAsset(
            FileAssetApi assets,
            String projectId,
            String sourceId,
            Path localFile,
            boolean preferLocalLink
    ) {
        return ensureAsset(assets, projectId, sourceId, FileAssetKinds.MEASUREMENT, localFile, preferLocalLink);
    }

    public static FileAssetDto ensureDefinitionAsset(
            FileAssetApi assets,
            String projectId,
            String sourceId,
            Path localFile,
            boolean preferLocalLink
    ) {
        return ensureAsset(assets, projectId, sourceId, FileAssetKinds.DEFINITION, localFile, preferLocalLink);
    }

    public static FileAssetDto ensureAsset(
            FileAssetApi assets,
            String projectId,
            String sourceId,
            String kind,
            Path localFile,
            boolean preferLocalLink
    ) {
        Objects.requireNonNull(assets, "assets");
        PlatformIds.requireId("projectId", projectId);
        Path path = Objects.requireNonNull(localFile, "localFile").toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Not a readable file: " + path);
        }
        if (preferLocalLink) {
            try {
                return assets.registerLocalAsset(projectId, new RegisterLocalAssetRequest(
                        sourceId,
                        kind,
                        path.toString(),
                        path.getFileName().toString(),
                        ""
                ));
            } catch (UnsupportedOperationException ignored) {
                // fall through to chunked upload
            }
        }
        return uploadManaged(assets, projectId, sourceId, kind, path);
    }

    public static FileAssetDto uploadManaged(
            FileAssetApi assets,
            String projectId,
            String sourceId,
            String kind,
            Path path
    ) {
        try {
            long totalBytes = Files.size(path);
            String sha = sha256Hex(path);
            UploadSessionDto session = assets.beginUpload(projectId, new BeginUploadRequest(
                    sourceId,
                    kind,
                    path.getFileName().toString(),
                    totalBytes,
                    sha
            ));
            int chunkSize = session.chunkSizeBytes() <= 0
                    ? FileAssetStore.DEFAULT_CHUNK_SIZE_BYTES
                    : session.chunkSizeBytes();
            int totalChunks = session.totalChunks();
            try (InputStream in = Files.newInputStream(path)) {
                for (int index = 0; index < totalChunks; index++) {
                    int len = (int) Math.min(chunkSize, totalBytes - (long) index * chunkSize);
                    byte[] buf = in.readNBytes(len);
                    if (buf.length != len) {
                        throw new IllegalStateException("Unexpected EOF uploading " + path);
                    }
                    assets.putChunk(projectId, session.uploadSessionId(), index, buf);
                }
            }
            return assets.completeUpload(projectId, session.uploadSessionId());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to upload asset from " + path, e);
        }
    }

    public static String sha256Hex(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file);
                 DigestInputStream din = new DigestInputStream(in, digest)) {
                din.transferTo(OutputStreamNull.INSTANCE);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class OutputStreamNull extends java.io.OutputStream {
        static final OutputStreamNull INSTANCE = new OutputStreamNull();

        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    }
}
