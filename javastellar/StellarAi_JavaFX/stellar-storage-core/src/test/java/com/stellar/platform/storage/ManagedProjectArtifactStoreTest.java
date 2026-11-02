package com.stellar.platform.storage;

import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedProjectArtifactStoreTest {

    @TempDir Path temp;
    private String previousHome;

    @AfterEach
    void tearDown() {
        AnalysisStorageRoots.clear();
        if (previousHome == null) {
            System.clearProperty("stellarai.home");
        } else {
            System.setProperty("stellarai.home", previousHome);
        }
    }

    @Test
    void deleteRemovesAnalysisStorageAndHomeProjectFolders() throws Exception {
        previousHome = System.getProperty("stellarai.home");
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        Path userRoot = temp.resolve("analysis");
        AnalysisStorageRoots.setUserSelectedRoot(userRoot);
        AnalysisStorageRoots.ensureControlledDirectory();

        String projectId = "del-1";
        Path analysisRoot = AnalysisStorageRoots.projectArtifactsRoot(projectId);
        Path homeRoot = StellarAiPaths.projectRoot(projectId);
        Files.createDirectories(analysisRoot.resolve(".stellar"));
        Files.writeString(analysisRoot.resolve(".stellar").resolve("stellar.db"), "db");
        Files.createDirectories(homeRoot);
        Files.writeString(homeRoot.resolve("leftover.bin"), "old");

        ManagedProjectArtifactStore store = new ManagedProjectArtifactStore();
        assertTrue(store.isManagedProjectPath(analysisRoot));
        assertTrue(store.deleteProjectArtifacts(projectId).removed());
        assertFalse(Files.exists(analysisRoot));
        assertFalse(Files.exists(homeRoot));
    }
}
