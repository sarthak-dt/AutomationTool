package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.DesktopLogging;
import com.stellar.platform.desktop.presentation.DecisionWorkspaceMode;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ProcessingPageMode;
import com.stellar.platform.desktop.presentation.ProcessingWorkspacePhase;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.DesktopFixtures;
import com.stellar.platform.desktop.testsupport.DesktopTestIsolation;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.graph.LosslessGraphSignalStore;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DEF-001 restart contract on an isolated project. Does not open E2E project 1001
 * and does not depend on another test importing or removing {@code desktop_engine.asc}.
 */
class LiveDef001RestartTest {

    private static final String ASC_NAME = "desktop_engine.asc";

    @TempDir Path temp;
    private final List<DesktopAppService> services = new ArrayList<>();
    private String previousHome;
    private String previousDisableAuto;
    private boolean homesCaptured;

    @AfterEach
    void tearDown() {
        for (DesktopAppService service : services) {
            service.shutdown();
        }
        services.clear();
        DesktopLogging.closeForTests();
        DesktopTestIsolation.resetProcessState();
        restore("stellarai.home", previousHome);
        restore("stellarai.analysis.storage.disable-auto", previousDisableAuto);
    }

    @Test
    void isolatedRestartRestoresCatalogAndKeepsRemovedAscAbsent() throws Exception {
        Path storage = temp.resolve("Storage");
        DesktopAppService service = newService(storage);
        service.newProject("Project A");
        String projectId = service.currentProjectId();
        assertTrue(service.activeDatabasePath().orElseThrow().startsWith(
                storage.resolve(AnalysisStorageRoots.CONTROLLED_DIR_NAME)),
                "project database must live in this test's Analysis Storage");

        WorkspaceFile measurement = prepareMeasurement(service);
        WorkspaceFile asc = addAsc(service);
        assertTrue(containsAsc(service), "setup must import the ASC before removing it");
        assertTrue(service.removeFile(asc.id()));
        assertFalse(containsAsc(service), "the test must remove the ASC itself");

        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        service.enterProcessingWorkspaceFromDecision();
        assertEquals(1, service.processingSelectionModel().reviewedPlan().jobs().size());
        assertTrue(service.processingSelectionModel().reviewedPlan().jobs().stream()
                .noneMatch(job -> asc.id().equals(job.projectSourceId())));
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);

        int catalog = catalogSize(service);
        assertTrue(catalog > 0, "processing must publish a catalog before close");
        assertTrue(service.saveProject().saved());
        String runId = service.latestPersistedProcessingRun().orElseThrow().runId();
        Path store = AnalysisStorageRoots.signalStoreRoot(projectId);
        assertTrue(store.startsWith(storage.resolve(AnalysisStorageRoots.CONTROLLED_DIR_NAME)));
        long indexStamp = seriesIndexStamp(store);
        Path database = service.activeDatabasePath().orElseThrow();
        assertEquals(0, runningJobs(database));
        assertEquals(20, schemaVersion(database));
        assertFalse(containsAsc(service));
        assertTrue(names(service).toLowerCase().contains(measurement.fileName().toLowerCase()));

        service.processingResultRegistry().clear();
        service.signalsModel().clear();
        service.shutdown();
        services.remove(service);
        AnalysisStorageRoots.clear();

        DesktopAppService reopened = newService(storage);
        DesktopAppService.ProjectOpenResult opened = reopened.openSavedProject(projectId);
        assertTrue(opened.opened(), String.join("; ", opened.warnings()));
        assertRestart(reopened, projectId, catalog, runId, store, indexStamp);

        reopened.shutdown();
        services.remove(reopened);
        AnalysisStorageRoots.clear();

        DesktopAppService second = newService(storage);
        DesktopAppService.ProjectOpenResult openedAgain = second.openSavedProject(projectId);
        assertTrue(openedAgain.opened(), String.join("; ", openedAgain.warnings()));
        assertRestart(second, projectId, catalog, runId, store, indexStamp);
        assertEquals(runId, second.latestPersistedProcessingRun().orElseThrow().runId(),
                "a second fresh service must not create a processing run");
    }

    private void assertRestart(
            DesktopAppService service,
            String projectId,
            int catalog,
            String runId,
            Path store,
            long indexStamp
    ) throws Exception {
        assertEquals(projectId, service.currentProjectId());
        assertFalse(service.isPlanExecutionActive());
        assertEquals(catalog, catalogSize(service), String.join("; ", List.of()));
        assertEquals(runId, service.latestPersistedProcessingRun().orElseThrow().runId(),
                "reopen must not start a new processing run");
        assertFalse(containsAsc(service), "removed ASC must stay removed");
        assertFalse(names(service).toLowerCase().contains("desktop_engine"));
        assertEquals(ProcessingPageMode.EXECUTION_RESULT, service.processingSelectionModel().mode());
        assertEquals(ProcessingWorkspacePhase.COMPLETED,
                ProcessingWorkspacePhase.of(service.processingSelectionModel().mode(), false),
                "Reprocess Entire Project is offered only for a completed result");
        assertEquals(indexStamp, seriesIndexStamp(store), "reopen must reuse the finalized signal store");
        assertEquals(0, runningJobs(service.activeDatabasePath().orElseThrow()));
    }

    private DesktopAppService newService(Path selected) throws Exception {
        rememberHomes();
        System.setProperty("stellarai.home", temp.resolve("AppHome").toAbsolutePath().toString());
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        DesktopTestIsolation.resetProcessState();
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        services.add(service);
        if (service.analysisStorage().userSelectedRoot().isEmpty()) {
            Files.createDirectories(selected);
            assertTrue(service.saveAnalysisStorage(selected).valid(), "analysis storage must be configured");
        }
        assertEquals(selected.toAbsolutePath().normalize(), service.analysisStorage().userSelectedRoot().orElseThrow());
        assertNotEquals(Path.of("D:/StellarAI-E2E/Storage").toAbsolutePath().normalize(),
                service.analysisStorage().userSelectedRoot().orElseThrow());
        return service;
    }

    private WorkspaceFile prepareMeasurement(DesktopAppService service) throws Exception {
        Path dbcPath = DesktopFixtures.copyResource("dbc/desktop_engine.dbc", temp.resolve(unique("one.dbc")));
        WorkspaceFile dbc = new WorkspaceFile(dbcPath, FileKind.DEFINITION_DBC, Files.size(dbcPath));
        service.workspace().addFile(dbc);
        Path mf4 = DesktopFixtures.writeFinalizedMf4(temp.resolve(unique("one.mf4")));
        WorkspaceFile source = new WorkspaceFile(mf4, FileKind.MEASUREMENT_MF4, Files.size(mf4));
        service.workspace().addFile(source);
        CanSourceInventory inventory = new CanSourceInventory(
                mf4, source.fileName(), MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                1, 1, 1, true,
                List.of(new CanChannelInventory(
                        0, "Channel 0", 1, 1, 1, 0, 0,
                        List.of(new CanMessageInventory(new CanMessageKey(0, 0x100L, false), 1, 8, 8, false)))),
                List.of());
        service.sourceInventoryModel().put(source.id(), inventory);
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(
                service.activeDatabasePath().orElseThrow())) {
            store.saveInventory(service.currentProjectId(), source.id(), inventory);
        }
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(source.projectSourceId(), 0),
                ChannelMappingStatus.CONFIRMED,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
                dbc.definitionSourceId(), dbc.fileName(),
                dbc.definitionSourceId(), dbc.fileName(), 1, 1, 1.0));
        return source;
    }

    private WorkspaceFile addAsc(DesktopAppService service) throws Exception {
        Path ascPath = DesktopFixtures.copyResource("asc/desktop_engine.asc", temp.resolve(unique(ASC_NAME)));
        WorkspaceFile asc = new WorkspaceFile(ascPath, FileKind.MEASUREMENT_ASC, Files.size(ascPath));
        service.workspace().addFile(asc);
        return asc;
    }

    private void rememberHomes() {
        if (homesCaptured) {
            return;
        }
        homesCaptured = true;
        previousHome = System.getProperty("stellarai.home");
        previousDisableAuto = System.getProperty("stellarai.analysis.storage.disable-auto");
    }

    private static boolean containsAsc(DesktopAppService service) {
        return service.workspace().measurementFiles().stream()
                .anyMatch(file -> file.fileName() != null
                        && file.fileName().toLowerCase().contains("desktop_engine"));
    }

    private static String names(DesktopAppService service) {
        return service.workspace().measurementFiles().stream()
                .map(file -> file.fileName() == null ? "" : file.fileName())
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    private static int catalogSize(DesktopAppService service) {
        return service.signalsModel().catalog() == null ? 0 : service.signalsModel().catalog().size();
    }

    private static long seriesIndexStamp(Path store) throws Exception {
        assertTrue(Files.isDirectory(store), "signal-store missing: " + store);
        try (Stream<Path> walk = Files.walk(store)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return LosslessGraphSignalStore.INDEX_FILE_NAME.equals(name)
                                || LosslessGraphSignalStore.COMMITTED_INDEX_FILE_NAME.equals(name);
                    })
                    .mapToLong(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis();
                        } catch (Exception ex) {
                            return -1L;
                        }
                    })
                    .max()
                    .orElseThrow(() -> new AssertionError("signal-store has no finalized index"));
        }
    }

    private static int runningJobs(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection(sqliteUrl(database));
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM processing_jobs WHERE UPPER(status)='RUNNING'")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static int schemaVersion(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection(sqliteUrl(database));
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT MAX(version) FROM schema_info")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static String sqliteUrl(Path database) {
        return "jdbc:sqlite:" + database.toAbsolutePath().toString().replace('\\', '/');
    }

    private static String unique(String suffix) {
        return UUID.randomUUID().toString().substring(0, 8) + "-" + suffix;
    }

    private static void waitForIdle(DesktopAppService service) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (service.isPlanExecutionActive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(service.isPlanExecutionActive(), "processing run did not reach a terminal state");
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
