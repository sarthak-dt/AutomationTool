package com.stellar.platform.api.assets;

import com.stellar.platform.api.dto.BeginUploadRequest;
import com.stellar.platform.api.dto.FileAssetDto;
import com.stellar.platform.api.dto.RegisterLocalAssetRequest;
import com.stellar.platform.api.dto.UploadSessionDto;
import com.stellar.platform.api.identity.FileAssetKinds;
import com.stellar.platform.api.identity.FileAssetStatuses;
import com.stellar.platform.api.identity.PlatformIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileAssetStoreTest {

    @TempDir
    Path temp;

    private FileAssetStore store;
    private String projectA;
    private String projectB;

    @BeforeEach
    void setUp() {
        store = new FileAssetStore(temp, 8);
        projectA = PlatformIds.newId();
        projectB = PlatformIds.newId();
    }

    @Test
    void smallUploadCompletesWithChecksum() throws Exception {
        byte[] payload = "hello-stellar".getBytes(StandardCharsets.UTF_8);
        String sha = sha(payload);
        UploadSessionDto session = store.beginUpload(projectA, new BeginUploadRequest(
                "src-1", FileAssetKinds.MEASUREMENT, "hello.mf4", payload.length, sha));
        putAll(session, payload);
        FileAssetDto asset = store.complete(projectA, session.uploadSessionId());
        assertEquals(FileAssetStatuses.COMPLETE, asset.status());
        assertEquals(sha, asset.sha256());
        assertEquals(payload.length, Files.size(store.resolveReadablePath(projectA, asset.fileAssetId())));
    }

    @Test
    void multipleChunksAndResume() throws Exception {
        byte[] payload = "0123456789ABCDEFGHIJ".getBytes(StandardCharsets.UTF_8);
        String sha = sha(payload);
        UploadSessionDto session = store.beginUpload(projectA, new BeginUploadRequest(
                "src-1", FileAssetKinds.MEASUREMENT, "big.bin", payload.length, sha));
        int chunkSize = session.chunkSizeBytes();
        store.putChunk(projectA, session.uploadSessionId(), 0, slice(payload, 0, chunkSize));
        UploadSessionDto mid = store.status(projectA, session.uploadSessionId());
        assertTrue(mid.receivedBytes() > 0);
        assertTrue(mid.receivedBytes() < payload.length);

        FileAssetStore resumed = new FileAssetStore(temp, 8);
        UploadSessionDto status = resumed.status(projectA, session.uploadSessionId());
        for (int i = 0; i < status.totalChunks(); i++) {
            if (!status.receivedChunkIndexes().contains(i)) {
                int from = i * chunkSize;
                int len = Math.min(chunkSize, payload.length - from);
                resumed.putChunk(projectA, session.uploadSessionId(), i, slice(payload, from, len));
            }
        }
        FileAssetDto asset = resumed.complete(projectA, session.uploadSessionId());
        assertEquals(FileAssetStatuses.COMPLETE, asset.status());
        assertEquals(sha, asset.sha256());
    }

    @Test
    void duplicateChunkRetryIsIdempotent() throws Exception {
        byte[] payload = "dup-chunk-data!".getBytes(StandardCharsets.UTF_8);
        UploadSessionDto session = store.beginUpload(projectA, new BeginUploadRequest(
                "src-1", FileAssetKinds.MEASUREMENT, "dup.bin", payload.length, sha(payload)));
        byte[] first = slice(payload, 0, session.chunkSizeBytes());
        store.putChunk(projectA, session.uploadSessionId(), 0, first);
        UploadSessionDto again = store.putChunk(projectA, session.uploadSessionId(), 0, first);
        assertEquals(1, again.receivedChunkIndexes().stream().filter(i -> i == 0).count());
        putRemaining(session, payload);
        assertEquals(FileAssetStatuses.COMPLETE,
                store.complete(projectA, session.uploadSessionId()).status());
    }

    @Test
    void checksumFailureNeverCreatesValidAsset() throws Exception {
        byte[] payload = "bad-checksum".getBytes(StandardCharsets.UTF_8);
        UploadSessionDto session = store.beginUpload(projectA, new BeginUploadRequest(
                "src-1", FileAssetKinds.MEASUREMENT, "bad.bin", payload.length, "0".repeat(64)));
        putAll(session, payload);
        assertThrows(IllegalStateException.class, () -> store.complete(projectA, session.uploadSessionId()));
        UploadSessionDto status = store.status(projectA, session.uploadSessionId());
        assertEquals(FileAssetStatuses.FAILED, status.status());
        assertTrue(store.getAsset(projectA, session.fileAssetId()).isPresent());
        assertFalse(FileAssetStatuses.COMPLETE.equals(
                store.getAsset(projectA, session.fileAssetId()).orElseThrow().status()));
    }

    @Test
    void cancelUnknownAndTraversal() {
        assertThrows(IllegalArgumentException.class,
                () -> store.status(projectA, PlatformIds.newId()));
        assertThrows(IllegalArgumentException.class,
                () -> store.beginUpload(projectA, new BeginUploadRequest(
                        "../evil", FileAssetKinds.MEASUREMENT, "x.bin", 1, "")));
        assertThrows(IllegalArgumentException.class,
                () -> store.beginUpload("..", new BeginUploadRequest(
                        "src", FileAssetKinds.MEASUREMENT, "x.bin", 1, "")));
    }

    @Test
    void oversizedUploadRejected() {
        FileAssetStore limited = new FileAssetStore(temp.resolve("limited"), 8, 16);
        assertThrows(IllegalArgumentException.class,
                () -> limited.beginUpload(projectA, new BeginUploadRequest(
                        "src-1", FileAssetKinds.MEASUREMENT, "big.bin", 32, "")));
    }

    @Test
    void cancelUpload() throws Exception {
        byte[] payload = "cancel-me".getBytes(StandardCharsets.UTF_8);
        UploadSessionDto session = store.beginUpload(projectA, new BeginUploadRequest(
                "src-1", FileAssetKinds.MEASUREMENT, "c.bin", payload.length, sha(payload)));
        store.putChunk(projectA, session.uploadSessionId(), 0,
                slice(payload, 0, Math.min(session.chunkSizeBytes(), payload.length)));
        UploadSessionDto cancelled = store.cancel(projectA, session.uploadSessionId());
        assertEquals(FileAssetStatuses.CANCELLED, cancelled.status());
    }

    @Test
    void twoProjectsSameFilenameAreIsolated() throws Exception {
        byte[] aBytes = "project-a-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = "project-b-BYTES!!".getBytes(StandardCharsets.UTF_8);
        UploadSessionDto a = store.beginUpload(projectA, new BeginUploadRequest(
                "src", FileAssetKinds.MEASUREMENT, "same.mf4", aBytes.length, sha(aBytes)));
        UploadSessionDto b = store.beginUpload(projectB, new BeginUploadRequest(
                "src", FileAssetKinds.MEASUREMENT, "same.mf4", bBytes.length, sha(bBytes)));
        putAll(a, aBytes);
        putAll(b, bBytes);
        FileAssetDto assetA = store.complete(projectA, a.uploadSessionId());
        FileAssetDto assetB = store.complete(projectB, b.uploadSessionId());
        assertEquals("same.mf4", assetA.originalFileName());
        assertEquals("same.mf4", assetB.originalFileName());
        assertFalse(assetA.fileAssetId().equals(assetB.fileAssetId()));
        assertEquals(new String(aBytes, StandardCharsets.UTF_8),
                Files.readString(store.resolveReadablePath(projectA, assetA.fileAssetId())));
        assertEquals(new String(bBytes, StandardCharsets.UTF_8),
                Files.readString(store.resolveReadablePath(projectB, assetB.fileAssetId())));
    }

    @Test
    void localRegisterDoesNotCopy() throws Exception {
        Path local = temp.resolve("inputs").resolve("vehicle.mf4");
        Files.createDirectories(local.getParent());
        Files.writeString(local, "local-link-content");
        FileAssetDto asset = store.registerLocal(projectA, new RegisterLocalAssetRequest(
                "src-local", FileAssetKinds.MEASUREMENT, local.toString()));
        assertEquals(FileAssetStatuses.COMPLETE, asset.status());
        assertEquals(FileAssetStore.MODE_LOCAL_LINK, asset.storageMode());
        assertEquals(local.toAbsolutePath().normalize(),
                store.resolveReadablePath(projectA, asset.fileAssetId()));
        assertFalse(Files.exists(temp.resolve("assets").resolve(projectA).resolve(asset.fileAssetId()).resolve("content.bin")));
        assertFalse(Files.exists(temp.resolve("assets").resolve(projectA).resolve(asset.fileAssetId()).resolve("content.mf4")));
    }

    private void putAll(UploadSessionDto session, byte[] payload) {
        int chunkSize = session.chunkSizeBytes();
        for (int i = 0; i < session.totalChunks(); i++) {
            int from = i * chunkSize;
            int len = Math.min(chunkSize, payload.length - from);
            store.putChunk(projectA.equals(session.projectId()) ? projectA : session.projectId(),
                    session.uploadSessionId(), i, slice(payload, from, len));
        }
    }

    private void putRemaining(UploadSessionDto session, byte[] payload) {
        UploadSessionDto status = store.status(session.projectId(), session.uploadSessionId());
        int chunkSize = status.chunkSizeBytes();
        for (int i = 0; i < status.totalChunks(); i++) {
            if (status.receivedChunkIndexes().contains(i)) {
                continue;
            }
            int from = i * chunkSize;
            int len = Math.min(chunkSize, payload.length - from);
            store.putChunk(session.projectId(), session.uploadSessionId(), i, slice(payload, from, len));
        }
    }

    private static byte[] slice(byte[] payload, int from, int len) {
        byte[] out = new byte[len];
        System.arraycopy(payload, from, out, 0, len);
        return out;
    }

    private static String sha(byte[] payload) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    }
}
