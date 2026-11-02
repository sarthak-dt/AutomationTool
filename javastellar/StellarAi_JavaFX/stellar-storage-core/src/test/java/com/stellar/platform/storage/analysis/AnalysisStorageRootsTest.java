package com.stellar.platform.storage.analysis;

import com.stellar.platform.storage.StellarAiPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisStorageRootsTest {

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
    void defaultManagedRootUsesAnalysisStorageWhenConfigured() {
        isolateHome();
        Path userRoot = temp.resolve("disk");
        AnalysisStorageRoots.setUserSelectedRoot(userRoot);
        Path expected = userRoot.resolve(AnalysisStorageRoots.CONTROLLED_DIR_NAME)
                .resolve("projects")
                .resolve("abc-1")
                .toAbsolutePath()
                .normalize();
        assertEquals(expected, AnalysisStorageRoots.defaultManagedProjectRoot("abc-1"));
        assertEquals(expected, AnalysisStorageRoots.projectArtifactsRoot("abc-1"));
        assertTrue(AnalysisStorageRoots.configuredProjectsDirectory().isPresent());
    }

    @Test
    void defaultManagedRootFallsBackToApplicationHomeWhenUnconfigured() {
        isolateHome();
        assertEquals(StellarAiPaths.projectRoot("abc-1"),
                AnalysisStorageRoots.defaultManagedProjectRoot("abc-1"));
        assertTrue(AnalysisStorageRoots.configuredProjectsDirectory().isEmpty());
    }

    private void isolateHome() {
        previousHome = System.getProperty("stellarai.home");
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
    }
}
