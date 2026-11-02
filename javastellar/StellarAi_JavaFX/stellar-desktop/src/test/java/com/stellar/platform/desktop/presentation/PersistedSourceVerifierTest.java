package com.stellar.platform.desktop.presentation;

import com.stellar.platform.storage.metadata.FileFingerprint;
import com.stellar.platform.storage.metadata.ProcessingArtifactMetadata;
import com.stellar.platform.storage.metadata.ProjectFileMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SavedProjectSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistedSourceVerifierTest {

    @TempDir Path temp;

    @Test
    void matchingFileIsARealVerifiedZeroWarningSet() throws Exception {
        Path source = temp.resolve("keep.mf4");
        Files.writeString(source, "abc");
        SavedProjectSnapshot snapshot = snapshot(source, Files.size(source), FileFingerprint.of(source));
        PersistedSourceVerifier.Result result = PersistedSourceVerifier.verify(snapshot);
        assertTrue(result.allSourcesMatch());
        assertEquals(1, result.verifiedFiles());
        assertEquals(0, result.unavailableFiles());
    }

    @Test
    void missingFileIsUnavailableNotAFakeMatch() throws Exception {
        Path source = temp.resolve("gone.mf4");
        Files.writeString(source, "abc");
        SavedProjectSnapshot snapshot = snapshot(source, Files.size(source), FileFingerprint.of(source));
        Files.delete(source);
        PersistedSourceVerifier.Result result = PersistedSourceVerifier.verify(snapshot);
        assertFalse(result.allSourcesMatch());
        assertEquals(1, result.unavailableFiles());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("no longer available")));
    }

    @Test
    void sizeChangeIsReported() throws Exception {
        Path source = temp.resolve("changed.mf4");
        Files.writeString(source, "abc");
        SavedProjectSnapshot snapshot = snapshot(source, 3, FileFingerprint.of(source));
        Files.writeString(source, "abcdef");
        PersistedSourceVerifier.Result result = PersistedSourceVerifier.verify(snapshot);
        assertFalse(result.allSourcesMatch());
        assertTrue(result.changedFiles() >= 1);
    }

    private static SavedProjectSnapshot snapshot(Path source, long size, String fingerprint) {
        Instant now = Instant.now();
        return new SavedProjectSnapshot(
                new ProjectMetadata("p1", "P", now, now),
                List.of(new ProjectFileMetadata(
                        "p1", "s1", source.toString(), "MEASUREMENT_MF4", size,
                        "READY", "", null, "", false, true, false)),
                Map.of(),
                List.of(),
                List.of(),
                List.of(new ProcessingArtifactMetadata(
                        "a1", "p1", "s1", 1, "d1", "run",
                        "SIGNAL_CATALOG_COUNTS", "", "counts://p1/s1/1/d1",
                        "1", fingerprint, "", 1, 1, 0L, 1L, "VALID", now, "CONFIRMED"))
        );
    }
}
