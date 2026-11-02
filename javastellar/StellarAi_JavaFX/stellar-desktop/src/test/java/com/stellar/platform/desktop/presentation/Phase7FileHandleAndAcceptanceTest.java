package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.MainShellFxScenario;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase7FileHandleAndAcceptanceTest {

    @TempDir Path temp;
    private DesktopAppService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void closedMf4AccessReleasesTheFileOnWindows() throws Exception {
        Path mf4 = temp.resolve("lock-probe.mf4");
        Files.write(mf4, new byte[64]);
        MdfFileAccess access = MdfFileAccess.open(mf4);
        access.close();
        Files.delete(mf4);
        assertFalse(Files.exists(mf4), "FileChannel must be closed so the measurement file can be deleted");
    }

    @Test
    void projectCloseReleasesAddedMeasurementPath() throws Exception {
        openService();
        service.newProject("File Unlock");
        Path mf4 = temp.resolve("added.mf4");
        Files.write(mf4, new byte[64]);
        service.addMeasurementPaths(List.of(mf4), DesktopAppService.PlatformUi.NOOP);
        service.closeProject();
        service.shutdown();
        service = null;
        Files.delete(mf4);
        assertFalse(Files.exists(mf4));
    }

    @Test
    @Timeout(60)
    void newProjectJourneyLandsOnSourcesAndSaves() throws Exception {
        MainShellFxScenario.run(temp, "journey-a", (svc, shell) -> {
            shell.setNewProjectNameForTest("V1 Journey");
            shell.requestNewProject();
            assertTrue(svc.isDurableNamedProject());
            assertEquals(WorkspaceRoute.SOURCES, shell.visibleRoute());
            shell.openRoute(WorkspaceRoute.DECODE);
            assertEquals(WorkspaceRoute.DECODE, shell.visibleRoute());
            assertTrue(svc.saveProject().saved());
            assertTrue(shell.closeActiveProject());
            assertEquals(WorkspaceRoute.HOME, shell.visibleRoute());
        });
    }

    @Test
    void reopenRestoresDuplicateIdentitiesAndEmptyClearView() {
        openService();
        service.newProject("Journey D/I");
        SignalCatalogKey can1 = speedKey(1);
        SignalCatalogKey can4 = speedKey(4);
        service.signalsModel().replaceCatalog(catalog(speed(1), speed(4)));
        service.signalsModel().addToWorkspace(List.of(can1, can4));
        service.graphInvestigation().hide(can4);
        service.persistGraphInvestigation();
        assertTrue(service.saveProject().saved());
        service.signalsModel().clearWorkspace();
        service.graphInvestigation().beginSession();
        service.restoreGraphInvestigation();
        assertEquals(List.of(can1, can4), service.signalsModel().workspaceKeys());
        assertFalse(service.graphInvestigation().isHidden(can4),
                "temporary waveform visibility is not part of the saved selection");
        assertNotEquals(can1, can4);

        service.signalsModel().clearWorkspace();
        service.graphInvestigation().clearAnalysis();
        service.persistGraphInvestigation();
        assertTrue(service.saveProject().saved());
        service.signalsModel().addToWorkspace(List.of(can1, can4));
        service.restoreGraphInvestigation();
        assertTrue(service.signalsModel().workspaceKeys().isEmpty());
    }

    @Test
    void missingSourceDoesNotDropCatalogMembership() throws Exception {
        openService();
        service.newProject("Missing Source");
        Path mf4 = temp.resolve("gone.mf4");
        com.stellar.platform.desktop.support.TestMeasurementFixtures.validMinimalMf4(mf4);
        service.addMeasurementPaths(List.of(mf4), DesktopAppService.PlatformUi.NOOP);
        service.signalsModel().replaceCatalog(catalog(speed(1)));
        SignalCatalogKey key = speedKey(1);
        service.signalsModel().addToWorkspace(key);
        assertTrue(service.saveProject().saved());
        String id = service.currentProjectId();
        Files.deleteIfExists(mf4);
        service.closeProject();
        assertTrue(service.openSavedProject(id).opened());
        assertEquals(1, service.workspace().measurementFiles().size());
        assertFalse(Files.exists(mf4));
    }

    @Test
    void customDecodeModePersistsAcrossLeave() {
        openService();
        service.newProject("Custom Journey");
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        assertEquals(DecisionWorkspaceMode.DETAILED_MAPPING, service.decisionWorkspaceMode());
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.QUICK_DECODE);
        assertEquals(DecisionWorkspaceMode.QUICK_DECODE, service.decisionWorkspaceMode());
    }

    private void openService() {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
    }

    private static SignalCatalogKey speedKey(int channel) {
        return new SignalCatalogKey(
                "VehicleSpeed", com.stellar.platform.domain.catalog.SignalSourceType.DBC_CAN,
                "Speed", 0x100, channel, "run.mf4", "veh.dbc");
    }

    private static CanonicalSignal speed(int channel) {
        return new CanonicalSignal(
                "VehicleSpeed", "Speed", 1_000L * channel, 0x100, channel, 42.0 + channel, 42L, "km/h",
                null, SourceReference.ofLine(channel));
    }

    private static InMemorySignalCatalogStore catalog(CanonicalSignal... signals) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 1_000);
        store.accept(List.of(signals));
        return store;
    }
}
