package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.testsupport.DesktopTestIsolation;
import com.stellar.platform.storage.ProjectRootIndex;
import com.stellar.platform.storage.ProjectStoragePaths;
import com.stellar.platform.storage.metadata.ProcessingRunMetadata;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectDashboardAggregationTest {

    @TempDir Path temp;
    private DesktopAppService service;
    private String previousHome;
    private String previousDisableAuto;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
            service = null;
        }
        DesktopTestIsolation.resetProcessState();
        if (previousHome == null) {
            System.clearProperty("stellarai.home");
        } else {
            System.setProperty("stellarai.home", previousHome);
        }
        if (previousDisableAuto == null) {
            System.clearProperty("stellarai.analysis.storage.disable-auto");
        } else {
            System.setProperty("stellarai.analysis.storage.disable-auto", previousDisableAuto);
        }
    }

    @Test
    void landingAggregatesProjectLocalSummariesWithNoActiveProject() {
        isolateHome();
        service = new DesktopAppService(new DesktopSession());
        Path projectA = temp.resolve("ProjectA");
        Path projectB = temp.resolve("ProjectB");
        seedProject(projectA, "proj-a", "Alpha", 2, 1, 149);
        seedProject(projectB, "proj-b", "Beta", 1, 2, 80);

        assertTrue(service.activeProjectRoot().isEmpty(), "no active project");
        assertTrue(service.activeDatabasePath().isEmpty());

        List<ProjectDashboardSummary> rows = service.projectDashboardSummaries();
        assertEquals(2, rows.size());
        int measurementFiles = rows.stream().mapToInt(ProjectDashboardSummary::measurementFileCount).sum();
        int signals = rows.stream().mapToInt(ProjectDashboardSummary::signalCount).sum();
        assertEquals(3, measurementFiles);
        assertEquals(229, signals);
        assertTrue(rows.stream().allMatch(ProjectDashboardSummary::countsAvailable));
        assertNotEquals(row(rows, "proj-a").projectRoot(), row(rows, "proj-b").projectRoot());
    }

    @Test
    void summariesSurviveReopenAndStayIsolated() {
        isolateHome();
        service = new DesktopAppService(new DesktopSession());
        Path projectA = temp.resolve("ProjectA");
        Path projectB = temp.resolve("ProjectB");
        seedProject(projectA, "proj-a", "Alpha", 2, 1, 149);
        seedProject(projectB, "proj-b", "Beta", 1, 2, 80);

        assertEquals(229, signalTotal(service.projectDashboardSummaries()));
        service.shutdown();

        service = new DesktopAppService(new DesktopSession());
        List<ProjectDashboardSummary> reopened = service.projectDashboardSummaries();
        assertEquals(3, reopened.stream().mapToInt(ProjectDashboardSummary::measurementFileCount).sum());
        assertEquals(229, signalTotal(reopened));

        seedExtraMeasurement(projectA, "proj-a", "meas-extra");
        List<ProjectDashboardSummary> updated = service.projectDashboardSummaries();
        assertEquals(3, row(updated, "proj-a").measurementFileCount());
        assertEquals(1, row(updated, "proj-b").measurementFileCount());
        assertEquals(80, row(updated, "proj-b").signalCount());
    }

    @Test
    void missingDatabaseIsNotListedAsAProject() throws Exception {
        isolateHome();
        service = new DesktopAppService(new DesktopSession());
        Path missing = temp.resolve("Gone");
        Files.createDirectories(missing);
        ProjectRootIndex.register("proj-gone", missing);
        assertTrue(service.projectDashboardSummaries().stream().noneMatch(s -> "proj-gone".equals(s.projectId())));
        assertTrue(service.savedProjects().stream().noneMatch(p -> "proj-gone".equals(p.projectId())));
        assertFalse(ProjectRootIndex.entries().containsKey("proj-gone"));
    }

    @Test
    void processingRunAppearsOnDashboardRefreshWithoutOpeningProject() {
        isolateHome();
        service = new DesktopAppService(new DesktopSession());
        Path projectA = temp.resolve("ProjectA");
        seedProject(projectA, "proj-a", "Alpha", 2, 1, 149);
        assertTrue(row(service.projectDashboardSummaries(), "proj-a").lastProcessingStatus().isBlank());

        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(
                ProjectStoragePaths.of(projectA).databasePath())) {
            store.upsertProcessingRun(new ProcessingRunMetadata(
                    "run-1", "proj-a", "COMPLETED", false, 1, now, now, "ok"));
        }

        assertEquals("COMPLETED", row(service.projectDashboardSummaries(), "proj-a").lastProcessingStatus());
        assertTrue(service.activeProjectRoot().isEmpty());
    }

    @Test
    void zeroFileProjectIsARealZero() {
        isolateHome();
        service = new DesktopAppService(new DesktopSession());
        Path empty = temp.resolve("Empty");
        seedProject(empty, "proj-empty", "Empty", 0, 0, 0);
        ProjectDashboardSummary row = row(service.projectDashboardSummaries(), "proj-empty");
        assertEquals(ProjectSummaryAvailability.AVAILABLE, row.availability());
        assertTrue(row.countsAvailable());
        assertEquals(0, row.measurementFileCount());
        assertEquals(0, row.signalCount());
    }

    private void isolateHome() {
        previousHome = System.getProperty("stellarai.home");
        previousDisableAuto = System.getProperty("stellarai.analysis.storage.disable-auto");
        DesktopTestIsolation.isolateHome(temp.resolve("home"));
    }

    private static int signalTotal(List<ProjectDashboardSummary> rows) {
        return rows.stream().mapToInt(ProjectDashboardSummary::signalCount).sum();
    }

    private static ProjectDashboardSummary row(List<ProjectDashboardSummary> rows, String projectId) {
        return rows.stream().filter(s -> projectId.equals(s.projectId())).findFirst().orElseThrow();
    }

    private static void seedProject(Path projectRoot, String projectId, String name,
            int measurements, int definitions, int signals) {
        ProjectStoragePaths paths = ProjectStoragePaths.of(projectRoot);
        paths.ensureMetadataDirectory();
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(paths.databasePath())) {
            store.upsertProject(new ProjectMetadata(projectId, name, now, now));
            for (int i = 0; i < measurements; i++) {
                store.upsertFile(file(projectId, "meas-" + i, "MEASUREMENT_MF4"));
            }
            for (int i = 0; i < definitions; i++) {
                store.upsertFile(file(projectId, "def-" + i, "DEFINITION_DBC"));
            }
            List<SignalCatalogMetadata> catalog = new ArrayList<>();
            for (int i = 0; i < signals; i++) {
                catalog.add(new SignalCatalogMetadata(
                        null, projectId, "meas-0", 0, "def-0", "art-1",
                        "key-" + i, "Signal" + i, "MSG", 256, 0, "DBC_CAN", "rpm",
                        10, 0L, 1L, 0.0, 1.0, now, now));
            }
            if (signals > 0) {
                store.replaceSignalCatalog(projectId, "art-1", catalog);
            }
        }
        ProjectRootIndex.register(projectId, projectRoot);
    }

    private static void seedExtraMeasurement(Path projectRoot, String projectId, String fileId) {
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(
                ProjectStoragePaths.of(projectRoot).databasePath())) {
            store.upsertFile(file(projectId, fileId, "MEASUREMENT_MF4"));
        }
    }

    private static ProjectFileMetadata file(String projectId, String fileId, String kind) {
        return new ProjectFileMetadata(
                projectId, fileId, "/tmp/" + fileId + ".dat", kind, 10,
                "READY", "", null, "", false, false, false);
    }
}
