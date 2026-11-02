package com.stellar.platform.storage;

import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.ProjectDashboardSummary;
import com.stellar.platform.storage.metadata.ProjectFileMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.ProjectSummaryAvailability;
import com.stellar.platform.storage.metadata.SignalCatalogMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectWorkspaceCatalogTest {

    @TempDir Path temp;
    private String previousHome;

    @AfterEach
    void tearDown() {
        if (previousHome == null) {
            System.clearProperty("stellarai.home");
        } else {
            System.setProperty("stellarai.home", previousHome);
        }
        System.clearProperty("stellarai.metadata.db");
        AnalysisStorageRoots.clear();
    }

    @Test
    void aggregatesIndependentProjectLocalDatabasesWithoutSharingState() throws Exception {
        isolateHome();
        Path projectA = temp.resolve("ProjectA");
        Path projectB = temp.resolve("ProjectB");
        seedProject(projectA, "proj-a", "Alpha", 2, 1, 149);
        seedProject(projectB, "proj-b", "Beta", 1, 2, 80);
        ProjectRootIndex.register("proj-a", projectA);
        ProjectRootIndex.register("proj-b", projectB);

        List<ProjectDashboardSummary> rows = ProjectWorkspaceCatalog.listManagedDashboardSummaries();
        assertEquals(2, rows.size());
        ProjectDashboardSummary a = row(rows, "proj-a");
        ProjectDashboardSummary b = row(rows, "proj-b");
        assertEquals(ProjectSummaryAvailability.AVAILABLE, a.availability());
        assertEquals(2, a.measurementFileCount());
        assertEquals(1, a.definitionFileCount());
        assertEquals(149, a.signalCount());
        assertTrue(a.projectRoot().startsWith(projectA.toAbsolutePath().normalize().toString()));
        assertEquals(ProjectStoragePaths.of(projectA).databasePath().toString(),
                Path.of(a.projectRoot()).resolve(".stellar").resolve("stellar.db").toString());
        assertEquals(1, b.measurementFileCount());
        assertEquals(2, b.definitionFileCount());
        assertEquals(80, b.signalCount());
        assertFalse(a.projectRoot().equals(b.projectRoot()));

        seedExtraMeasurement(projectA, "proj-a", "src-extra");
        List<ProjectDashboardSummary> refreshed = ProjectWorkspaceCatalog.listManagedDashboardSummaries();
        assertEquals(3, row(refreshed, "proj-a").measurementFileCount());
        assertEquals(1, row(refreshed, "proj-b").measurementFileCount());
        assertEquals(80, row(refreshed, "proj-b").signalCount());
    }

    @Test
    void orphanManagedFolderWithoutDatabaseIsNotListed() throws Exception {
        isolateHome();
        Path orphan = ProjectWorkspaceCatalog.managedProjectsDirectory().resolve("orphan-folder");
        Files.createDirectories(orphan);
        Path real = temp.resolve("Real");
        seedProject(real, "proj-real", "Real", 1, 0, 3);
        ProjectRootIndex.register("proj-real", real);

        List<ProjectDashboardSummary> rows = ProjectWorkspaceCatalog.listManagedDashboardSummaries();
        assertTrue(rows.stream().noneMatch(s -> "orphan-folder".equals(s.projectId())));
        assertEquals(1, rows.size());
        assertEquals("proj-real", rows.get(0).projectId());
    }

    @Test
    void missingAndInvalidDatabasesAreNotListedAsProjects() throws Exception {
        isolateHome();
        Path missing = temp.resolve("MissingProject");
        Files.createDirectories(missing);
        ProjectRootIndex.register("proj-missing", missing);

        Path corrupt = temp.resolve("CorruptProject");
        Path corruptDb = ProjectStoragePaths.of(corrupt).databasePath();
        Files.createDirectories(corruptDb.getParent());
        Files.writeString(corruptDb, "this is not a sqlite database");
        ProjectRootIndex.register("proj-corrupt", corrupt);

        Path empty = temp.resolve("EmptyProject");
        seedProject(empty, "proj-empty", "Empty", 0, 0, 0);
        ProjectRootIndex.register("proj-empty", empty);

        List<ProjectDashboardSummary> rows = ProjectWorkspaceCatalog.listManagedDashboardSummaries();
        assertTrue(rows.stream().noneMatch(s -> "proj-missing".equals(s.projectId())));
        assertTrue(rows.stream().noneMatch(s -> "proj-corrupt".equals(s.projectId())));
        assertFalse(ProjectRootIndex.entries().containsKey("proj-missing"));
        assertFalse(ProjectRootIndex.entries().containsKey("proj-corrupt"));

        ProjectDashboardSummary emptyRow = row(rows, "proj-empty");
        assertEquals(ProjectSummaryAvailability.AVAILABLE, emptyRow.availability());
        assertEquals(0, emptyRow.measurementFileCount());
        assertEquals(0, emptyRow.signalCount());
        assertTrue(emptyRow.countsAvailable());
        assertEquals("Empty", emptyRow.name());
    }

    @Test
    void artifactUuidDirectoryWithoutProjectMetadataIsNotAProject() throws Exception {
        isolateHome();
        Path userRoot = temp.resolve("analysis-root");
        AnalysisStorageRoots.setUserSelectedRoot(userRoot);
        AnalysisStorageRoots.ensureControlledDirectory();
        String ghostId = "e7f51e67-1111-2222-3333-444444444444";
        Path artifactOnly = AnalysisStorageRoots.projectArtifactsRoot(ghostId).resolve("artifacts");
        Files.createDirectories(artifactOnly);
        Files.writeString(artifactOnly.resolve("placeholder.bin"), "not-a-project");

        Path real = temp.resolve("Real");
        seedProject(real, "proj-real", "Real", 1, 0, 3);
        ProjectRootIndex.register("proj-real", real);

        List<ProjectDashboardSummary> rows = ProjectWorkspaceCatalog.listManagedDashboardSummaries();
        assertTrue(rows.stream().noneMatch(s -> ghostId.equals(s.projectId())));
        assertTrue(rows.stream().noneMatch(s -> ghostId.equals(s.name())));
        assertEquals(1, rows.size());
        assertEquals("proj-real", rows.get(0).projectId());
        assertEquals("Real", rows.get(0).name());
    }

    @Test
    void dashboardSignalCountUsesUniqueSignalKeysNotCatalogRows() throws Exception {
        isolateHome();
        Path project = temp.resolve("DupKeys");
        seedProject(project, "proj-dup", "Dup", 2, 0, 3);
        Instant now = Instant.now();
        ProjectStoragePaths paths = ProjectStoragePaths.of(project);
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(paths.databasePath())) {
            List<SignalCatalogMetadata> rows = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                rows.add(new SignalCatalogMetadata(
                        null, "proj-dup", "meas-1", 0, "def-0", "art-2",
                        "key-" + i, "Signal" + i, "MSG", 256, 0, "DBC_CAN", "rpm",
                        10, 0L, 1L, 0.0, 1.0, now, now));
            }
            store.replaceSignalCatalog("proj-dup", "art-2", rows);
        }
        ProjectRootIndex.register("proj-dup", project);

        List<ProjectDashboardSummary> rows = ProjectWorkspaceCatalog.listManagedDashboardSummaries();
        assertEquals(3, row(rows, "proj-dup").signalCount(),
                "Workspace/project signal counts must be unique indexed keys, not per-source catalog rows");
    }

    @Test
    void leftoverDatabaseWithoutProjectsRowIsNotListedAsUuidProject() throws Exception {
        isolateHome();
        String ghostId = "f3a63fcb-aaaa-bbbb-cccc-dddddddddddd";
        Path leftover = ProjectWorkspaceCatalog.managedProjectsDirectory().resolve(ghostId);
        ProjectStoragePaths paths = ProjectStoragePaths.of(leftover);
        paths.ensureMetadataDirectory();
        try (SqliteProjectMetadataStore ignored = new SqliteProjectMetadataStore(paths.databasePath())) {
            // Schema only — no projects row, as after an incomplete delete.
        }
        ProjectRootIndex.register(ghostId, leftover);

        List<ProjectDashboardSummary> rows = ProjectWorkspaceCatalog.listManagedDashboardSummaries();
        assertTrue(rows.stream().noneMatch(s -> ghostId.equals(s.projectId())));
        assertTrue(rows.stream().noneMatch(s -> ghostId.equals(s.name())));
        assertFalse(ProjectRootIndex.entries().containsKey(ghostId));
    }

    @Test
    void discoversProjectDatabaseUnderConfiguredAnalysisStorage() throws Exception {
        isolateHome();
        Path userRoot = temp.resolve("analysis-root");
        AnalysisStorageRoots.setUserSelectedRoot(userRoot);
        AnalysisStorageRoots.ensureControlledDirectory();
        Path projectRoot = AnalysisStorageRoots.projectArtifactsRoot("cfg-proj");
        seedProject(projectRoot, "cfg-proj", "Configured", 2, 0, 7);

        List<ProjectDashboardSummary> rows = ProjectWorkspaceCatalog.listManagedDashboardSummaries();
        ProjectDashboardSummary row = row(rows, "cfg-proj");
        assertEquals(ProjectSummaryAvailability.AVAILABLE, row.availability());
        assertEquals(2, row.measurementFileCount());
        assertEquals(7, row.signalCount());
        assertTrue(row.projectRoot().startsWith(projectRoot.toAbsolutePath().normalize().toString()));

        assertTrue(ProjectWorkspaceCatalog.locateManagedProject("cfg-proj").isPresent());
        assertEquals(projectRoot.toAbsolutePath().normalize(),
                ProjectWorkspaceCatalog.locateManagedProject("cfg-proj").orElseThrow()
                        .projectRoot().toAbsolutePath().normalize());
    }

    @Test
    void legacyGlobalProjectAppearsWhenNoLocalDatabaseExists() throws Exception {
        isolateHome();
        Path legacy = SqliteProjectMetadataStore.legacyGlobalDatabasePath();
        Files.createDirectories(legacy.getParent());
        seedProjectDatabase(legacy, "legacy-1", "Legacy Road", 3, 1, 42);

        List<ProjectDashboardSummary> rows = ProjectWorkspaceCatalog.listManagedDashboardSummaries();
        ProjectDashboardSummary legacyRow = row(rows, "legacy-1");
        assertEquals(ProjectSummaryAvailability.LEGACY, legacyRow.availability());
        assertEquals(3, legacyRow.measurementFileCount());
        assertEquals(42, legacyRow.signalCount());
        assertTrue(legacyRow.countsAvailable());
    }

    private void isolateHome() {
        previousHome = System.getProperty("stellarai.home");
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.clearProperty("stellarai.metadata.db");
    }

    private static ProjectDashboardSummary row(List<ProjectDashboardSummary> rows, String projectId) {
        return rows.stream().filter(s -> projectId.equals(s.projectId())).findFirst().orElseThrow();
    }

    private static void seedProject(Path projectRoot, String projectId, String name,
            int measurements, int definitions, int signals) {
        ProjectStoragePaths paths = ProjectStoragePaths.of(projectRoot);
        paths.ensureMetadataDirectory();
        seedProjectDatabase(paths.databasePath(), projectId, name, measurements, definitions, signals);
    }

    private static void seedExtraMeasurement(Path projectRoot, String projectId, String fileId) {
        ProjectStoragePaths paths = ProjectStoragePaths.of(projectRoot);
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(paths.databasePath())) {
            store.upsertFile(file(projectId, fileId, "MEASUREMENT_MF4"));
        }
    }

    private static void seedProjectDatabase(
            Path database, String projectId, String name, int measurements, int definitions, int signals
    ) {
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(database)) {
            store.upsertProject(new ProjectMetadata(projectId, name, now, now));
            for (int i = 0; i < measurements; i++) {
                store.upsertFile(file(projectId, "meas-" + i, "MEASUREMENT_MF4"));
            }
            for (int i = 0; i < definitions; i++) {
                store.upsertFile(file(projectId, "def-" + i, "DEFINITION_DBC"));
            }
            List<SignalCatalogMetadata> rows = new ArrayList<>();
            for (int i = 0; i < signals; i++) {
                rows.add(new SignalCatalogMetadata(
                        null, projectId, "meas-0", 0, "def-0", "art-1",
                        "key-" + i, "Signal" + i, "MSG", 256, 0, "DBC_CAN", "rpm",
                        10, 0L, 1L, 0.0, 1.0, now, now));
            }
            if (signals > 0) {
                store.replaceSignalCatalog(projectId, "art-1", rows);
            }
        }
    }

    private static ProjectFileMetadata file(String projectId, String fileId, String kind) {
        return new ProjectFileMetadata(
                projectId, fileId, "/tmp/" + fileId + ".dat", kind, 10,
                "READY", "", null, "", false, false, false);
    }
}
