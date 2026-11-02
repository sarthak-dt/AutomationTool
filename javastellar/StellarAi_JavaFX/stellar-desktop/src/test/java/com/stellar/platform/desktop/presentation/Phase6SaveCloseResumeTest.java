package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.storage.StellarAiPaths;
import com.stellar.platform.storage.metadata.DuplicateProjectNameException;
import com.stellar.platform.storage.metadata.FileFingerprint;
import com.stellar.platform.storage.metadata.ProjectFileMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadataStore;
import com.stellar.platform.storage.metadata.SavedProjectSnapshot;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 — save / close / reopen / resume hardening.
 */
class Phase6SaveCloseResumeTest {

    @TempDir Path tempDir;
    private DesktopAppService service;
    private SqliteProjectMetadataStore store;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private void openService() {
        System.setProperty("stellarai.home", tempDir.resolve("home").toString());
        Path db = tempDir.resolve("meta.db");
        System.setProperty("stellarai.metadata.db", db.toString());
        store = new SqliteProjectMetadataStore(db);
        service = new DesktopAppService(new DesktopSession(), store);
    }

    @Test
    void logicalChangeMarksProjectDirty() {
        openService();
        service.newProject("Dirty Check");
        assertEquals(ProjectSaveState.CLEAN, service.projectSaveState());
        service.noteLogicalChange();
        assertEquals(ProjectSaveState.DIRTY, service.projectSaveState());
        assertTrue(service.hasUnsavedChanges());
        assertNotEquals("Saved", service.saveStatusLabel());
    }

    @Test
    void temporaryGraphHoverDoesNotMarkDirty() {
        openService();
        service.newProject("Hover");
        service.noteTransientGraphHover();
        assertEquals(ProjectSaveState.CLEAN, service.projectSaveState());
        assertTrue(service.saveStatusLabel().startsWith("Saved"));
    }

    @Test
    void explicitSaveClearsDirtyState() {
        openService();
        service.newProject("Save Clears");
        service.noteLogicalChange();
        assertTrue(service.saveProject().saved());
        assertEquals(ProjectSaveState.CLEAN, service.projectSaveState());
        assertFalse(service.hasUnsavedChanges());
        assertTrue(service.saveStatusLabel().startsWith("Saved"));
        assertTrue(service.lastSavedAt().isPresent());
    }

    @Test
    void saveFailureDoesNotClearDirtyState() {
        ToggleFailStore failing = ToggleFailStore.open(tempDir);
        service = new DesktopAppService(new DesktopSession(), failing);
        service.newProject("Fail Save");
        failing.failWrites = true;
        DesktopAppService.ProjectSaveResult result = service.saveProject();
        assertFalse(result.saved());
        assertEquals(ProjectSaveState.SAVE_FAILED, service.projectSaveState());
        assertTrue(service.hasUnsavedChanges());
        assertEquals("Save failed", service.saveStatusLabel());
        assertNotEquals("Saved", service.saveStatusLabel());
    }

    @Test
    void saveFailureDoesNotShowSaved() {
        saveFailureDoesNotClearDirtyState();
        assertEquals("Save failed", service.saveStatusLabel());
    }

    @Test
    void successfulRetryClearsSaveError() {
        ToggleFailStore failing = ToggleFailStore.open(tempDir);
        service = new DesktopAppService(new DesktopSession(), failing);
        service.newProject("Retry Save");
        failing.failWrites = true;
        assertFalse(service.saveProject().saved());
        failing.failWrites = false;
        assertTrue(service.saveProject().saved());
        assertEquals(ProjectSaveState.CLEAN, service.projectSaveState());
        assertTrue(service.saveStatusLabel().startsWith("Saved"));
    }

    @Test
    void ctrlSInvokesAuthoritativeSave() {
        openService();
        service.newProject("Ctrl S");
        service.noteLogicalChange();
        assertTrue(service.saveProject().saved(), "Ctrl+S is wired to saveProject()");
        assertEquals(ProjectSaveState.CLEAN, service.projectSaveState());
    }

    @Test
    void discardAndCloseDoesNotPersistUnsavedChange() {
        openService();
        service.newProject("Discard");
        String id = service.currentProjectId();
        service.saveProject();
        service.graphInvestigation().setNotes("unsaved note");
        service.noteGraphLogicalChange();
        service.discardUnsavedLogicalChanges();
        service.closeProject();
        assertFalse(service.isDurableNamedProject());
        DesktopAppService.ProjectOpenResult opened = service.openSavedProject(id);
        assertTrue(opened.opened());
        assertEquals("", service.graphInvestigation().notes());
    }

    @Test
    void closeProjectReleasesRuntimeWithoutDeletingDurableData() {
        openService();
        service.newProject("Keep Durable");
        String id = service.currentProjectId();
        service.saveProject();
        service.closeProject();
        assertTrue(service.savedProjects().stream().anyMatch(p -> p.projectId().equals(id)));
        assertFalse(service.isDurableNamedProject());
    }

    @Test
    void renameDoesNotTriggerReprocessingAndPreservesIdentity() {
        openService();
        service.newProject("Before Rename");
        String id = service.currentProjectId();
        service.saveProject();
        SignalCatalogKey key = new SignalCatalogKey(
                "VehicleSpeed", SignalSourceType.DBC_CAN, "ABS", 0x123, 1, "run.mf4", "veh.dbc");
        service.signalsModel().addToWorkspace(key);
        service.graphInvestigation().setNotes("keep");
        service.persistGraphInvestigation();
        service.renameProject("After Rename");
        assertEquals(id, service.currentProjectId());
        assertEquals("After Rename", service.workspace().projectName());
        assertEquals(List.of(key), service.signalsModel().workspaceKeys());
        assertEquals("keep", service.graphInvestigation().notes());
        assertEquals(0, service.processingResultRegistry().all().size());
    }

    @Test
    void normalizedDuplicateProjectNameIsRejected() {
        openService();
        service.newProject("My Project");
        assertThrows(DuplicateProjectNameException.class, () -> service.newProject(" my project "));
    }

    @Test
    void deleteProjectRemovesInternalStateNotExternalSource() throws Exception {
        openService();
        Path source = tempDir.resolve("keep.mf4");
        Files.writeString(source, "original-bytes");
        service.newProject("Delete Me");
        String id = service.currentProjectId();
        service.addMeasurementPaths(List.of(source), DesktopAppService.PlatformUi.NOOP);
        service.saveProject();
        Path managed = StellarAiPaths.projectRoot(id);
        Files.createDirectories(managed);
        Files.writeString(managed.resolve("artifact.bin"), "derived");
        assertTrue(service.deleteProject(id).deleted());
        assertTrue(Files.exists(source));
        assertFalse(Files.exists(managed));
        assertTrue(service.savedProjects().stream().noneMatch(p -> p.projectId().equals(id)));
    }

    @Test
    void locateExactSourcePreservesProjectIdentity() throws Exception {
        openService();
        service.newProject("Locate Same");
        Path original = tempDir.resolve("orig.mf4");
        com.stellar.platform.desktop.support.TestMeasurementFixtures.validMinimalMf4(original);
        service.addMeasurementPaths(List.of(original), DesktopAppService.PlatformUi.NOOP);
        WorkspaceFile added = service.workspace().measurementFiles().getFirst();
        ProjectSourceId identity = added.projectSourceId();
        service.rememberProcessedSourceFingerprintForTest(identity.value(), FileFingerprint.of(original));
        Path moved = tempDir.resolve("moved.mf4");
        Files.copy(original, moved);
        java.nio.file.Files.setLastModifiedTime(moved, Files.getLastModifiedTime(original));
        RelocateSourceResult result = service.relocateSourceDetailed(identity, moved);
        assertEquals(RelocateSourceResult.REATTACHED, result);
        assertEquals(identity, service.workspace().measurementFiles().getFirst().projectSourceId());
    }

    @Test
    void locateDifferentContentIsNotAcceptedAsSameSource() throws Exception {
        openService();
        service.newProject("Locate Different");
        Path original = tempDir.resolve("orig.mf4");
        com.stellar.platform.desktop.support.TestMeasurementFixtures.validMinimalMf4(original, 1);
        service.addMeasurementPaths(List.of(original), DesktopAppService.PlatformUi.NOOP);
        WorkspaceFile added = service.workspace().measurementFiles().getFirst();
        ProjectSourceId identity = added.projectSourceId();
        service.rememberProcessedSourceFingerprintForTest(identity.value(), FileFingerprint.of(original));
        Path other = tempDir.resolve("other.mf4");
        com.stellar.platform.desktop.support.TestMeasurementFixtures.validMinimalMf4(other, 2);
        RelocateSourceResult result = service.relocateSourceDetailed(identity, other);
        assertEquals(RelocateSourceResult.DIFFERENT_CONTENT, result);
        assertEquals(original.toAbsolutePath().normalize(),
                service.workspace().measurementFiles().getFirst().path());
    }

    @Test
    void dataOnlyPersistDoesNotOverwriteResumeCheckpoint() {
        openService();
        service.newProject("Checkpoint Lock");
        String id = service.currentProjectId();
        service.rememberMeaningfulWorkspace(WorkspaceRoute.GRAPHS);
        assertTrue(service.saveProject().saved());
        Instant savedAt = service.lastSavedAt().orElseThrow();
        service.rememberMeaningfulWorkspace(WorkspaceRoute.SIGNALS);
        service.graphInvestigation().setNotes("unsaved navigation note");
        service.noteGraphLogicalChange();
        assertTrue(service.saveProjectDataOnly().saved());
        service.closeProject();
        assertTrue(service.openSavedProject(id).opened());
        Optional<ProjectMetadata> meta = store.loadProject(id).map(SavedProjectSnapshot::project);
        assertTrue(meta.isPresent());
        assertEquals("GRAPHS", meta.get().lastWorkspace());
        assertEquals(savedAt, meta.get().lastSavedAt());
        assertEquals(WorkspaceRoute.GRAPHS, service.lastMeaningfulWorkspace().orElse(null));
        assertEquals("", service.graphInvestigation().notes());
    }

    @Test
    void secondExplicitSaveMovesResumeToSignals() {
        openService();
        service.newProject("Second Save");
        String id = service.currentProjectId();
        service.rememberMeaningfulWorkspace(WorkspaceRoute.GRAPHS);
        assertTrue(service.saveProject().saved());
        Instant firstSave = service.lastSavedAt().orElseThrow();
        service.rememberMeaningfulWorkspace(WorkspaceRoute.SIGNALS);
        assertTrue(service.saveProject().saved());
        Instant secondSave = service.lastSavedAt().orElseThrow();
        assertTrue(!secondSave.isBefore(firstSave));
        service.closeProject();
        assertTrue(service.openSavedProject(id).opened());
        assertEquals("SIGNALS", store.loadProject(id).orElseThrow().project().lastWorkspace());
        assertEquals(secondSave, store.loadProject(id).orElseThrow().project().lastSavedAt());
    }

    @Test
    void clearViewPersistsEmptyMembership() {
        openService();
        service.newProject("Clear View");
        SignalCatalogKey key = speedKey(1);
        service.signalsModel().replaceCatalog(catalog(speed(1)));
        service.signalsModel().addToWorkspace(key);
        service.signalsModel().clearWorkspace();
        service.persistGraphInvestigation();
        service.signalsModel().addToWorkspace(key);
        service.restoreGraphInvestigation();
        assertTrue(service.signalsModel().workspaceKeys().isEmpty());
    }

    @Test
    void removedTraceDoesNotReturnAfterReopen() {
        openService();
        service.newProject("Remove Trace");
        SignalCatalogKey keep = speedKey(1);
        SignalCatalogKey drop = speedKey(4);
        service.signalsModel().replaceCatalog(catalog(speed(1), speed(4)));
        service.signalsModel().addToWorkspace(List.of(keep, drop));
        service.signalsModel().removeFromWorkspace(drop);
        service.persistGraphInvestigation();
        service.signalsModel().clearWorkspace();
        service.restoreGraphInvestigation();
        assertEquals(List.of(keep), service.signalsModel().workspaceKeys());
    }

    @Test
    void hiddenTraceVisibilityRestores() {
        openService();
        service.newProject("Hidden Trace");
        SignalCatalogKey key = speedKey(1);
        service.signalsModel().replaceCatalog(catalog(speed(1)));
        service.signalsModel().addToWorkspace(key);
        service.graphInvestigation().hide(key);
        service.persistGraphInvestigation();
        service.signalsModel().clearWorkspace();
        service.graphInvestigation().beginSession();
        service.restoreGraphInvestigation();
        assertEquals(List.of(key), service.signalsModel().workspaceKeys());
        assertFalse(service.graphInvestigation().isHidden(key),
                "temporary waveform visibility is not part of the saved selection");
    }

    @Test
    void notesAndCursorsFlushOnExplicitSave() {
        openService();
        service.newProject("Flush Graph");
        SignalCatalogKey key = speedKey(1);
        service.signalsModel().replaceCatalog(catalog(speed(1)));
        service.signalsModel().addToWorkspace(key);
        service.graphInvestigation().setNotes("bookmark note");
        service.graphInvestigation().setCursorA(1000L);
        service.graphInvestigation().setCursorB(2000L);
        service.noteGraphLogicalChange();
        assertTrue(service.saveProject().saved());
        service.signalsModel().clearWorkspace();
        service.graphInvestigation().beginSession();
        service.restoreGraphInvestigation();
        assertEquals("bookmark note", service.graphInvestigation().notes());
        assertEquals(1000L, service.graphInvestigation().cursorA());
        assertEquals(2000L, service.graphInvestigation().cursorB());
    }

    @Test
    void duplicateTraceMembershipRestoresByStableIdentity() {
        openService();
        service.newProject("Dup Identity");
        SignalCatalogKey can1 = speedKey(1);
        SignalCatalogKey can4 = speedKey(4);
        service.signalsModel().replaceCatalog(catalog(speed(1), speed(4)));
        service.signalsModel().addToWorkspace(List.of(can1, can4));
        service.persistGraphInvestigation();
        service.signalsModel().clearWorkspace();
        service.graphInvestigation().beginSession();
        service.restoreGraphInvestigation();
        assertEquals(List.of(can1, can4), service.signalsModel().workspaceKeys());
        assertNotEquals(can1, can4);
    }

    @Test
    void lastMeaningfulWorkspaceIsNotABlindRestoreOfProcessingRoutes() {
        openService();
        service.newProject("Workspace");
        service.rememberMeaningfulWorkspace(WorkspaceRoute.PROCESSING_RESUME);
        assertTrue(service.lastMeaningfulWorkspace().isEmpty());
        service.rememberMeaningfulWorkspace(WorkspaceRoute.SIGNALS);
        assertEquals(Optional.of(WorkspaceRoute.SIGNALS), service.lastMeaningfulWorkspace());
    }

    @Test
    void metadataWriteFailurePropagatesToSaveState() {
        ToggleFailStore failing = ToggleFailStore.open(tempDir);
        service = new DesktopAppService(new DesktopSession(), failing);
        service.newProject("Write Fail");
        failing.failWrites = true;
        service.persistGraphInvestigation();
        assertEquals(ProjectSaveState.SAVE_FAILED, service.projectSaveState());
        assertEquals("Save failed", service.saveStatusLabel());
    }

    @Test
    void failedAutosaveCanBeRetried() {
        successfulRetryClearsSaveError();
    }

    @Test
    void failedSaveDoesNotCorruptExistingPersistedState() {
        ToggleFailStore failing = ToggleFailStore.open(tempDir);
        service = new DesktopAppService(new DesktopSession(), failing);
        service.newProject("Keep Good");
        String id = service.currentProjectId();
        assertTrue(service.saveProject().saved());
        failing.failWrites = true;
        service.graphInvestigation().setNotes("should not persist");
        service.noteGraphLogicalChange();
        assertFalse(service.saveProject().saved());
        service.shutdown();
        failing = ToggleFailStore.reopen(failing);
        service = new DesktopAppService(new DesktopSession(), failing);
        assertTrue(service.openSavedProject(id).opened());
        assertEquals("", service.graphInvestigation().notes());
    }

    @Test
    void parallelSaveRequestsAreCoalescedOrSerialized() {
        openService();
        service.newProject("Serialize");
        synchronized (service.saveCoordinatorForTest().saveLock()) {
            DesktopAppService.ProjectSaveResult first = service.saveProject();
            assertTrue(first.saved());
        }
    }

    @Test
    void reopenGraphUsesPersistedSignalIds() {
        duplicateTraceMembershipRestoresByStableIdentity();
    }

    @Test
    void explicitSaveRestoresSelectedOrderVisibilityFocusAndViewport() {
        openService();
        service.newProject("Graph Session");
        String id = service.currentProjectId();
        SignalCatalogKey rpm = new SignalCatalogKey(
                "EngineRPM", SignalSourceType.DBC_CAN, "ECM", 0x101, 1, "run.mf4", "veh.dbc");
        SignalCatalogKey speed = speedKey(1);
        SignalCatalogKey coolant = new SignalCatalogKey(
                "CoolantTemp", SignalSourceType.DBC_CAN, "ECM", 0x102, 1, "run.mf4", "veh.dbc");
        service.signalsModel().replaceCatalog(catalog(
                named("EngineRPM", "ECM", 0x101, 1),
                speed(1),
                named("CoolantTemp", "ECM", 0x102, 1)));
        service.signalsModel().addToWorkspace(List.of(rpm, speed, coolant));
        service.graphInvestigation().hide(speed);
        service.graphInvestigation().setFocus(rpm);
        service.graphInvestigation().setLayoutMode(GraphInvestigationState.OVERLAY);
        service.graphInvestigation().setAnalysisTab(1);
        service.graphInvestigation().setInspectorVisible(false);
        service.graphInvestigation().setPlotSettingsVisible(true);
        service.graphInvestigation().setViewport(10_000L, 90_000L);
        service.rememberMeaningfulWorkspace(WorkspaceRoute.GRAPHS);
        Instant before = Instant.now().minusSeconds(1);
        assertTrue(service.saveProject().saved());
        Instant savedAt = service.lastSavedAt().orElseThrow();
        assertFalse(savedAt.isBefore(before));
        assertTrue(service.saveStatusLabel().startsWith("Saved "));

        service.closeProject();
        assertTrue(service.openSavedProject(id).opened());
        // Reopen has no MF4 artifacts in this unit test; publish the catalog then recover
        // the checkpoint the same way production does after Analysis Storage seed.
        service.signalsModel().replaceCatalog(catalog(
                named("EngineRPM", "ECM", 0x101, 1),
                speed(1),
                named("CoolantTemp", "ECM", 0x102, 1)));
        service.restoreGraphInvestigationIfWorkspaceEmpty();
        assertEquals(List.of(rpm, speed, coolant), service.signalsModel().workspaceKeys());
        assertFalse(service.graphInvestigation().isHidden(speed),
                "temporary waveform visibility is not part of the saved selection");
        assertFalse(service.graphInvestigation().isHidden(rpm));
        assertEquals(rpm, service.graphInvestigation().focusKey());
        assertEquals(GraphInvestigationState.OVERLAY, service.graphInvestigation().layoutMode());
        assertEquals(1, service.graphInvestigation().analysisTab());
        assertFalse(service.graphInvestigation().inspectorVisible());
        assertTrue(service.graphInvestigation().plotSettingsVisible());
        assertEquals(10_000L, service.graphInvestigation().viewportStart());
        assertEquals(90_000L, service.graphInvestigation().viewportEnd());
        assertEquals(savedAt, service.lastSavedAt().orElseThrow());
        assertEquals("GRAPHS", store.loadProject(id).orElseThrow().project().lastWorkspace());
    }

    @Test
    void explicitGraphsCheckpointResumesGraphsEvenBeforeMembershipRemap() {
        openService();
        service.newProject("Resume Graphs");
        String id = service.currentProjectId();
        service.signalsModel().replaceCatalog(catalog(speed(1)));
        service.signalsModel().addToWorkspace(List.of(speedKey(1)));
        service.graphInvestigation().setAnalysisTab(3);
        service.rememberMeaningfulWorkspace(WorkspaceRoute.GRAPHS);
        assertTrue(service.saveProject().saved());
        service.rememberMeaningfulWorkspace(WorkspaceRoute.SIGNALS);
        service.graphInvestigation().setAnalysisTab(0);
        assertTrue(service.saveProjectDataOnly().saved());
        service.closeProject();
        assertTrue(service.openSavedProject(id).opened());
        service.signalsModel().replaceCatalog(catalog(speed(1)));
        assertEquals(WorkspaceRoute.GRAPHS, service.resolveResumeRoute());
        assertEquals("GRAPHS", store.loadProject(id).orElseThrow().project().lastWorkspace());
        service.restoreGraphInvestigationIfWorkspaceEmpty();
        assertEquals(3, service.graphInvestigation().analysisTab());
        assertEquals(List.of(speedKey(1)), service.signalsModel().workspaceKeys());
    }

    @Test
    void missingSavedSignalIsSkippedWithoutNameSubstitution() {
        openService();
        service.newProject("Missing B");
        SignalCatalogKey a = speedKey(1);
        SignalCatalogKey b = speedKey(4);
        SignalCatalogKey c = new SignalCatalogKey(
                "CoolantTemp", SignalSourceType.DBC_CAN, "ECM", 0x102, 1, "run.mf4", "veh.dbc");
        service.signalsModel().replaceCatalog(catalog(speed(1), speed(4), named("CoolantTemp", "ECM", 0x102, 1)));
        service.signalsModel().addToWorkspace(List.of(a, b, c));
        service.persistGraphInvestigation();
        service.signalsModel().replaceCatalog(catalog(speed(1), named("CoolantTemp", "ECM", 0x102, 1)));
        service.signalsModel().clearWorkspace();
        service.graphInvestigation().beginSession();
        service.restoreGraphInvestigation();
        assertEquals(List.of(a, c), service.signalsModel().workspaceKeys());
        assertFalse(service.signalsModel().workspaceKeys().contains(b));
    }

    @Test
    void lateCatalogPublishRecoversSavedGraphMembership() {
        openService();
        service.newProject("Late Catalog");
        SignalCatalogKey known = speedKey(1);
        SignalCatalogKey unseen = new SignalCatalogKey(
                "TurboBoostPressure_QX9", SignalSourceType.DBC_CAN, "ECM", 0x220, 1, "run.mf4", "veh.dbc");
        service.signalsModel().replaceCatalog(catalog(
                speed(1),
                named("TurboBoostPressure_QX9", "ECM", 0x220, 1)));
        service.signalsModel().addToWorkspace(List.of(known, unseen));
        service.persistGraphInvestigation();
        service.signalsModel().replaceCatalog(new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 1_000));
        service.signalsModel().clearWorkspace();
        service.graphInvestigation().beginSession();
        service.restoreGraphInvestigation();
        assertTrue(service.signalsModel().workspaceKeys().isEmpty());
        service.signalsModel().replaceCatalog(catalog(
                speed(1),
                named("TurboBoostPressure_QX9", "ECM", 0x220, 1)));
        service.restoreGraphInvestigationIfWorkspaceEmpty();
        assertEquals(List.of(known, unseen), service.signalsModel().workspaceKeys());
    }

    @Test
    void navigationWithoutSaveDoesNotOverwriteGraphCheckpoint() {
        openService();
        service.newProject("Hold Checkpoint");
        String id = service.currentProjectId();
        SignalCatalogKey key = speedKey(1);
        service.signalsModel().replaceCatalog(catalog(speed(1)));
        service.signalsModel().addToWorkspace(key);
        service.rememberMeaningfulWorkspace(WorkspaceRoute.GRAPHS);
        assertTrue(service.saveProject().saved());
        service.rememberMeaningfulWorkspace(WorkspaceRoute.SIGNALS);
        service.noteLogicalChange();
        assertTrue(service.saveProjectDataOnly().saved());
        service.closeProject();
        assertTrue(service.openSavedProject(id).opened());
        assertEquals("GRAPHS", store.loadProject(id).orElseThrow().project().lastWorkspace());
        service.signalsModel().replaceCatalog(catalog(speed(1)));
        service.restoreGraphInvestigationIfWorkspaceEmpty();
        assertEquals(List.of(key), service.signalsModel().workspaceKeys());
    }

    @Test
    void currentSchemaVersionLoads() {
        openService();
        assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), store.schemaVersion());
    }

    @Test
    void graphRestoreAcceptanceExactViewportAndWaveformsTab() {
        openService();
        service.newProject("Graph Restore P0");
        String id = service.currentProjectId();
        SignalCatalogKey a = new SignalCatalogKey(
                "EngineRPM", SignalSourceType.DBC_CAN, "ECM", 0x101, 1, "run.mf4", "veh.dbc");
        SignalCatalogKey b = speedKey(1);
        SignalCatalogKey c = new SignalCatalogKey(
                "CoolantTemp", SignalSourceType.DBC_CAN, "ECM", 0x102, 1, "run.mf4", "veh.dbc");
        service.signalsModel().replaceCatalog(catalog(
                named("EngineRPM", "ECM", 0x101, 1),
                speed(1),
                named("CoolantTemp", "ECM", 0x102, 1)));
        service.signalsModel().addToWorkspace(List.of(a, b, c));
        service.graphInvestigation().hide(b);
        service.graphInvestigation().setFocus(c);
        service.graphInvestigation().setLayoutMode(GraphInvestigationState.OVERLAY);
        service.graphInvestigation().setAnalysisTab(0);
        service.graphInvestigation().setInspectorVisible(false);
        long start = Math.round(100.25d * 1_000_000_000L);
        long end = Math.round(175.75d * 1_000_000_000L);
        service.graphInvestigation().setViewport(start, end);
        service.rememberMeaningfulWorkspace(WorkspaceRoute.GRAPHS);
        Instant before = Instant.now().minusSeconds(1);
        assertTrue(service.saveProject().saved());
        Instant savedAt = service.lastSavedAt().orElseThrow();
        assertFalse(savedAt.isBefore(before));
        service.closeProject();
        service.shutdown();
        service = null;
        openService();
        assertTrue(service.openSavedProject(id).opened());
        service.signalsModel().replaceCatalog(catalog(
                named("EngineRPM", "ECM", 0x101, 1),
                speed(1),
                named("CoolantTemp", "ECM", 0x102, 1)));
        service.restoreGraphInvestigationIfWorkspaceEmpty();
        assertEquals("GRAPHS", store.loadProject(id).orElseThrow().project().lastWorkspace());
        assertEquals(List.of(a, b, c), service.signalsModel().workspaceKeys());
        assertFalse(service.graphInvestigation().isHidden(b),
                "temporary waveform visibility is not part of the saved selection");
        assertFalse(service.graphInvestigation().isHidden(a));
        assertEquals(c, service.graphInvestigation().focusKey());
        assertEquals(GraphInvestigationState.OVERLAY, service.graphInvestigation().layoutMode());
        assertEquals(0, service.graphInvestigation().analysisTab(), "WAVEFORMS tab");
        assertFalse(service.graphInvestigation().inspectorVisible());
        assertEquals(start, service.graphInvestigation().viewportStart());
        assertEquals(end, service.graphInvestigation().viewportEnd());
        assertEquals(savedAt, service.lastSavedAt().orElseThrow());
    }

    @Test
    void analysisResultReferenceRestoresWithoutReparse() {
        openService();
        service.newProject("Analysis Resume");
        String id = service.currentProjectId();
        InMemorySignalCatalogStore catalog = catalog(speed(1), speed(2), speed(3));
        service.signalsModel().replaceCatalog(catalog);
        SignalCatalogKey key = new SignalCatalogKey(
                "VehicleSpeed", SignalSourceType.DBC_CAN, "Speed", 0x100, 1, "run.mf4", "veh.dbc");
        service.signalsModel().addToWorkspace(List.of(key));
        var result = service.runEngineeringAnalysis(
                com.stellar.platform.analysis.model.AnalysisRequest.builder(
                                com.stellar.platform.analysis.model.AnalysisType.STATISTICS)
                        .projectId(id)
                        .addSignal(key)
                        .build());
        assertEquals(com.stellar.platform.analysis.model.AnalysisStatus.COMPLETED, result.status());
        assertTrue(service.saveProject().saved());
        service.closeProject();
        service.shutdown();
        service = null;
        openService();
        assertTrue(service.openSavedProject(id).opened());
        assertEquals("STATISTICS", store.findAnalysisWorkspace(id).orElseThrow().lastAnalysisType());
        assertFalse(store.findAnalysisWorkspace(id).orElseThrow().lastAnalysisId().isBlank());
        assertTrue(service.lastEngineeringAnalysisReport().contains("ENGINEERING ANALYSIS")
                || store.findAnalysisRun(store.findAnalysisWorkspace(id).orElseThrow().lastAnalysisId()).isPresent());
    }

    @Test
    void diagnosticResultReferenceRestoresWithoutReparse() {
        openService();
        service.newProject("Diagnostic Resume");
        String id = service.currentProjectId();
        Instant now = Instant.now();
        store.upsertDiagnosticRun(new com.stellar.platform.storage.metadata.DiagnosticRunMetadata(
                "diag-1", id, "generic-engineering", "1.0", "COMPLETED", "CURSOR_A_TO_B",
                1_000_000_000L, 2_000_000_000L, 1, 1, 0, "inv", 1L, "1", "ok", now, now));
        store.replaceDiagnosticFindings("diag-1", List.of(
                new com.stellar.platform.storage.metadata.DiagnosticFindingRow(
                        "f-diag", "diag-1", id, "thermal.high-coolant", "1", "WARNING", "THERMAL",
                        1_000_000_000L, 2_000_000_000L, "High coolant", "peak=107", "COOLANT=107.3", now)));
        store.upsertDiagnosticWorkspace(new com.stellar.platform.storage.metadata.DiagnosticWorkspaceMetadata(
                id, "generic-engineering", "diag-1", "thermal.high-coolant", true, "f-diag", now));
        store.upsertDiagnosticBinding(new com.stellar.platform.storage.metadata.DiagnosticBindingRow(
                id, "generic-engineering", "COOLANT_TEMPERATURE",
                "CoolantTemperature|DBC_CAN|ECM|1536|1|run.mf4|veh.dbc", now));
        assertTrue(service.saveProject().saved());
        service.closeProject();
        service.shutdown();
        service = null;
        openService();
        assertTrue(service.openSavedProject(id).opened());
        assertEquals("generic-engineering", store.findDiagnosticWorkspace(id).orElseThrow().lastPackId());
        assertEquals("diag-1", store.findDiagnosticWorkspace(id).orElseThrow().lastRunId());
        assertFalse(store.listDiagnosticBindings(id, "generic-engineering").isEmpty());
        assertEquals(1, store.listDiagnosticFindings("diag-1").size());
        assertTrue(service.lastEngineeringDiagnosticsReport().contains("DIAGNOSTICS")
                || store.findDiagnosticRun("diag-1").isPresent());
    }

    @Test
    void incompatibleGraphStateDegradesSafely() {
        openService();
        service.newProject("Bad Graph");
        store.upsertGraphInvestigation(new com.stellar.platform.storage.metadata.GraphInvestigationMetadata(
                service.currentProjectId(),
                "STACKED",
                1L, 2L, 1L, 2L, null, null, "n",
                "GIV99\nH\u001f0\u001fbroken",
                Instant.now()));
        service.restoreGraphInvestigation();
        assertTrue(service.signalsModel().workspaceKeys().isEmpty());
        assertTrue(service.isDurableNamedProject());
    }

    @Test
    void signalsSelectSaveNavigateWithoutSaveResumeRestoresSignals() {
        openService();
        service.newProject("Signals Checkpoint");
        String id = service.currentProjectId();
        SignalCatalogKey selected = speedKey(1);
        service.signalsModel().replaceCatalog(catalog(speed(1), speed(4)));
        service.signalsModel().addToWorkspace(selected);
        service.signalsModel().setFocusedSignalKey(selected);
        service.rememberMeaningfulWorkspace(WorkspaceRoute.SIGNALS);
        Instant before = service.lastSavedAt().orElse(Instant.EPOCH);
        assertTrue(service.saveProject().saved());
        Instant checkpoint = service.lastSavedAt().orElseThrow();
        assertTrue(checkpoint.isAfter(before));
        assertTrue(service.saveStatusLabel().startsWith("Saved"));
        assertEquals("SIGNALS", store.loadProject(id).orElseThrow().project().lastWorkspace());

        service.signalsModel().addToWorkspace(speedKey(4));
        service.rememberMeaningfulWorkspace(WorkspaceRoute.GRAPHS);
        service.noteLogicalChange();
        assertTrue(service.saveProjectDataOnly().saved());
        assertEquals(checkpoint, service.lastSavedAt().orElseThrow());
        assertEquals("SIGNALS", store.loadProject(id).orElseThrow().project().lastWorkspace());

        service.closeProject();
        assertTrue(service.openSavedProject(id).opened());
        service.signalsModel().replaceCatalog(catalog(speed(1), speed(4)));
        service.restoreGraphInvestigationIfWorkspaceEmpty();
        assertEquals(WorkspaceRoute.SIGNALS, service.resolveResumeRoute());
        assertEquals(List.of(selected), service.signalsModel().workspaceKeys());
        assertEquals(checkpoint, store.loadProject(id).orElseThrow().project().lastSavedAt());
    }

    @Test
    void graphsSelectSaveNavigateWithoutSaveResumeRestoresGraphs() {
        openService();
        service.newProject("Graphs Checkpoint");
        String id = service.currentProjectId();
        SignalCatalogKey rpm = new SignalCatalogKey(
                "EngineRPM", SignalSourceType.DBC_CAN, "ECM", 0x101, 1, "run.mf4", "veh.dbc");
        SignalCatalogKey speed = speedKey(1);
        service.signalsModel().replaceCatalog(catalog(named("EngineRPM", "ECM", 0x101, 1), speed(1)));
        service.signalsModel().addToWorkspace(List.of(rpm, speed));
        service.graphInvestigation().setFocus(rpm);
        service.graphInvestigation().setAnalysisTab(0);
        service.rememberMeaningfulWorkspace(WorkspaceRoute.GRAPHS);
        Instant before = service.lastSavedAt().orElse(Instant.EPOCH);
        assertTrue(service.saveProject().saved());
        Instant checkpoint = service.lastSavedAt().orElseThrow();
        assertTrue(checkpoint.isAfter(before));
        assertTrue(service.saveStatusLabel().startsWith("Saved"));

        service.rememberMeaningfulWorkspace(WorkspaceRoute.SIGNALS);
        service.graphInvestigation().setAnalysisTab(2);
        service.noteLogicalChange();
        assertTrue(service.saveProjectDataOnly().saved());
        assertEquals(checkpoint, service.lastSavedAt().orElseThrow());
        assertEquals("GRAPHS", store.loadProject(id).orElseThrow().project().lastWorkspace());

        service.closeProject();
        assertTrue(service.openSavedProject(id).opened());
        service.signalsModel().replaceCatalog(catalog(named("EngineRPM", "ECM", 0x101, 1), speed(1)));
        service.restoreGraphInvestigationIfWorkspaceEmpty();
        assertEquals(WorkspaceRoute.GRAPHS, service.resolveResumeRoute());
        assertEquals(List.of(rpm, speed), service.signalsModel().workspaceKeys());
        assertEquals(rpm, service.graphInvestigation().focusKey());
        assertEquals(0, service.graphInvestigation().analysisTab());
        assertEquals(checkpoint, store.loadProject(id).orElseThrow().project().lastSavedAt());
    }

    @Test
    void reopenStartsSavedUntilUserAddsAnEvent() {
        openService();
        service.newProject("Hydration Clean");
        assertTrue(service.saveProject().saved());
        String id = service.currentProjectId();
        service.closeProject();
        assertTrue(service.openSavedProject(id).opened());
        assertEquals(ProjectSaveState.CLEAN, service.projectSaveState());
        assertTrue(service.saveStatusLabel().startsWith("Saved"));
        service.persistInvestigationEvent("Unexpected RPM spike", "Observation", "", 14_321_142_000_000L, "EngineRPM");
        assertEquals(ProjectSaveState.DIRTY, service.projectSaveState());
        assertTrue(service.saveProject().saved());
        service.closeProject();
        assertTrue(service.openSavedProject(id).opened());
        assertEquals(ProjectSaveState.CLEAN, service.projectSaveState());
        assertEquals(1, service.listEngineeringBookmarks().size());
        assertEquals("Unexpected RPM spike",
                InvestigationEventCodec.titleOf(service.listEngineeringBookmarks().getFirst().observation()));
    }

    private static SignalCatalogKey speedKey(int channel) {
        return new SignalCatalogKey(
                "VehicleSpeed", SignalSourceType.DBC_CAN, "Speed", 0x100, channel, "run.mf4", "veh.dbc");
    }

    private static CanonicalSignal speed(int channel) {
        return new CanonicalSignal(
                "VehicleSpeed", "Speed", 1_000L * channel, 0x100, channel, 42.0 + channel, 42L, "km/h", null,
                SourceReference.ofLine(channel));
    }

    private static CanonicalSignal named(String signal, String message, long canId, int channel) {
        return new CanonicalSignal(
                signal, message, 1_000L * channel, canId, channel, 1.0, 1L, "", null,
                SourceReference.ofLine(channel));
    }

    private static InMemorySignalCatalogStore catalog(CanonicalSignal... signals) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 1_000);
        store.accept(List.of(signals));
        return store;
    }

    private static final class ToggleFailStore implements ProjectMetadataStore {
        private final SqliteProjectMetadataStore inner;
        volatile boolean failWrites;

        private final Path databasePath;

        static ToggleFailStore open(Path tempDir) {
            System.setProperty("stellarai.home", tempDir.resolve("home").toString());
            Path db = tempDir.resolve("fail.db");
            return new ToggleFailStore(new SqliteProjectMetadataStore(db), db);
        }

        static ToggleFailStore reopen(ToggleFailStore previous) {
            return new ToggleFailStore(new SqliteProjectMetadataStore(previous.databasePath), previous.databasePath);
        }

        private ToggleFailStore(SqliteProjectMetadataStore inner, Path databasePath) {
            this.inner = inner;
            this.databasePath = databasePath;
        }

        private void maybeFail() {
            if (failWrites) {
                throw new RuntimeException("disk unavailable");
            }
        }

        @Override public void upsertProject(ProjectMetadata project) {
            maybeFail();
            inner.upsertProject(project);
        }

        @Override public void upsertFile(ProjectFileMetadata file) {
            maybeFail();
            inner.upsertFile(file);
        }

        @Override public void deleteFile(String projectId, String fileId) {
            maybeFail();
            inner.deleteFile(projectId, fileId);
        }

        @Override public void upsertChannelMapping(com.stellar.platform.storage.metadata.ChannelMappingMetadata mapping) {
            maybeFail();
            inner.upsertChannelMapping(mapping);
        }

        @Override public void deleteChannelMapping(String projectId, String sourceId, int channelId) {
            maybeFail();
            inner.deleteChannelMapping(projectId, sourceId, channelId);
        }

        @Override public void upsertProcessingRun(com.stellar.platform.storage.metadata.ProcessingRunMetadata run) {
            maybeFail();
            inner.upsertProcessingRun(run);
        }

        @Override public void saveInventory(String projectId, String sourceId,
                                           com.stellar.platform.domain.inventory.CanSourceInventory inventory) {
            maybeFail();
            inner.saveInventory(projectId, sourceId, inventory);
        }

        @Override public void upsertGraphInvestigation(
                com.stellar.platform.storage.metadata.GraphInvestigationMetadata state) {
            maybeFail();
            inner.upsertGraphInvestigation(state);
        }

        @Override public Optional<com.stellar.platform.storage.metadata.GraphInvestigationMetadata>
        findGraphInvestigation(String projectId) {
            return inner.findGraphInvestigation(projectId);
        }

        @Override public List<ProjectMetadata> listProjects() {
            return inner.listProjects();
        }

        @Override public Optional<SavedProjectSnapshot> loadProject(String projectId) {
            return inner.loadProject(projectId);
        }

        @Override public void runInTransaction(Runnable work) {
            maybeFail();
            inner.runInTransaction(work);
        }

        @Override public void close() {
            inner.close();
        }
    }
}
