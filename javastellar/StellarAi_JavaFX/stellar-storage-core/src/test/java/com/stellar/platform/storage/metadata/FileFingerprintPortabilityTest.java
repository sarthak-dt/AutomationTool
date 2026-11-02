package com.stellar.platform.storage.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileFingerprintPortabilityTest {

    @TempDir Path tempDir;

    @Test
    void v2FingerprintDoesNotEmbedAbsolutePathAndMatchesAcrossSeparatorStyles() throws Exception {
        Path file = tempDir.resolve("DriveData").resolve("run.mf4");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "abc");

        String fingerprint = FileFingerprint.of(file);
        assertTrue(fingerprint.startsWith("v=2;"));
        assertFalse(fingerprint.contains(";path="));
        assertFalse(fingerprint.contains(file.getParent().toString()));
        assertTrue(FileFingerprint.matches(fingerprint, file));

        // Legacy v1 path-bound fingerprint still validates via size+mtime when path differs.
        var attrs = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
        String legacy = "v=1;size=" + attrs.size()
                + ";mtime=" + attrs.lastModifiedTime().toMillis()
                + ";path=C:\\Users\\ADMIN\\elsewhere\\run.mf4";
        assertTrue(FileFingerprint.isLegacyPathBound(legacy));
        assertTrue(FileFingerprint.matches(legacy, file));
    }
}
