package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.DesktopLogging;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.DesktopFixtures;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.processing.graph.LosslessGraphSignalStore;
import com.stellar.platform.storage.ArtifactStorageRefs;
import com.stellar.platform.storage.StellarAiPaths;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.FileFingerprint;
import com.stellar.platform.storage.metadata.ProcessingArtifactMetadata;
import com.stellar.platform.storage.metadata.ProcessingPlanFingerprint;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A processed project must reopen from Analysis Storage artifacts on a new service graph.
 * Same-session catalog state is cleared before the second graph is constructed.
 */
class ProcessedArtifactRestartPersistenceTest {

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
        AnalysisStorageRoots.clear();
        restore("stellarai.home", previousHome);
        restore("stellarai.analysis.storage.disable-auto", previousDisableAuto);
    }

    @Test
    void processedProjectReopensFromAnalysisStorageOnANewServiceGraph() throws Exception {
        Path selected = temp.resolve("Storage");
        DesktopAppService service = newService(selected);
        service.newProject("Project A");
        prepareOneRealSource(service);
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);

        int catalog = catalogSize(service);
        assertTrue(catalog > 0, "processing must publish a catalog before restart");
        assertEquals(ProcessingPageMode.EXECUTION_RESULT, service.processingSelectionModel().mode());
        String projectId = service.currentProjectId();
        String runId = service.latestPersistedProcessingRun().orElseThrow().runId();
        Path store = AnalysisStorageRoots.signalStoreRoot(projectId);
        assertTrue(store.startsWith(selected.resolve(AnalysisStorageRoots.CONTROLLED_DIR_NAME)));
        assertFalse(store.startsWith(StellarAiPaths.homeRoot().resolve("projects")));
        assertFalse(underLocalAppDataProjects(store));
        List<Path> files = regularFiles(store);
        assertTrue(files.stream().anyMatch(ProcessedArtifactRestartPersistenceTest::isSeriesIndex),
                "series index must be finalized before the run is complete");
        assertTrue(files.stream().anyMatch(path -> path.getFileName().toString().endsWith(".bin")
                && fileSize(path) > 0), "segment payloads must be closed and nonzero");
        long indexStamp = files.stream()
                .filter(ProcessedArtifactRestartPersistenceTest::isSeriesIndex)
                .mapToLong(ProcessedArtifactRestartPersistenceTest::mtime)
                .max()
                .orElseThrow();

        Path legacy = StellarAiPaths.signalStoreRoot(projectId).resolve("stale.bin");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "legacy-cache");

        assertTrue(service.saveProject().saved());
        service.processingResultRegistry().clear();
        service.signalsModel().clear();
        service.shutdown();
        services.remove(service);
        AnalysisStorageRoots.clear();

        DesktopAppService reopened = newService(selected);
        DesktopAppService.ProjectOpenResult opened = reopened.openSavedProject(projectId);
        assertTrue(opened.opened(), String.join("; ", opened.warnings()));
        assertEquals(catalog, catalogSize(reopened), String.join("; ", opened.warnings()));
        assertEquals(runId, reopened.latestPersistedProcessingRun().orElseThrow().runId());
        assertEquals(0, reopened.signalStoreCreateCountForTest.get());
        assertFalse(reopened.isPlanExecutionActive());
        assertEquals(ProcessingPageMode.EXECUTION_RESULT, reopened.processingSelectionModel().mode());
        assertEquals(ProcessingWorkspacePhase.COMPLETED,
                ProcessingWorkspacePhase.of(reopened.processingSelectionModel().mode(), false));
        assertTrue(Files.isDirectory(store));
        assertEquals(indexStamp, regularFiles(store).stream()
                .filter(ProcessedArtifactRestartPersistenceTest::isSeriesIndex)
                .mapToLong(ProcessedArtifactRestartPersistenceTest::mtime)
                .max()
                .orElseThrow(), "reopen must not rewrite the series index");
        assertFalse(Files.exists(legacy), "legacy application-home cache is disposable");
        assertTrue(store.startsWith(selected.resolve("StellarAIAnalysis").resolve("projects").resolve(projectId)
                .resolve("signal-store")));

        reopened.shutdown();
        services.remove(reopened);
        AnalysisStorageRoots.clear();
        DesktopAppService second = newService(selected);
        DesktopAppService.ProjectOpenResult openedAgain = second.openSavedProject(projectId);
        assertTrue(openedAgain.opened(), String.join("; ", openedAgain.warnings()));
        assertEquals(catalog, catalogSize(second), "a second reopen must still load the persisted catalog");
        assertEquals(ProcessingPageMode.EXECUTION_RESULT, second.processingSelectionModel().mode());
    }

    @Test
    void freshServiceRejectsMissingSegmentAndDoesNotShowProcessingComplete() throws Exception {
        Path selected = temp.resolve("Storage");
        DesktopAppService service = newService(selected);
        service.newProject("Project A");
        prepareOneRealSource(service);
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);
        String projectId = service.currentProjectId();
        String runId = service.latestPersistedProcessingRun().orElseThrow().runId();
        Path store = AnalysisStorageRoots.signalStoreRoot(projectId);
        Path segment = regularFiles(store).stream()
                .filter(path -> path.getFileName().toString().endsWith(".bin"))
                .findFirst()
                .orElseThrow();
        assertTrue(service.saveProject().saved());
        service.shutdown();
        services.remove(service);
        AnalysisStorageRoots.clear();
        Files.delete(segment);

        DesktopAppService reopened = newService(selected);
        DesktopAppService.ProjectOpenResult opened = reopened.openSavedProject(projectId);
        assertTrue(opened.opened(), String.join("; ", opened.warnings()));
        assertEquals(0, catalogSize(reopened));
        assertNotEquals(ProcessingPageMode.EXECUTION_RESULT, reopened.processingSelectionModel().mode());
        assertNotEquals(ProcessingWorkspacePhase.COMPLETED,
                ProcessingWorkspacePhase.of(reopened.processingSelectionModel().mode(), false));
        assertEquals(runId, reopened.latestPersistedProcessingRun().orElseThrow().runId());
        assertEquals(0, reopened.signalStoreCreateCountForTest.get());
        assertFalse(reopened.isPlanExecutionActive());
    }

    @Test
    void unprocessedProjectDoesNotShowProcessingComplete() throws Exception {
        Path selected = temp.resolve("Storage");
        DesktopAppService service = newService(selected);
        service.newProject("Not Processed");
        assertTrue(service.saveProject().saved());
        String projectId = service.currentProjectId();
        service.shutdown();
        services.remove(service);
        AnalysisStorageRoots.clear();

        DesktopAppService reopened = newService(selected);
        DesktopAppService.ProjectOpenResult opened = reopened.openSavedProject(projectId);
        assertTrue(opened.opened(), String.join("; ", opened.warnings()));
        assertEquals(0, catalogSize(reopened));
        assertNotEquals(ProcessingPageMode.EXECUTION_RESULT, reopened.processingSelectionModel().mode());
        assertNotEquals(ProcessingWorkspacePhase.COMPLETED,
                ProcessingWorkspacePhase.of(reopened.processingSelectionModel().mode(), false));
    }

    @Test
    void missingIndexIncompleteFlagAndWrongRootAreRejected() throws Exception {
        Path selected = temp.resolve("Storage");
        rememberHomes();
        System.setProperty("stellarai.home", temp.resolve("AppHome").toAbsolutePath().toString());
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        Files.createDirectories(selected);
        AnalysisStorageRoots.setUserSelectedRoot(selected);
        AnalysisStorageRoots.ensureControlledDirectory();

        Path root = ArtifactStorageRefs.jobRoot("p1", "src", 0, "def");
        Path source = temp.resolve("m.mf4");
        Path definition = temp.resolve("d.dbc");
        Files.writeString(source, "mf4");
        Files.writeString(definition, "VERSION \"\"");
        try (GraphSignalSessionStore write = GraphSignalSessionStore.createPersistent(root, "m.mf4", "d.dbc")) {
            write.accept(List.of(new CanonicalSignal(
                    "EngineSpeed", "Engine", 1_000L, 0x100L, 0, 1.0, null, "rpm", null,
                    SourceReference.ofLine(1))));
            write.finishWriteSession();
        }
        String plan = ProcessingPlanFingerprint.forJob("src", 0, "def", "CONFIRMED", "MF4");
        ProcessingArtifactMetadata artifact = artifact(root, source, definition, plan);
        assertTrue(DurableArtifactLifecycle.validateOnDisk(artifact, root, source, definition, plan).reusable());

        Path index = regularFiles(root).stream()
                .filter(ProcessedArtifactRestartPersistenceTest::isSeriesIndex)
                .findFirst()
                .orElseThrow();
        byte[] indexBytes = Files.readAllBytes(index);
        Files.delete(index);
        DurableArtifactLifecycle.Check missingIndex =
                DurableArtifactLifecycle.validateOnDisk(artifact, root, source, definition, plan);
        assertFalse(missingIndex.reusable());
        assertEquals(DurableArtifactLifecycle.Outcome.MISSING, missingIndex.outcome());
        assertTrue(missingIndex.warning().contains("missing or incomplete"));
        Files.write(index, indexBytes);

        Path segment = regularFiles(root).stream()
                .filter(path -> path.getFileName().toString().endsWith(".bin"))
                .findFirst()
                .orElseThrow();
        byte[] segmentBytes = Files.readAllBytes(segment);
        Files.delete(segment);
        DurableArtifactLifecycle.Check missingSegment =
                DurableArtifactLifecycle.validateOnDisk(artifact, root, source, definition, plan);
        assertFalse(missingSegment.reusable());
        assertEquals(DurableArtifactLifecycle.Outcome.STALE, missingSegment.outcome());
        assertTrue(missingSegment.warning().contains("payloads missing"));
        Files.write(segment, segmentBytes);

        Path flag = root.resolve(GraphSignalSessionStore.INCOMPLETE_FLAG);
        Files.writeString(flag, "1");
        DurableArtifactLifecycle.Check incomplete =
                DurableArtifactLifecycle.validateOnDisk(artifact, root, source, definition, plan);
        assertFalse(incomplete.reusable());
        assertEquals(DurableArtifactLifecycle.Outcome.MISSING, incomplete.outcome());
        Files.delete(flag);

        Path other = ArtifactStorageRefs.jobRoot("other-project", "src", 0, "def");
        assertFalse(other.startsWith(AnalysisStorageRoots.signalStoreRoot("p1")));
        DurableArtifactLifecycle.Check wrongRoot =
                DurableArtifactLifecycle.validateOnDisk(artifact, other, source, definition, plan);
        assertFalse(wrongRoot.reusable());
        assertEquals(DurableArtifactLifecycle.Outcome.MISSING, wrongRoot.outcome());
    }

    @Test
    void e2eSignalStoreResolvesUnderConfiguredAnalysisStorage() {
        rememberHomes();
        System.setProperty("stellarai.home", temp.resolve("AppHome").toAbsolutePath().toString());
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        assertThrows(IllegalStateException.class,
                () -> AnalysisStorageRoots.setUserSelectedRoot(Path.of("D:/StellarAI-E2E/Storage")));
        Path selected = Path.of("D:/StellarAI-FormulaCheck").toAbsolutePath().normalize();
        AnalysisStorageRoots.setUserSelectedRoot(selected);
        Path store = AnalysisStorageRoots.signalStoreRoot("1001");
        Path expected = selected.resolve("StellarAIAnalysis").resolve("projects").resolve("1001")
                .resolve("signal-store");
        assertEquals(expected, store);
        Path job = ArtifactStorageRefs.jobRoot(
                "1001", "0a2aa474-b550-4691-aa2e-fc48890fc2c5", 1,
                "1918bbd4-9b58-4070-8efc-4904cd79e64e");
        assertTrue(job.startsWith(expected));
        assertFalse(store.startsWith(StellarAiPaths.homeRoot().resolve("projects")));
        assertFalse(underLocalAppDataProjects(store));
        assertFalse(store.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()));
    }

    private DesktopAppService newService(Path selected) throws Exception {
        rememberHomes();
        System.setProperty("stellarai.home", temp.resolve("AppHome").toAbsolutePath().toString());
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        services.add(service);
        if (service.analysisStorage().userSelectedRoot().isEmpty()) {
            assertTrue(service.saveAnalysisStorage(selected).valid(), "analysis storage must be configured");
        }
        Path root = service.analysisStorage().userSelectedRoot().orElseThrow();
        assertEquals(selected.toAbsolutePath().normalize(), root);
        return service;
    }

    private void prepareOneRealSource(DesktopAppService service) throws Exception {
        WorkspaceFile dbc = addDefinition(service, unique("one.dbc"));
        addRealSource(service, unique("one.mf4"), dbc);
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        service.enterProcessingWorkspaceFromDecision();
        assertEquals(1, service.processingSelectionModel().reviewedPlan().jobs().size());
    }

    private WorkspaceFile addDefinition(DesktopAppService service, String name) throws Exception {
        Path dbcPath = DesktopFixtures.copyResource("dbc/desktop_engine.dbc", temp.resolve(name));
        WorkspaceFile dbc = new WorkspaceFile(dbcPath, FileKind.DEFINITION_DBC, Files.size(dbcPath));
        service.workspace().addFile(dbc);
        return dbc;
    }

    private WorkspaceFile addRealSource(DesktopAppService service, String name, WorkspaceFile dbc) throws Exception {
        Path mf4 = DesktopFixtures.writeFinalizedMf4(temp.resolve(name));
        WorkspaceFile source = new WorkspaceFile(mf4, FileKind.MEASUREMENT_MF4, Files.size(mf4));
        service.workspace().addFile(source);
        CanSourceInventory inventory = new CanSourceInventory(
                mf4, name, MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
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

    private void rememberHomes() {
        if (homesCaptured) {
            return;
        }
        homesCaptured = true;
        previousHome = System.getProperty("stellarai.home");
        previousDisableAuto = System.getProperty("stellarai.analysis.storage.disable-auto");
    }

    private static ProcessingArtifactMetadata artifact(
            Path root, Path source, Path definition, String plan) throws Exception {
        return new ProcessingArtifactMetadata(
                "p1|src|0|def",
                "p1",
                "src",
                0,
                "def",
                "run-1",
                ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                "",
                ArtifactStorageRefs.forJob("p1", "src", 0, "def"),
                ProcessingArtifactMetadata.ARTIFACT_VERSION_CURRENT,
                FileFingerprint.of(source),
                FileFingerprint.of(definition),
                plan,
                1, 1, 0L, 1L,
                ProcessingArtifactMetadata.STATUS_VALID,
                Instant.now(),
                "CONFIRMED",
                "SUCCESS",
                "",
                "");
    }

    private static int catalogSize(DesktopAppService service) {
        return service.signalsModel().catalog() == null
                ? 0
                : service.signalsModel().catalog().entries().size();
    }

    private static List<Path> regularFiles(Path root) throws Exception {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    private static boolean isSeriesIndex(Path path) {
        String name = path.getFileName().toString();
        return LosslessGraphSignalStore.INDEX_FILE_NAME.equals(name)
                || LosslessGraphSignalStore.COMMITTED_INDEX_FILE_NAME.equals(name);
    }

    private static boolean underLocalAppDataProjects(Path path) {
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) {
            return false;
        }
        Path projects = Path.of(local, "StellarAI", "projects").toAbsolutePath().normalize();
        return path.toAbsolutePath().normalize().startsWith(projects);
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception ex) {
            return -1;
        }
    }

    private static long mtime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ex) {
            return -1;
        }
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
