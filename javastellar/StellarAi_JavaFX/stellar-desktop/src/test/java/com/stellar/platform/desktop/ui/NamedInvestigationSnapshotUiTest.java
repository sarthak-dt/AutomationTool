package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EvidenceKind;
import com.stellar.platform.analysis.investigation.InvestigationStoragePaths;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Create Investigation and both Capture Snapshot actions require an entered name. */
class NamedInvestigationSnapshotUiTest {

    private static final long VIEW_START = 1_000_000_000L;
    private static final long VIEW_END = 5_000_000_000L;

    @TempDir Path temp;
    private DesktopAppService service;
    private GraphController graph;
    private Stage stage;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        JavaFxTestSupport.runOnFx("dispose graph", () -> {
            if (graph != null) {
                graph.dispose();
                graph = null;
            }
            if (stage != null) {
                stage.close();
                stage = null;
            }
        });
        if (service != null) {
            service.shutdown();
            service = null;
        }
        JavaFxTestSupport.drainFxPulse();
    }

    @Test
    @Timeout(60)
    void investigationAndSnapshotRequireAnEnteredName() throws Exception {
        openGraph();
        service.graphInvestigation().setViewport(VIEW_START, VIEW_END);

        Dialog<ButtonType>[] dialogs = new Dialog[2];
        boolean[] disabled = new boolean[4];
        String[] text = new String[6];
        List<String> menu = new java.util.ArrayList<>();
        JavaFxTestSupport.runOnFx("inspect empty name dialogs", () -> {
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            graph.relayoutGraphToolbarForTest();
            menu.addAll(graph.toolbarMoreItemsForTest());
            Dialog<ButtonType> investigation = graph.investigationNameDialogForTest();
            Dialog<ButtonType> snapshot = graph.snapshotNameDialogForTest();
            dialogs[0] = investigation;
            dialogs[1] = snapshot;
            text[0] = graph.requiredNameTextForTest(investigation);
            text[1] = graph.requiredNamePromptForTest(investigation);
            text[2] = graph.requiredNameTextForTest(snapshot);
            text[3] = graph.requiredNamePromptForTest(snapshot);
            disabled[0] = graph.requiredNameConfirmDisabledForTest(investigation);
            disabled[1] = graph.requiredNameConfirmDisabledForTest(snapshot);
            graph.setRequiredNameTextForTest(investigation, "   ");
            graph.setRequiredNameTextForTest(snapshot, " \t ");
            disabled[2] = graph.requiredNameConfirmDisabledForTest(investigation);
            disabled[3] = graph.requiredNameConfirmDisabledForTest(snapshot);
            text[4] = graph.requiredNameErrorForTest(investigation);
            text[5] = graph.requiredNameErrorForTest(snapshot);
            assertFalse(graph.commitCreateInvestigationForTest("   "));
            assertFalse(graph.commitCaptureSnapshotForTest(""));
            assertFalse(graph.commitCaptureSnapshotForTest(null));
        });

        assertEquals("Create Investigation", dialogs[0].getTitle());
        assertEquals("", text[0]);
        assertEquals("Enter investigation name", text[1]);
        assertTrue(disabled[0]);
        assertTrue(disabled[2]);
        assertEquals("Investigation Name must not be empty.", text[4]);
        assertTrue(dialogs[0].getDialogPane().getButtonTypes().stream().anyMatch(b -> "Create".equals(b.getText())));
        assertTrue(dialogs[0].getDialogPane().getButtonTypes().contains(ButtonType.CANCEL));

        assertEquals("Capture Snapshot", dialogs[1].getTitle());
        assertEquals("", text[2]);
        assertEquals("Enter snapshot name", text[3]);
        assertTrue(disabled[1]);
        assertTrue(disabled[3]);
        assertEquals("Snapshot Name must not be empty.", text[5]);
        assertTrue(dialogs[1].getDialogPane().getButtonTypes().stream().anyMatch(b -> "Capture".equals(b.getText())));
        assertTrue(menu.contains("Create Investigation"));
        assertTrue(menu.contains("Capture Snapshot"));
        assertEquals(0, investigations().size());

        JavaFxTestSupport.runOnFx("create the named investigation and two snapshots", () -> {
            assertTrue(graph.commitCreateInvestigationForTest("  Coast brake  "));
            assertTrue(graph.commitCaptureSnapshotForTest("  Marker  "));
            assertTrue(graph.commitCaptureSnapshotForTest("Marker"));
        });

        var named = investigations().stream().filter(inv -> "Coast brake".equals(inv.name())).findFirst().orElseThrow();
        assertEquals(VIEW_START, named.viewportStartNanos());
        assertEquals(VIEW_END, named.viewportEndNanos());
        var shots = named.evidence().stream().filter(item -> item.kind() == EvidenceKind.GRAPH_SNAPSHOT).toList();
        assertEquals(2, shots.size());
        assertEquals("Marker", shots.get(0).caption());
        assertEquals("Marker", shots.get(1).caption());
        assertFalse(shots.get(0).referenceId().equals(shots.get(1).referenceId()));
        Path firstPng = snapshotFile(named.investigationId(), shots.get(0).referenceId());
        Path secondPng = snapshotFile(named.investigationId(), shots.get(1).referenceId());
        assertTrue(Files.isRegularFile(firstPng));
        assertTrue(Files.isRegularFile(secondPng));
        assertTrue(Files.size(firstPng) > 0);
        assertFalse(firstPng.equals(secondPng));
        String meta = Files.readString(firstPng.resolveSibling(shots.get(0).referenceId() + ".json"));
        assertTrue(meta.contains("\"rangeStartNanos\":" + VIEW_START));
        assertTrue(meta.contains("\"rangeEndNanos\":" + VIEW_END));

        JavaFxTestSupport.runOnFx("save and reopen", () -> {
            assertTrue(service.saveProject().saved());
            String projectId = service.currentProjectId();
            service.closeProject();
            assertTrue(service.openSavedProject(projectId).opened());
            graph.setAppService(service, null);
        });

        var reopened = investigations().stream().filter(inv -> "Coast brake".equals(inv.name())).findFirst().orElseThrow();
        var reopenedShots = reopened.evidence().stream()
                .filter(item -> item.kind() == EvidenceKind.GRAPH_SNAPSHOT).toList();
        assertEquals(2, reopenedShots.size());
        assertEquals(List.of("Marker", "Marker"), reopenedShots.stream().map(item -> item.caption()).toList());
        assertTrue(Files.isRegularFile(snapshotFile(reopened.investigationId(), reopenedShots.get(0).referenceId())));
        assertTrue(Files.isRegularFile(snapshotFile(reopened.investigationId(), reopenedShots.get(1).referenceId())));

        var legacy = service.createInvestigationFromCurrentGraph();
        assertEquals("Investigation from Graph", legacy.name());
        assertEquals(2, investigations().size());
    }

    private List<com.stellar.platform.analysis.investigation.EngineeringInvestigation> investigations() {
        return service.engineeringInvestigations().list(service.metadataStore(), service.currentProjectId());
    }

    private Path snapshotFile(String investigationId, String snapshotId) {
        return InvestigationStoragePaths.snapshot(
                AnalysisStorageRoots.projectArtifactsRoot(service.currentProjectId()),
                investigationId, snapshotId);
    }

    private void openGraph() throws Exception {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("meta.db").toString());
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        service.newProject("Named Evidence");
        JavaFxTestSupport.runOnFx("load graph", () -> {
            FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
            Parent root = loader.load();
            graph = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, 1366, 768));
            stage.show();
            graph.setAppService(service, null);
        });
        JavaFxTestSupport.drainFxPulse();
    }
}
