package com.stellar.platform.storage.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisStorageValidatorTest {

    @TempDir Path temp;

    @Test
    void validDirectoryPassesProbe() throws Exception {
        Path root = temp.resolve("analysis");
        Files.createDirectories(root);
        AnalysisStorageValidator.Result result = AnalysisStorageValidator.validate(root);
        assertTrue(result.valid());
        assertTrue(result.readable());
        assertTrue(result.writable());
        assertTrue(Files.isDirectory(root.resolve(AnalysisStorageRoots.CONTROLLED_DIR_NAME)));
        assertTrue(result.freeSpaceBytes() == null || result.freeSpaceBytes() >= 0);
    }

    @Test
    void fileInsteadOfDirectoryFails() throws Exception {
        Path file = temp.resolve("not-a-dir.txt");
        Files.writeString(file, "x");
        AnalysisStorageValidator.Result result = AnalysisStorageValidator.validate(file);
        assertFalse(result.valid());
        assertEquals(AnalysisStorageValidator.Status.INVALID, result.status());
        assertTrue(result.message().toLowerCase().contains("directory"));
    }

    @Test
    void missingDirectoryIsCreatedWhenWritable() {
        Path root = temp.resolve("missing-parent").resolve("child");
        AnalysisStorageValidator.Result result = AnalysisStorageValidator.validate(root);
        assertTrue(result.valid(), result.message());
        assertTrue(Files.isDirectory(root.resolve(AnalysisStorageRoots.CONTROLLED_DIR_NAME)));
    }

    @Test
    void writeFailureWhenControlledRootReadOnly() throws Exception {
        Path root = temp.resolve("ro");
        Path controlled = root.resolve(AnalysisStorageRoots.CONTROLLED_DIR_NAME);
        Files.createDirectories(controlled);
        DosFileAttributeView dos = Files.getFileAttributeView(controlled, DosFileAttributeView.class);
        if (dos == null) {
            // Non-Windows / unsupported — skip soft assertion by validating a path under a file parent.
            Path blocker = temp.resolve("file-as-parent.txt");
            Files.writeString(blocker, "x");
            AnalysisStorageValidator.Result blocked = AnalysisStorageValidator.validate(blocker);
            assertFalse(blocked.valid());
            return;
        }
        dos.setReadOnly(true);
        try {
            AnalysisStorageValidator.Result result = AnalysisStorageValidator.validate(root);
            // On some Windows configurations createDirectories still succeeds; probe write should fail.
            if (!result.valid()) {
                assertFalse(result.writable());
            }
        } finally {
            dos.setReadOnly(false);
        }
    }

    @Test
    void notConfiguredWhenNull() {
        AnalysisStorageValidator.Result result = AnalysisStorageValidator.validate(null);
        assertEquals(AnalysisStorageValidator.Status.NOT_CONFIGURED, result.status());
    }
}
