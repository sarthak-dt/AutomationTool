package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BD-25: saved graph keys are restored by exact logical or physical identity.
 * A project-logical key is not rewritten onto the first measurement that shares its name.
 */
class GraphCheckpointRestoreTest {

    @TempDir Path tempDir;

    private DesktopAppService service;
    private String previousHome;

    @AfterEach
    void cleanup() {
        if (service != null) {
            service.shutdown();
        }
        if (previousHome == null) {
            System.clearProperty("stellarai.home");
        } else {
            System.setProperty("stellarai.home", previousHome);
        }
    }

    @Test
    void mixedLogicalAndPhysicalSelectionsSurviveReopen() throws Exception {
        ProjectMergedSignalStore store = openProject();
        SignalCatalogKey datPhysical = physical(store, "dat-1", "IN_Vehicle_speed");
        SignalCatalogKey logPhysical = physical(store, "log-1", "IN_Vehicle_speed");
        SignalCatalogKey speedLogical = logical("IN_Vehicle_speed");
        SignalCatalogKey ascLogical = logical("DCDC_OUT_VOLTAGE");
        SignalCatalogKey blfPhysical = physical(store, "blf-1", "BLF_PACK_VOLTAGE");
        SignalCatalogKey n597Logical = logical("DISPLAY_SPEED");
        SignalCatalogKey missing = new SignalCatalogKey(
                "NOT_IN_CATALOG",
                speedLogical.sourceType(),
                speedLogical.messageName(),
                speedLogical.canId(),
                speedLogical.channel(),
                speedLogical.sourceFile(),
                speedLogical.sourceDatabase());

        assertEquals(4, service.signalsModel().engineeringCatalogAll().size());
        assertEquals(7, store.physicalCatalogAll().size());
        assertFalse(speedLogical.equals(datPhysical));
        assertTrue(service.workspace().measurementFiles().stream()
                .allMatch(file -> file.associatedDefinitionId() == null));

        service.selectAnalysisScope(AnalysisScope.allMeasurements(service.workspace().measurementFiles().size()));
        service.signalsModel().replacePhysicalWorkspace(List.of(
                datPhysical, speedLogical, ascLogical, blfPhysical, n597Logical, missing));
        service.persistGraphInvestigation();
        assertTrue(service.saveProject().saved());
        String projectId = service.currentProjectId();
        service.closeProject();

        assertTrue(service.openSavedProject(projectId).opened());
        assertTrue(service.signalsModel().workspaceKeys().isEmpty(),
                "graph selection waits until the catalog is available");
        service.signalsModel().replaceCatalog(store);
        service.restoreGraphInvestigation();

        List<SignalCatalogKey> restored = service.signalsModel().workspaceKeys();
        assertEquals(List.of(datPhysical, speedLogical, ascLogical, blfPhysical, n597Logical), restored);
        assertFalse(restored.contains(logPhysical));
        assertFalse(restored.contains(missing));
        assertFalse(restored.contains(physical(store, "asc-1", "DCDC_OUT_VOLTAGE")));
        assertTrue(service.isProjectWideAnalysis());
        assertTrue(service.workspace().measurementFiles().stream()
                .allMatch(file -> file.associatedDefinitionId() == null));
        assertEquals(4, service.signalsModel().catalog().size());
        assertEquals(7, store.physicalCatalogAll().size());
    }

    @Test
    void explicitEmptySelectionStaysEmpty() throws Exception {
        ProjectMergedSignalStore store = openProject();
        service.signalsModel().replacePhysicalWorkspace(List.of(logical("DISPLAY_SPEED")));
        service.signalsModel().replacePhysicalWorkspace(List.of());
        service.persistGraphInvestigation();
        assertTrue(service.saveProject().saved());
        String projectId = service.currentProjectId();
        service.closeProject();

        assertTrue(service.openSavedProject(projectId).opened());
        service.signalsModel().replaceCatalog(store);
        service.restoreGraphInvestigation();
        assertTrue(service.signalsModel().workspaceKeys().isEmpty());
    }

    @Test
    void projectSwitchDoesNotRestoreTheOtherGraph() throws Exception {
        ProjectMergedSignalStore first = openProject();
        SignalCatalogKey firstKey = logical("DISPLAY_SPEED");
        service.selectAnalysisScope(AnalysisScope.allMeasurements(service.workspace().measurementFiles().size()));
        service.signalsModel().replacePhysicalWorkspace(List.of(firstKey));
        service.persistGraphInvestigation();
        assertTrue(service.saveProject().saved());
        String firstId = service.currentProjectId();

        service.newProject("Second");
        Path other = tempDir.resolve("other.mf4");
        Files.writeString(other, "stub");
        service.workspace().addFile(WorkspaceFile.restore("n597-2", other, FileKind.MEASUREMENT_MF4, 1));
        ProjectMergedSignalStore second = ProjectMergedSignalStore.fromMembers(
                List.of(member("n597-2", "other.mf4", "DISPLAY_SPEED", "CLUSTER", 100, 1, "rev7")),
                "one", "rev7");
        service.signalsModel().replaceCatalog(second);
        SignalCatalogKey secondKey = logical("DISPLAY_SPEED");
        service.signalsModel().replacePhysicalWorkspace(List.of(secondKey));
        service.persistGraphInvestigation();
        assertTrue(service.saveProject().saved());
        String secondId = service.currentProjectId();

        assertTrue(service.openSavedProject(firstId).opened());
        service.signalsModel().replaceCatalog(first);
        service.restoreGraphInvestigation();
        assertEquals(List.of(firstKey), service.signalsModel().workspaceKeys());

        assertTrue(service.openSavedProject(secondId).opened());
        service.signalsModel().replaceCatalog(second);
        service.restoreGraphInvestigation();
        assertEquals(List.of(secondKey), service.signalsModel().workspaceKeys());
        assertFalse(firstId.equals(secondId));
        assertEquals("other.mf4", service.workspace().measurementFiles().getFirst().fileName());
    }

    private ProjectMergedSignalStore openProject() throws Exception {
        previousHome = System.getProperty("stellarai.home");
        System.setProperty("stellarai.home", tempDir.resolve("home").toAbsolutePath().toString());
        service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()),
                new SqliteProjectMetadataStore(tempDir.resolve("graph-restore.db")));
        service.newProject("Graph restore");
        List<ProjectMergedSignalStore.Member> members = new ArrayList<>();
        members.add(add("dat-1", "speed.dat", FileKind.MEASUREMENT_DAT,
                "IN_Vehicle_speed", InMemorySignalCatalogStore.DIRECT_MEASUREMENT_MESSAGE, 0, 3, "direct"));
        members.add(add("log-1", "speed.log", FileKind.MEASUREMENT_LOG,
                "IN_Vehicle_speed", InMemorySignalCatalogStore.DIRECT_MEASUREMENT_MESSAGE, 0, 3, "direct"));
        members.add(add("asc-1", "a.asc", FileKind.MEASUREMENT_ASC,
                "DCDC_OUT_VOLTAGE", "DCDC_283h", 643, 1, "dbc-2"));
        members.add(add("asc-2", "b.asc", FileKind.MEASUREMENT_ASC,
                "DCDC_OUT_VOLTAGE", "DCDC_283h", 643, 1, "dbc-2"));
        members.add(add("asc-3", "c.asc", FileKind.MEASUREMENT_ASC,
                "DCDC_OUT_VOLTAGE", "DCDC_283h", 643, 1, "dbc-2"));
        members.add(add("blf-1", "pack.blf", FileKind.MEASUREMENT_BLF,
                "BLF_PACK_VOLTAGE", "PACK_100h", 256, 1, "dbc-2"));
        members.add(add("n597", "n597.mf4", FileKind.MEASUREMENT_MF4,
                "DISPLAY_SPEED", "CLUSTER", 200, 1, "rev7"));
        ProjectMergedSignalStore store = ProjectMergedSignalStore.fromMembers(members, "mixed", "definitions");
        service.signalsModel().replaceCatalog(store);
        return store;
    }

    private ProjectMergedSignalStore.Member add(
            String id,
            String fileName,
            FileKind kind,
            String signal,
            String message,
            long canId,
            int channel,
            String definitionId
    ) throws Exception {
        Path path = tempDir.resolve(fileName);
        Files.writeString(path, "stub");
        service.workspace().addFile(WorkspaceFile.restore(id, path, kind, 1));
        return member(id, fileName, signal, message, canId, channel, definitionId);
    }

    private static ProjectMergedSignalStore.Member member(
            String id,
            String fileName,
            String signal,
            String message,
            long canId,
            int channel,
            String definitionId
    ) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(fileName, definitionId, 8);
        store.accept(List.of(new CanonicalSignal(
                signal, message, 1L, canId, channel, 1.0, 1L, "u", null, SourceReference.ofLine(1))));
        return new ProjectMergedSignalStore.Member(store, id, definitionId, fileName, definitionId);
    }

    private SignalCatalogKey logical(String name) {
        return service.signalsModel().engineeringCatalogAll().stream()
                .map(SignalCatalogEntry::key)
                .filter(key -> name.equals(key.signalName())
                        && ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE.equals(key.sourceFile()))
                .findFirst()
                .orElseThrow();
    }

    private static SignalCatalogKey physical(ProjectMergedSignalStore store, String sourceId, String name) {
        return store.physicalCatalogAll().stream()
                .map(SignalCatalogEntry::key)
                .filter(key -> name.equals(key.signalName()) && sourceId.equals(key.sourceFile()))
                .findFirst()
                .orElseThrow();
    }
}
