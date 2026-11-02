package com.stellar.platform.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectStoragePathsTest {

    @TempDir Path temp;

    @Test
    void databasePathIsInsideProjectRoot() {
        Path projectA = temp.resolve("ProjectA");
        ProjectStoragePaths paths = ProjectStoragePaths.of(projectA);
        assertEquals(projectA.toAbsolutePath().normalize(), paths.projectRoot());
        assertEquals(projectA.toAbsolutePath().normalize().resolve(".stellar"), paths.metadataDirectory());
        assertEquals(projectA.toAbsolutePath().normalize().resolve(".stellar").resolve("stellar.db"),
                paths.databasePath());
        assertTrue(paths.isLocatedUnderProjectRoot(paths.databasePath()));
        assertFalse(paths.databaseExists());
        paths.ensureMetadataDirectory();
        assertTrue(Files.isDirectory(paths.metadataDirectory()));
    }

    @Test
    void independentProjectsDoNotShareADatabase() {
        ProjectStoragePaths a = ProjectStoragePaths.of(temp.resolve("ProjectA"));
        ProjectStoragePaths b = ProjectStoragePaths.of(temp.resolve("ProjectB"));
        assertFalse(a.databasePath().equals(b.databasePath()));
        assertTrue(a.databasePath().startsWith(a.projectRoot()));
        assertTrue(b.databasePath().startsWith(b.projectRoot()));
    }
}
