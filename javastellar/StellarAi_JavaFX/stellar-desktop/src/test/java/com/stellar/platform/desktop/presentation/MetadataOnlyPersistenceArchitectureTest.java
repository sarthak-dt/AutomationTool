package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.storage.StellarAiPaths;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.analysis.CentralSqlitePaths;
import com.stellar.platform.storage.metadata.ProjectDashboardSummary;
import com.stellar.platform.storage.metadata.SignalCatalogMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saved projects persist paths and counts in the project DB. Heavy LOSSLESS artifacts
 * live under configured Analysis Storage (not under the project-local signal-store path).
 */
class MetadataOnlyPersistenceArchitectureTest {

    @TempDir Path temp;
    private DesktopAppService service;
    private String previousHome;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
        if (previousHome == null) {
            System.clearProperty("stellarai.home");
        } else {
            System.setProperty("stellarai.home", previousHome);
        }
        System.clearProperty("stellarai.metadata.db");
        AnalysisStorageRoots.clear();
    }

    @Test
    void saveAndReopenKeepsPathsAndCountsWithoutSignalStore() throws Exception {
        isolateHome();
        service = new DesktopAppService(new DesktopSession());
        service.newProject("Light Cache");
        Path measurement = temp.resolve("drive.mf4");
        Files.writeString(measurement, "mf4-bytes");
        WorkspaceFile added = service.workspace().addFile(
                new WorkspaceFile(measurement.toAbsolutePath(), FileKind.MEASUREMENT_MF4, Files.size(measurement)));
        assertTrue(service.saveProject().saved());
        String projectId = service.currentProjectId();
        seedCatalogCounts(projectId, added.id());

        assertFalse(Files.exists(StellarAiPaths.signalStoreRoot(projectId)));
        service.closeProject();
        assertFalse(Files.exists(StellarAiPaths.signalStoreRoot(projectId)));

        assertTrue(service.openSavedProject(projectId).opened());
        assertEquals(measurement.toAbsolutePath().normalize(),
                service.workspace().measurementFiles().getFirst().path());
        assertTrue(service.signalsModel().catalog().entries().isEmpty());
        assertTrue(service.processingResultRegistry().all().isEmpty());
        assertFalse(Files.exists(StellarAiPaths.signalStoreRoot(projectId)));

        List<ProjectDashboardSummary> rows = service.projectDashboardSummaries();
        ProjectDashboardSummary row = rows.stream()
                .filter(s -> projectId.equals(s.projectId()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, row.measurementFileCount());
        assertEquals(3, row.signalCount());
        assertTrue(row.countsAvailable());
    }

    @Test
    void leftoverSignalStoreIsRemovedOnOpen() throws Exception {
        isolateHome();
        service = new DesktopAppService(new DesktopSession());
        service.newProject("Cleanup");
        assertTrue(service.saveProject().saved());
        String projectId = service.currentProjectId();
        Path leftover = StellarAiPaths.signalStoreRoot(projectId).resolve("stale.bin");
        Files.createDirectories(leftover.getParent());
        Files.writeString(leftover, "decoded-cache");
        service.closeProject();

        assertTrue(service.openSavedProject(projectId).opened());
        assertFalse(Files.exists(StellarAiPaths.signalStoreRoot(projectId)));
    }

    private void isolateHome() {
        previousHome = System.getProperty("stellarai.home");
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.clearProperty("stellarai.metadata.db");
    }

    private static void seedCatalogCounts(String projectId, String sourceId) {
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(
                CentralSqlitePaths.databasePath())) {
            store.replaceSignalCatalog(projectId, "art-1", List.of(
                    catalogRow(projectId, sourceId, "s0", now),
                    catalogRow(projectId, sourceId, "s1", now),
                    catalogRow(projectId, sourceId, "s2", now)
            ));
        }
    }

    private static SignalCatalogMetadata catalogRow(String projectId, String sourceId, String key, Instant now) {
        return new SignalCatalogMetadata(
                null, projectId, sourceId, 0, "def-0", "art-1",
                key, "Signal" + key, "MSG", 256, 0, "DBC_CAN", "rpm",
                10, 0L, 1L, 0.0, 1.0, now, now);
    }
}
