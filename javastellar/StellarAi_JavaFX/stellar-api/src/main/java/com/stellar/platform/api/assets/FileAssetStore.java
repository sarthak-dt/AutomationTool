package com.stellar.platform.api.assets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stellar.platform.api.dto.BeginUploadRequest;
import com.stellar.platform.api.dto.FileAssetDto;
import com.stellar.platform.api.dto.RegisterLocalAssetRequest;
import com.stellar.platform.api.dto.UploadSessionDto;
import com.stellar.platform.api.identity.FileAssetStatuses;
import com.stellar.platform.api.identity.PlatformIds;
import com.stellar.platform.api.support.ApiJson;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sandboxed file-asset store with bounded chunk upload, resume, and SHA-256 commit.
 * Clients never choose physical destination paths under the data root.
 */
public final class FileAssetStore {

    public static final int DEFAULT_CHUNK_SIZE_BYTES = 4 * 1024 * 1024;
    /** Default max managed upload size (1 GiB). Configurable via constructor. */
    public static final long DEFAULT_MAX_UPLOAD_BYTES = 1024L * 1024L * 1024L;
    public static final String MODE_MANAGED = "MANAGED";
    public static final String MODE_LOCAL_LINK = "LOCAL_LINK";

    private static final String META_FILE = "asset.json";
    private static final String STATE_FILE = "upload-state.json";
    private static final String CONTENT_FILE_DEFAULT = "content.bin";

    private final Path assetsRoot;
    private final int chunkSizeBytes;
    private final long maxUploadBytes;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public FileAssetStore(Path dataRoot) {
        this(dataRoot, DEFAULT_CHUNK_SIZE_BYTES, DEFAULT_MAX_UPLOAD_BYTES);
    }

    public FileAssetStore(Path dataRoot, int chunkSizeBytes) {
        this(dataRoot, chunkSizeBytes, DEFAULT_MAX_UPLOAD_BYTES);
    }

    public FileAssetStore(Path dataRoot, int chunkSizeBytes, long maxUploadBytes) {
        Path root = Objects.requireNonNull(dataRoot, "dataRoot").toAbsolutePath().normalize();
        this.assetsRoot = root.resolve("assets");
        if (chunkSizeBytes < 4) {
            throw new IllegalArgumentException("chunkSizeBytes must be >= 4");
        }
        if (maxUploadBytes < chunkSizeBytes) {
            throw new IllegalArgumentException("maxUploadBytes must be >= chunkSizeBytes");
        }
        this.chunkSizeBytes = chunkSizeBytes;
        this.maxUploadBytes = maxUploadBytes;
        this.mapper = ApiJson.mapper();
        try {
            Files.createDirectories(this.assetsRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create assets root " + this.assetsRoot, e);
        }
    }

    public Path assetsRoot() {
        return assetsRoot;
    }

    public int chunkSizeBytes() {
        return chunkSizeBytes;
    }

    public long maxUploadBytes() {
        return maxUploadBytes;
    }

    public UploadSessionDto beginUpload(String projectId, BeginUploadRequest request) {
        String pid = PlatformIds.requireId("projectId", projectId);
        Objects.requireNonNull(request, "request");
        if (request.totalBytes() < 0L) {
            throw new IllegalArgumentException("totalBytes must be >= 0");
        }
        if (request.totalBytes() > maxUploadBytes) {
            throw new IllegalArgumentException(
                    "Upload exceeds max size " + maxUploadBytes + " bytes (got " + request.totalBytes() + ")");
        }
        String assetId = request.fileAssetId().isBlank() ? PlatformIds.newId() : request.fileAssetId();
        PlatformIds.requireId("fileAssetId", assetId);
        PlatformIds.requireId("sourceId", request.sourceId());
        synchronized (lock(pid, assetId)) {
            Path dir = assetDir(pid, assetId);
            Optional<AssetRecord> existing = readMeta(dir);
            if (existing.isPresent() && FileAssetStatuses.COMPLETE.equals(existing.get().status())) {
                throw new IllegalStateException("Asset already committed: " + assetId);
            }
            int totalChunks = totalChunks(request.totalBytes());
            UploadState state = new UploadState(
                    pid,
                    assetId,
                    assetId,
                    request.sourceId(),
                    request.kind(),
                    request.originalFileName(),
                    request.totalBytes(),
                    0L,
                    chunkSizeBytes,
                    totalChunks,
                    new BitSet(Math.max(1, totalChunks)),
                    normalizeSha(request.expectedSha256()),
                    FileAssetStatuses.UPLOADING,
                    "Upload started",
                    MODE_MANAGED,
                    ""
            );
            try {
                Files.createDirectories(dir);
                Path content = contentPath(dir, request.originalFileName());
                if (!Files.exists(content)) {
                    try (RandomAccessFile raf = new RandomAccessFile(content.toFile(), "rw")) {
                        raf.setLength(request.totalBytes());
                    }
                }
                writeState(dir, state.withResolvedPath(content.toString()));
                writeMeta(dir, AssetRecord.fromUpload(state.withResolvedPath(content.toString())));
                return toSession(state.withResolvedPath(content.toString()));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to begin upload for asset " + assetId, e);
            }
        }
    }

    public UploadSessionDto putChunk(String projectId, String uploadSessionId, int chunkIndex, byte[] data) {
        String pid = PlatformIds.requireId("projectId", projectId);
        String sessionId = PlatformIds.requireId("uploadSessionId", uploadSessionId);
        if (data == null) {
            throw new IllegalArgumentException("chunk data must not be null");
        }
        synchronized (lock(pid, sessionId)) {
            Path dir = assetDir(pid, sessionId);
            UploadState state = readState(dir)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown upload: " + sessionId));
            if (!FileAssetStatuses.UPLOADING.equals(state.status())) {
                throw new IllegalStateException("Upload is not active: " + state.status());
            }
            if (chunkIndex < 0 || chunkIndex >= state.totalChunks()) {
                throw new IllegalArgumentException("chunkIndex out of range: " + chunkIndex);
            }
            int expectedLen = chunkLength(state.totalBytes(), chunkIndex, state.chunkSizeBytes());
            if (data.length != expectedLen) {
                throw new IllegalArgumentException(
                        "chunk length mismatch: expected " + expectedLen + " got " + data.length);
            }
            if (state.received().get(chunkIndex)) {
                // Idempotent retry of an already committed chunk.
                return toSession(state);
            }
            long offset = (long) chunkIndex * state.chunkSizeBytes();
            Path content = resolveContentPath(dir, state);
            try (RandomAccessFile raf = new RandomAccessFile(content.toFile(), "rw")) {
                raf.seek(offset);
                raf.write(data);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write chunk " + chunkIndex, e);
            }
            BitSet next = (BitSet) state.received().clone();
            next.set(chunkIndex);
            long receivedBytes = receivedBytes(next, state.totalBytes(), state.chunkSizeBytes(), state.totalChunks());
            UploadState updated = state.withReceived(next, receivedBytes, "Chunk " + chunkIndex + " stored");
            writeState(dir, updated);
            writeMeta(dir, AssetRecord.fromUpload(updated));
            return toSession(updated);
        }
    }

    public UploadSessionDto status(String projectId, String uploadSessionId) {
        String pid = PlatformIds.requireId("projectId", projectId);
        String sessionId = PlatformIds.requireId("uploadSessionId", uploadSessionId);
        Path dir = assetDir(pid, sessionId);
        return readState(dir)
                .map(FileAssetStore::toSession)
                .or(() -> readMeta(dir).map(AssetRecord::toSession))
                .orElseThrow(() -> new IllegalArgumentException("Unknown upload: " + sessionId));
    }

    public FileAssetDto complete(String projectId, String uploadSessionId) {
        String pid = PlatformIds.requireId("projectId", projectId);
        String sessionId = PlatformIds.requireId("uploadSessionId", uploadSessionId);
        synchronized (lock(pid, sessionId)) {
            Path dir = assetDir(pid, sessionId);
            UploadState state = readState(dir)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown upload: " + sessionId));
            if (FileAssetStatuses.COMPLETE.equals(state.status())) {
                return readMeta(dir).orElseThrow().toDto();
            }
            if (!FileAssetStatuses.UPLOADING.equals(state.status())) {
                throw new IllegalStateException("Cannot complete upload in status " + state.status());
            }
            if (state.totalChunks() > 0 && state.received().cardinality() != state.totalChunks()) {
                throw new IllegalStateException("Upload incomplete: "
                        + state.received().cardinality() + "/" + state.totalChunks() + " chunks");
            }
            Path content = resolveContentPath(dir, state);
            String actualSha;
            try {
                if (Files.size(content) != state.totalBytes()) {
                    fail(dir, state, "Byte length mismatch after upload");
                    throw new IllegalStateException("Byte length mismatch after upload");
                }
                actualSha = sha256Hex(content);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to verify uploaded asset", e);
            }
            if (!state.expectedSha256().isBlank()
                    && !state.expectedSha256().equalsIgnoreCase(actualSha)) {
                fail(dir, state, "SHA-256 mismatch");
                throw new IllegalStateException("SHA-256 mismatch for asset " + sessionId);
            }
            UploadState done = state.withStatus(
                    FileAssetStatuses.COMPLETE,
                    MODE_MANAGED,
                    content.toString(),
                    "Upload committed"
            );
            writeState(dir, done);
            AssetRecord record = new AssetRecord(
                    done.projectId(),
                    done.fileAssetId(),
                    done.sourceId(),
                    done.kind(),
                    done.originalFileName(),
                    done.totalBytes(),
                    actualSha,
                    FileAssetStatuses.COMPLETE,
                    MODE_MANAGED,
                    content.toString()
            );
            writeMeta(dir, record);
            return record.toDto();
        }
    }

    public UploadSessionDto cancel(String projectId, String uploadSessionId) {
        String pid = PlatformIds.requireId("projectId", projectId);
        String sessionId = PlatformIds.requireId("uploadSessionId", uploadSessionId);
        synchronized (lock(pid, sessionId)) {
            Path dir = assetDir(pid, sessionId);
            UploadState state = readState(dir)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown upload: " + sessionId));
            if (FileAssetStatuses.COMPLETE.equals(state.status())) {
                throw new IllegalStateException("Cannot cancel committed asset: " + sessionId);
            }
            UploadState cancelled = state.withStatus(
                    FileAssetStatuses.CANCELLED,
                    state.storageMode(),
                    state.resolvedPath(),
                    "Upload cancelled"
            );
            writeState(dir, cancelled);
            writeMeta(dir, AssetRecord.fromUpload(cancelled));
            try {
                Path content = resolveContentPath(dir, state);
                Files.deleteIfExists(content);
            } catch (IOException ignored) {
                // best effort
            }
            return toSession(cancelled);
        }
    }

    public FileAssetDto registerLocal(String projectId, RegisterLocalAssetRequest request) {
        String pid = PlatformIds.requireId("projectId", projectId);
        Objects.requireNonNull(request, "request");
        Path local = Path.of(request.localPath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(local)) {
            throw new IllegalArgumentException("localPath is not a readable file: " + local);
        }
        String assetId = request.fileAssetId().isBlank() ? PlatformIds.newId() : request.fileAssetId();
        synchronized (lock(pid, assetId)) {
            Path dir = assetDir(pid, assetId);
            try {
                Files.createDirectories(dir);
                long size = Files.size(local);
                AssetRecord record = new AssetRecord(
                        pid,
                        assetId,
                        request.sourceId(),
                        request.kind(),
                        request.originalFileName(),
                        size,
                        "",
                        FileAssetStatuses.COMPLETE,
                        MODE_LOCAL_LINK,
                        local.toString()
                );
                writeMeta(dir, record);
                UploadState state = new UploadState(
                        pid,
                        assetId,
                        assetId,
                        request.sourceId(),
                        request.kind(),
                        request.originalFileName(),
                        size,
                        size,
                        chunkSizeBytes,
                        totalChunks(size),
                        fullBitSet(totalChunks(size)),
                        "",
                        FileAssetStatuses.COMPLETE,
                        "Local path registered without copy",
                        MODE_LOCAL_LINK,
                        local.toString()
                );
                writeState(dir, state);
                return record.toDto();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to register local asset", e);
            }
        }
    }

    public Optional<FileAssetDto> getAsset(String projectId, String fileAssetId) {
        String pid = PlatformIds.requireId("projectId", projectId);
        String assetId = PlatformIds.requireId("fileAssetId", fileAssetId);
        return readMeta(assetDir(pid, assetId)).map(AssetRecord::toDto);
    }

    /**
     * Resolve a COMPLETE asset to a readable path for processing-core.
     * LOCAL_LINK returns the original path; MANAGED returns sandboxed content with original extension.
     */
    public Path resolveReadablePath(String projectId, String fileAssetId) {
        String pid = PlatformIds.requireId("projectId", projectId);
        String assetId = PlatformIds.requireId("fileAssetId", fileAssetId);
        AssetRecord record = readMeta(assetDir(pid, assetId))
                .orElseThrow(() -> new IllegalArgumentException("Unknown asset: " + assetId));
        if (!FileAssetStatuses.COMPLETE.equals(record.status())) {
            throw new IllegalStateException("Asset is not COMPLETE: " + assetId + " status=" + record.status());
        }
        Path resolved = Path.of(record.resolvedPath()).toAbsolutePath().normalize();
        if (MODE_MANAGED.equals(record.storageMode())) {
            Path expected = Path.of(record.resolvedPath()).toAbsolutePath().normalize();
            ensureUnderRoot(expected);
            if (!expected.getParent().equals(assetDir(pid, assetId))) {
                throw new IllegalStateException("Managed asset path escaped sandbox");
            }
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalStateException("Asset file missing: " + resolved);
        }
        return resolved;
    }

    private void fail(Path dir, UploadState state, String message) {
        UploadState failed = state.withStatus(
                FileAssetStatuses.FAILED,
                state.storageMode(),
                state.resolvedPath(),
                message
        );
        writeState(dir, failed);
        writeMeta(dir, AssetRecord.fromUpload(failed));
    }

    private Path assetDir(String projectId, String assetId) {
        Path dir = assetsRoot.resolve(projectId).resolve(assetId).normalize();
        ensureUnderRoot(dir);
        return dir;
    }

    private void ensureUnderRoot(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(assetsRoot)) {
            throw new IllegalArgumentException("Path escapes asset sandbox: " + path);
        }
    }

    private Object lock(String projectId, String assetId) {
        return locks.computeIfAbsent(projectId + "::" + assetId, k -> new Object());
    }

    private int totalChunks(long totalBytes) {
        if (totalBytes <= 0L) {
            return 0;
        }
        return (int) ((totalBytes + chunkSizeBytes - 1L) / chunkSizeBytes);
    }

    private static int chunkLength(long totalBytes, int chunkIndex, int chunkSize) {
        long offset = (long) chunkIndex * chunkSize;
        long remaining = totalBytes - offset;
        if (remaining <= 0L) {
            return 0;
        }
        return (int) Math.min(chunkSize, remaining);
    }

    private static long receivedBytes(BitSet received, long totalBytes, int chunkSize, int totalChunks) {
        long sum = 0L;
        for (int i = received.nextSetBit(0); i >= 0; i = received.nextSetBit(i + 1)) {
            sum += chunkLength(totalBytes, i, chunkSize);
        }
        return sum;
    }

    private static BitSet fullBitSet(int totalChunks) {
        BitSet bits = new BitSet(Math.max(1, totalChunks));
        bits.set(0, Math.max(0, totalChunks));
        return bits;
    }

    private static String normalizeSha(String sha) {
        return sha == null ? "" : sha.trim().toLowerCase(Locale.ROOT);
    }

    private static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file);
                 DigestInputStream din = new DigestInputStream(in, digest)) {
                din.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private Path contentPath(Path dir, String originalFileName) {
        return dir.resolve(contentFileName(originalFileName));
    }

    private static Path resolveContentPath(Path dir, UploadState state) {
        if (state.resolvedPath() != null && !state.resolvedPath().isBlank()) {
            return Path.of(state.resolvedPath());
        }
        return dir.resolve(contentFileName(state.originalFileName()));
    }

    /** Keep original extension so ServiceLoader readers (MF4/ASC/BLF) can discover the file. */
    static String contentFileName(String originalFileName) {
        String display = PlatformIds.displayFileName(originalFileName);
        int dot = display.lastIndexOf('.');
        if (dot > 0 && dot < display.length() - 1) {
            String ext = display.substring(dot).toLowerCase(Locale.ROOT);
            if (ext.matches("\\.[a-z0-9]{1,8}")) {
                return "content" + ext;
            }
        }
        return CONTENT_FILE_DEFAULT;
    }

    private void writeState(Path dir, UploadState state) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(STATE_FILE).toFile(), PersistState.from(state));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<UploadState> readState(Path dir) {
        Path file = dir.resolve(STATE_FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            PersistState persist = mapper.readValue(file.toFile(), PersistState.class);
            return Optional.of(persist.toState());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeMeta(Path dir, AssetRecord record) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(META_FILE).toFile(), record);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<AssetRecord> readMeta(Path dir) {
        Path file = dir.resolve(META_FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), AssetRecord.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static UploadSessionDto toSession(UploadState state) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = state.received().nextSetBit(0); i >= 0; i = state.received().nextSetBit(i + 1)) {
            indexes.add(i);
        }
        indexes.sort(Comparator.naturalOrder());
        return new UploadSessionDto(
                state.projectId(),
                state.uploadSessionId(),
                state.fileAssetId(),
                state.sourceId(),
                state.kind(),
                state.originalFileName(),
                state.totalBytes(),
                state.receivedBytes(),
                state.chunkSizeBytes(),
                state.totalChunks(),
                indexes,
                state.expectedSha256(),
                state.status(),
                state.message()
        );
    }

    private record UploadState(
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
            BitSet received,
            String expectedSha256,
            String status,
            String message,
            String storageMode,
            String resolvedPath
    ) {
        UploadState withReceived(BitSet next, long receivedBytes, String message) {
            return new UploadState(
                    projectId, uploadSessionId, fileAssetId, sourceId, kind, originalFileName,
                    totalBytes, receivedBytes, chunkSizeBytes, totalChunks, next, expectedSha256,
                    status, message, storageMode, resolvedPath
            );
        }

        UploadState withStatus(String status, String mode, String path, String message) {
            return new UploadState(
                    projectId, uploadSessionId, fileAssetId, sourceId, kind, originalFileName,
                    totalBytes, receivedBytes, chunkSizeBytes, totalChunks, received, expectedSha256,
                    status, message, mode, path
            );
        }

        UploadState withResolvedPath(String path) {
            return new UploadState(
                    projectId, uploadSessionId, fileAssetId, sourceId, kind, originalFileName,
                    totalBytes, receivedBytes, chunkSizeBytes, totalChunks, received, expectedSha256,
                    status, message, storageMode, path
            );
        }
    }

    private record PersistState(
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
            String message,
            String storageMode,
            String resolvedPath
    ) {
        static PersistState from(UploadState state) {
            List<Integer> indexes = new ArrayList<>();
            for (int i = state.received().nextSetBit(0); i >= 0; i = state.received().nextSetBit(i + 1)) {
                indexes.add(i);
            }
            return new PersistState(
                    state.projectId(),
                    state.uploadSessionId(),
                    state.fileAssetId(),
                    state.sourceId(),
                    state.kind(),
                    state.originalFileName(),
                    state.totalBytes(),
                    state.receivedBytes(),
                    state.chunkSizeBytes(),
                    state.totalChunks(),
                    indexes,
                    state.expectedSha256(),
                    state.status(),
                    state.message(),
                    state.storageMode(),
                    state.resolvedPath()
            );
        }

        UploadState toState() {
            BitSet bits = new BitSet(Math.max(1, totalChunks));
            if (receivedChunkIndexes != null) {
                for (Integer idx : receivedChunkIndexes) {
                    if (idx != null && idx >= 0) {
                        bits.set(idx);
                    }
                }
            }
            return new UploadState(
                    projectId, uploadSessionId, fileAssetId, sourceId, kind, originalFileName,
                    totalBytes, receivedBytes, chunkSizeBytes, totalChunks, bits, expectedSha256,
                    status, message, storageMode, resolvedPath
            );
        }
    }

    public record AssetRecord(
            String projectId,
            String fileAssetId,
            String sourceId,
            String kind,
            String originalFileName,
            long sizeBytes,
            String sha256,
            String status,
            String storageMode,
            String resolvedPath
    ) {
        static AssetRecord fromUpload(UploadState state) {
            return new AssetRecord(
                    state.projectId(),
                    state.fileAssetId(),
                    state.sourceId(),
                    state.kind(),
                    state.originalFileName(),
                    state.totalBytes(),
                    state.expectedSha256(),
                    state.status(),
                    state.storageMode(),
                    state.resolvedPath()
            );
        }

        FileAssetDto toDto() {
            return new FileAssetDto(
                    projectId, fileAssetId, sourceId, kind, originalFileName,
                    sizeBytes, sha256, status, storageMode
            );
        }

        UploadSessionDto toSession() {
            return new UploadSessionDto(
                    projectId, fileAssetId, fileAssetId, sourceId, kind, originalFileName,
                    sizeBytes, sizeBytes, 0, 0, List.of(), sha256, status, storageMode
            );
        }
    }
}
