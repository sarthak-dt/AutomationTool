package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.StorageCoreCompatibility;
import com.stellar.platform.desktop.project.FileValidationStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.storage.metadata.DuplicateProjectNameException;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadataStore;
import com.stellar.platform.storage.metadata.ProjectNameNormalizer;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 CREATE / OPEN / RESUME lifecycle — repository rows, activation, coordinator isolation.
 */
class ProjectCreateOpenLifecycleTest {

    @TempDir Path tempDir;
    private DesktopAppService service;
    private SqliteProjectMetadataStore store;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private static com.stellar.platform.domain.inventory.CanSourceInventory readyMf4Inventory(Path path) {
        com.stellar.platform.domain.inventory.CanMessageInventory message =
                new com.stellar.platform.domain.inventory.CanMessageInventory(
                        new com.stellar.platform.domain.inventory.CanMessageKey(1, 0x100L, false),
                        10, 8, 8, false);
        com.stellar.platform.domain.inventory.CanChannelInventory channel =
                new com.stellar.platform.domain.inventory.CanChannelInventory(
                        1, "Channel 1", 10, 1, 1, 0, 0, List.of(message));
        String name = path.getFileName() == null ? "setup.mf4" : path.getFileName().toString();
        return new com.stellar.platform.domain.inventory.CanSourceInventory(
                path, name, com.stellar.platform.domain.MeasurementSourceFormat.MF4,
                com.stellar.platform.domain.inventory.CanInventoryStatus.INVENTORY_READY,
                1, 10, 1, true, List.of(channel), List.of());
    }

    private void openService() {
        System.setProperty("stellarai.home", tempDir.resolve("home").toString());
        Path db = tempDir.resolve("meta.db");
        System.setProperty("stellarai.metadata.db", db.toString());
        store = new SqliteProjectMetadataStore(db);
        service = new DesktopAppService(new DesktopSession(), store);
    }

    @Test
    void newProjectImmediatelyBecomesActive() {
        openService();
        service.newProject("My Project");
        assertTrue(service.isDurableNamedProject());
        assertEquals("My Project", service.workspace().projectName());
        assertTrue(store.loadProject(service.currentProjectId()).isPresent());
        assertEquals("My Project", store.loadProject(service.currentProjectId()).orElseThrow().project().name());
    }

    @Test
    void validUniqueProjectNameIsAccepted() {
        openService();
        service.newProject("  Trimmed Name  ");
        assertEquals("Trimmed Name", service.workspace().projectName());
        assertEquals("trimmed name", ProjectNameNormalizer.normalized(service.workspace().projectName()));
        assertEquals(1, service.savedProjects().size());
    }

    @Test
    void duplicateNormalizedNameIsRejectedAndLeavesNoOrphan() {
        openService();
        service.newProject("My Project");
        String first = service.currentProjectId();
        assertThrows(DuplicateProjectNameException.class, () -> service.newProject(" my project "));
        assertEquals(first, service.currentProjectId());
        assertEquals(1, service.savedProjects().size());
    }

    @Test
    void newProjectInitializesSaveCoordinatorForNewProject() {
        openService();
        assertTrue(service.saveCoordinatorForTest().boundProjectId() == null);
        service.newProject("Coordinator New");
        assertEquals(service.currentProjectId(), service.saveCoordinatorForTest().boundProjectId());
        assertEquals(ProjectSaveState.CLEAN, service.projectSaveState());
        assertFalse(service.saveCoordinatorForTest().hasPendingAutosave());
    }

    @Test
    void creatingProjectInitializesCoordinatorCleanly() {
        newProjectInitializesSaveCoordinatorForNewProject();
    }

    @Test
    void openingProjectInitializesCoordinatorCleanly() {
        openService();
        service.newProject("Open Coord");
        String id = service.currentProjectId();
        service.closeProject();
        assertTrue(service.saveCoordinatorForTest().boundProjectId() == null);
        assertTrue(service.openSavedProject(id).opened());
        assertEquals(id, service.saveCoordinatorForTest().boundProjectId());
        assertEquals(ProjectSaveState.CLEAN, service.projectSaveState());
    }

    @Test
    void openSavedProjectMakesRequestedProjectActive() {
        openService();
        service.newProject("Alpha");
        String alpha = service.currentProjectId();
        service.newProject("Beta");
        String beta = service.currentProjectId();
        assertTrue(service.openSavedProject(alpha).opened());
        assertEquals(alpha, service.currentProjectId());
        assertEquals("Alpha", service.workspace().projectName());
        assertNotEquals(beta, service.currentProjectId());
    }

    @Test
    void openSavedProjectHydratesBeforeResumeResolution() {
        openService();
        service.newProject("Hydrate First");
        String id = service.currentProjectId();
        service.closeProject();
        assertFalse(service.isDurableNamedProject());
        assertTrue(service.openSavedProject(id).opened());
        assertTrue(service.isDurableNamedProject());
        assertEquals(id, service.currentProjectId());
        assertNotEquals(WorkspaceRoute.PROJECTS_HOME, service.resolveResumeRoute());
    }

    @Test
    void openSavedEmptyProjectRoutesSources() {
        openService();
        service.newProject("Empty Saved");
        String id = service.currentProjectId();
        service.closeProject();
        assertTrue(service.openSavedProject(id).opened());
        assertEquals(WorkspaceRoute.SOURCES, service.resolveResumeRoute());
    }

    @Test
    void openSavedDiscoveredMeasurementRoutesReview() throws Exception {
        System.setProperty("stellarai.home", tempDir.resolve("home-decode").toString());
        Path db = tempDir.resolve("decode.db");
        System.setProperty("stellarai.metadata.db", db.toString());
        store = new SqliteProjectMetadataStore(db);
        service = new DesktopAppService(new DesktopSession(), (sourceId, path, ctx, progress) -> {
            com.stellar.platform.domain.inventory.CanSourceInventory inventory = readyMf4Inventory(path);
            progress.onInventoryUpdated(inventory);
            return inventory;
        }, store);
        service.newProject("Setup Saved");
        Path source = tempDir.resolve("setup.mf4");
        com.stellar.platform.desktop.support.TestMeasurementFixtures.validMinimalMf4(source);
        service.addMeasurementPaths(List.of(source), DesktopAppService.PlatformUi.NOOP);
        WorkspaceFile added = service.workspace().measurementFiles().getFirst();
        assertEquals(com.stellar.platform.desktop.project.FileKind.MEASUREMENT_MF4, added.kind());
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && !service.canContinueToDecode()) {
            Thread.sleep(20);
        }
        assertTrue(service.canContinueToDecode(), "a discovered MF4 source is ready for Decode");
        String id = service.currentProjectId();
        service.saveProject();
        service.closeProject();
        assertTrue(service.openSavedProject(id).opened());
        assertEquals(id, service.currentProjectId());
        assertEquals(WorkspaceRoute.REVIEW_AND_PROCESS, service.resolveResumeRoute(),
                "a discovered MF4 source with no DBC resumes on Review, where native output can still be processed");
    }

    @Test
    void openSavedAnalysisProjectRoutesSignalsOrGraphs() {
        openService();
        service.newProject("Analysis Saved");
        service.signalsModel().replaceCatalog(catalog());
        WorkspaceRoute route = service.resolveResumeRoute();
        assertTrue(route == WorkspaceRoute.SIGNALS || route == WorkspaceRoute.GRAPHS, route.name());
    }

    @Test
    void openSavedProjectDoesNotReparseMeasurementSource() throws Exception {
        openService();
        service.newProject("No Reparse");
        Path source = tempDir.resolve("keep.mf4");
        com.stellar.platform.desktop.support.TestMeasurementFixtures.validMinimalMf4(source);
        service.addMeasurementPaths(List.of(source), DesktopAppService.PlatformUi.NOOP);
        String id = service.currentProjectId();
        service.saveProject();
        service.closeProject();
        assertTrue(service.openSavedProject(id).opened());
        assertEquals(0, service.processingResultRegistry().all().size());
        assertEquals(source.toAbsolutePath().normalize(),
                service.workspace().measurementFiles().getFirst().path());
    }

    @Test
    void failedOpenDoesNotDeleteSavedProject() {
        openService();
        service.newProject("Keep On Fail");
        String id = service.currentProjectId();
        service.closeProject();
        service.openHydrationFailureForTest = new IllegalStateException("hydration exploded");
        DesktopAppService.ProjectOpenResult opened = service.openSavedProject(id);
        assertFalse(opened.opened());
        assertTrue(store.loadProject(id).isPresent());
        assertTrue(service.savedProjects().stream().anyMatch(p -> p.projectId().equals(id)));
        assertFalse(service.isDurableNamedProject());
    }

    @Test
    void deleteAfterFailedOpenStillRequiresExplicitUserDelete() {
        failedOpenDoesNotDeleteSavedProject();
        String id = service.savedProjects().getFirst().projectId();
        assertTrue(store.loadProject(id).isPresent());
        assertTrue(service.deleteProject(id).deleted());
        assertTrue(store.loadProject(id).isEmpty());
    }

    @Test
    void deleteProjectRequiresExplicitAction() {
        openService();
        service.newProject("Explicit Delete");
        String id = service.currentProjectId();
        service.closeProject();
        assertTrue(store.loadProject(id).isPresent());
        assertFalse(service.isDurableNamedProject());
    }

    @Test
    void deleteProjectRemovesInternalProject() {
        openService();
        service.newProject("Remove Me");
        String id = service.currentProjectId();
        assertTrue(service.deleteProject(id).deleted());
        assertTrue(service.savedProjects().stream().noneMatch(p -> p.projectId().equals(id)));
        assertTrue(store.loadProject(id).isEmpty());
    }

    @Test
    void deleteProjectDoesNotDeleteExternalSources() throws Exception {
        openService();
        Path source = tempDir.resolve("external.mf4");
        Files.writeString(source, "keep-me");
        service.newProject("External Source");
        String id = service.currentProjectId();
        service.addMeasurementPaths(List.of(source), DesktopAppService.PlatformUi.NOOP);
        assertTrue(service.deleteProject(id).deleted());
        assertTrue(Files.exists(source));
        assertEquals("keep-me", Files.readString(source));
    }

    @Test
    void coordinatorSupportsNoActiveProjectState() {
        openService();
        assertFalse(service.isDurableNamedProject());
        assertEquals(ProjectSaveState.CLEAN, service.projectSaveState());
        service.noteLogicalChange();
        assertEquals(ProjectSaveState.CLEAN, service.projectSaveState());
        assertFalse(service.hasUnsavedChanges());
    }

    @Test
    void saveFailureFromPreviousProjectDoesNotPoisonNewProject() {
        openService();
        service.newProject("Failed First");
        service.saveCoordinatorForTest().markFailed(new RuntimeException("disk unavailable"));
        assertEquals(ProjectSaveState.SAVE_FAILED, service.projectSaveState());
        service.newProject("Second Project");
        assertEquals(ProjectSaveState.CLEAN, service.projectSaveState());
        assertTrue(service.lastSaveError() == null || service.lastSaveError().isBlank());
        assertEquals("Second Project", service.workspace().projectName());
    }

    @Test
    void oldProjectAutosaveCannotRunAgainstNewProject() throws Exception {
        openService();
        service.newProject("Project A");
        String first = service.currentProjectId();
        service.noteLogicalChange();
        service.newProject("Project B");
        String second = service.currentProjectId();
        Thread.sleep(900);
        assertEquals(second, service.currentProjectId());
        assertEquals("Project B", store.loadProject(second).orElseThrow().project().name());
        assertEquals("Project A", store.loadProject(first).orElseThrow().project().name());
        assertEquals(ProjectSaveState.CLEAN, service.projectSaveState());
    }

    @Test
    void projectResetCancelsOldDebouncedSave() {
        openService();
        service.newProject("Reset A");
        service.noteLogicalChange();
        assertTrue(service.saveCoordinatorForTest().hasPendingAutosave()
                || service.projectSaveState() == ProjectSaveState.DIRTY);
        service.closeProject();
        assertFalse(service.saveCoordinatorForTest().hasPendingAutosave());
        assertEquals(ProjectSaveState.CLEAN, service.projectSaveState());
    }

    @Test
    void creationFailureIsVisibleThroughException() throws Exception {
        Path db = tempDir.resolve("fail-create.db");
        System.setProperty("stellarai.home", tempDir.resolve("fail-home").toString());
        System.setProperty("stellarai.metadata.db", db.toString());
        SqliteProjectMetadataStore isolated = new SqliteProjectMetadataStore(db);
        service = new DesktopAppService(new DesktopSession(), new FailOnUpsertStore(isolated));
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.newProject("Broken"));
        assertTrue(thrown.getMessage() != null && thrown.getMessage().contains("disk unavailable"));
        assertFalse(service.isDurableNamedProject());
        assertTrue(isolated.listProjects().isEmpty());
    }

    @Test
    void storageCoreLastWorkspaceApiIsPresent() {
        assertTrue(StorageCoreCompatibility.lastWorkspaceApiPresent());
    }

    private static InMemorySignalCatalogStore catalog() {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 1_000);
        store.accept(List.of(new CanonicalSignal(
                "VehicleSpeed", "Speed", 1_000L, 0x100, 1, 42.0, 42L, "km/h", null,
                SourceReference.ofLine(1))));
        return store;
    }

    private static final class FailOnUpsertStore implements ProjectMetadataStore {
        private final ProjectMetadataStore inner;

        private FailOnUpsertStore(ProjectMetadataStore inner) {
            this.inner = inner;
        }

        @Override
        public void upsertProject(ProjectMetadata project) {
            throw new RuntimeException("disk unavailable");
        }

        @Override
        public void upsertFile(com.stellar.platform.storage.metadata.ProjectFileMetadata file) {
            inner.upsertFile(file);
        }

        @Override
        public void deleteFile(String projectId, String fileId) {
            inner.deleteFile(projectId, fileId);
        }

        @Override
        public void saveInventory(String projectId, String sourceId,
                                 com.stellar.platform.domain.inventory.CanSourceInventory inventory) {
            inner.saveInventory(projectId, sourceId, inventory);
        }

        @Override
        public void upsertChannelMapping(com.stellar.platform.storage.metadata.ChannelMappingMetadata mapping) {
            inner.upsertChannelMapping(mapping);
        }

        @Override
        public void deleteChannelMapping(String projectId, String sourceId, int channelId) {
            inner.deleteChannelMapping(projectId, sourceId, channelId);
        }

        @Override
        public void upsertProcessingRun(com.stellar.platform.storage.metadata.ProcessingRunMetadata run) {
            inner.upsertProcessingRun(run);
        }

        @Override
        public void upsertGraphInvestigation(com.stellar.platform.storage.metadata.GraphInvestigationMetadata state) {
            inner.upsertGraphInvestigation(state);
        }

        @Override
        public Optional<com.stellar.platform.storage.metadata.GraphInvestigationMetadata> findGraphInvestigation(
                String projectId) {
            return inner.findGraphInvestigation(projectId);
        }

        @Override
        public List<ProjectMetadata> listProjects() {
            return inner.listProjects();
        }

        @Override
        public Optional<com.stellar.platform.storage.metadata.SavedProjectSnapshot> loadProject(String projectId) {
            return inner.loadProject(projectId);
        }

        @Override
        public void close() {
            inner.close();
        }
    }
}
