package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.storage.ProjectStoragePaths;
import com.stellar.platform.storage.StellarAiPaths;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.analysis.CentralSqlitePaths;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectLocalDatabaseTest {

    @TempDir Path temp;
    private DesktopAppService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
        System.clearProperty("stellarai.home");
        System.clearProperty("stellarai.metadata.db");
        AnalysisStorageRoots.clear();
    }

    @Test
    void untitledDoesNotCreateAPermanentExternalDatabase() {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        service = new DesktopAppService(new DesktopSession());
        assertTrue(service.activeDatabasePath().isEmpty());
        assertTrue(service.activeProjectRoot().isEmpty());
        Path globalDefault = temp.resolve("home").resolve("metadata").resolve("stellar.db");
        assertFalse(Files.exists(globalDefault), "unsaved project must not create a global DB");
        assertFalse(CentralSqlitePaths.databaseExists());
    }

    @Test
    void projectAAndBShareOneCentralDatabaseWithIsolatedIds() {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        service = new DesktopAppService(new DesktopSession());
        Path projectA = temp.resolve("ProjectA");
        Path projectB = temp.resolve("ProjectB");
        service.newProject("Alpha", projectA);
        Path dbA = service.activeDatabasePath().orElseThrow();
        assertEquals(CentralSqlitePaths.databasePath(), dbA);
        assertFalse(ProjectStoragePaths.of(projectA).databaseExists());
        String idA = service.currentProjectId();
        assertEquals("1001", idA);
        service.closeProject();

        service.newProject("Beta", projectB);
        Path dbB = service.activeDatabasePath().orElseThrow();
        assertEquals(dbA, dbB);
        assertFalse(ProjectStoragePaths.of(projectB).databaseExists());
        String idB = service.currentProjectId();
        assertEquals("1002", idB);
        assertNotEquals(idA, idB);
        service.closeProject();

        assertTrue(service.openSavedProject(idA).opened());
        assertEquals(dbA, service.activeDatabasePath().orElseThrow());
        service.closeProject();
        assertTrue(service.openSavedProject(idB).opened());
        assertEquals(dbB, service.activeDatabasePath().orElseThrow());
    }

    @Test
    void managedNewProjectUsesNumericArtifactFolderAndCentralDb() {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        service = new DesktopAppService(new DesktopSession());
        service.newProject("Managed");
        Path root = service.activeProjectRoot().orElseThrow();
        Path db = service.activeDatabasePath().orElseThrow();
        Path expectedRoot = AnalysisStorageRoots.projectArtifactsRoot(service.currentProjectId());
        assertEquals(expectedRoot, root);
        assertEquals("1001", service.currentProjectId());
        assertTrue(root.startsWith(AnalysisStorageRoots.requireControlledRoot()));
        assertEquals(CentralSqlitePaths.databasePath(), db);
        assertTrue(Files.isRegularFile(db));
        assertFalse(ProjectStoragePaths.of(root).databaseExists());
        assertFalse(Files.exists(StellarAiPaths.projectRoot(service.currentProjectId())
                .resolve(".stellar").resolve("stellar.db")));
    }

    @Test
    void openingLegacyGlobalProjectImportsIntoCentralDbAndLeavesLegacyUntouched() {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        String projectId = "legacy-project-a";
        Path legacy = SqliteProjectMetadataStore.legacyGlobalDatabasePath();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(legacy)) {
            store.upsertProject(new ProjectMetadata(projectId, "Legacy Alpha", Instant.now(), Instant.now()));
        }
        assertTrue(Files.isRegularFile(legacy));
        long legacySize = fileSize(legacy);

        service = new DesktopAppService(new DesktopSession());
        assertTrue(service.openSavedProject(projectId).opened());
        Path central = service.activeDatabasePath().orElseThrow();
        assertEquals(CentralSqlitePaths.databasePath(), central);
        assertNotEquals(legacy, central);
        assertTrue(Files.isRegularFile(legacy), "legacy global DB must not be deleted");
        assertEquals(legacySize, fileSize(legacy));

        service.closeProject();
        assertTrue(service.openSavedProject(projectId).opened());
        assertEquals(central, service.activeDatabasePath().orElseThrow());
    }

    @Test
    void existingHomeProjectImportsWithoutDeletingLocalLegacyDb() {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        String projectId = "home-era-project";
        Path homeRoot = StellarAiPaths.projectRoot(projectId);
        ProjectStoragePaths paths = ProjectStoragePaths.of(homeRoot);
        paths.ensureMetadataDirectory();
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(paths.databasePath())) {
            store.upsertProject(new ProjectMetadata(projectId, "Home Era", now, now));
        }

        service = new DesktopAppService(new DesktopSession());
        assertTrue(AnalysisStorageRoots.isConfigured());
        assertTrue(service.openSavedProject(projectId).opened());
        assertEquals(CentralSqlitePaths.databasePath(), service.activeDatabasePath().orElseThrow());
        assertTrue(Files.isRegularFile(paths.databasePath()), "legacy project-local DB is retained");
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
