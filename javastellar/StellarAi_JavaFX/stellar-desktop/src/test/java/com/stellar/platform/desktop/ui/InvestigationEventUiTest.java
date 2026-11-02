package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.InvestigationEventCodec;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.map.MapTimeFormat;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Inspector Events list, edit dialog, delete confirmation, and waveform markers. */
class InvestigationEventUiTest {

    private static final long GENERATED_TIME = 1_000_000_000L;
    private static final long LOOK_TIME = 8_411_000_000L;
    private static final long ISSUE_TIME = 595_092_000_000L;

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
    void editAndDeleteUpdateTheInspectorListAndWaveformMarkers() throws Exception {
        openGraph();
        service.persistInvestigationEvent("Look", "Observation", "", LOOK_TIME, "EngineRPM");
        service.persistInvestigationEvent("Brake noise", "Issue", "pad", ISSUE_TIME, "VehicleSpeed");
        service.persistEngineeringBookmark("VehicleSpeed", "Transition", "Large relative step", GENERATED_TIME);
        String issueId = service.listEngineeringBookmarks().stream()
                .filter(b -> "Brake noise".equals(InvestigationEventCodec.titleOf(b.observation())))
                .findFirst().orElseThrow().bookmarkId();
        String lookId = service.listEngineeringBookmarks().stream()
                .filter(b -> "Look".equals(InvestigationEventCodec.titleOf(b.observation())))
                .findFirst().orElseThrow().bookmarkId();

        JavaFxTestSupport.runOnFx("reload events", () -> graph.reloadEventsForTest());
        assertEquals(3, graph.eventRowsForTest().size());
        assertTrue(graph.eventRowsForTest().get(1).contains("Look"));
        assertTrue(graph.eventRowsForTest().get(1).contains("Observation"));
        assertEquals(java.util.List.of("Large relative step", "Look", "Brake noise"), graph.overlayLabelsForTest());
        assertEquals(java.util.List.of(GENERATED_TIME, LOOK_TIME, ISSUE_TIME), graph.overlayTimesForTest());

        JavaFxTestSupport.runOnFx("no selection disables actions", () -> {
            assertFalse(graph.editEventEnabledForTest());
            assertFalse(graph.deleteEventEnabledForTest());
            graph.selectEventIndexForTest(0);
            assertFalse(graph.editEventEnabledForTest());
            assertFalse(graph.deleteEventEnabledForTest());
            assertNull(graph.editDialogForTest(0));
            assertNull(graph.deleteConfirmationForTest(0));
        });

        String[] form = new String[4];
        String[] confirmation = new String[2];
        JavaFxTestSupport.runOnFx("edit dialog is prefilled", () -> {
            graph.selectEventIndexForTest(2);
            assertTrue(graph.editEventEnabledForTest());
            assertTrue(graph.deleteEventEnabledForTest());
            assertEquals(issueId, graph.selectedEventIdForTest());
            var dialog = graph.editDialogForTest(2);
            assertNotNull(dialog);
            assertEquals("Edit Event", dialog.getTitle());
            assertTrue(dialog.getDialogPane().getButtonTypes().contains(ButtonType.CANCEL));
            String[] values = graph.eventFormValuesForTest(dialog);
            form[0] = values[0];
            form[1] = values[1];
            form[2] = values[2];
            form[3] = values[3];
            Alert alert = graph.deleteConfirmationForTest(2);
            assertNotNull(alert);
            confirmation[0] = alert.getContentText();
            confirmation[1] = alert.getButtonTypes().stream().map(ButtonType::getText).reduce((a, b) -> a + "|" + b).orElse("");
            assertTrue(alert.getButtonTypes().stream().anyMatch(b -> b.getButtonData() == ButtonBar.ButtonData.OK_DONE));
            assertTrue(alert.getButtonTypes().contains(ButtonType.CANCEL));
        });
        assertEquals("Brake noise", form[0]);
        assertEquals("Issue", form[1]);
        assertEquals("pad", form[2]);
        assertEquals(MapTimeFormat.withMillis(ISSUE_TIME), form[3]);
        assertTrue(confirmation[0].contains("Brake noise"));
        assertTrue(confirmation[0].contains("Issue"));
        assertTrue(confirmation[0].contains(MapTimeFormat.withMillis(ISSUE_TIME)));
        assertTrue(confirmation[0].contains("deleted from the project"));
        assertTrue(confirmation[1].contains("Delete"));
        assertTrue(confirmation[1].contains("Cancel"));

        JavaFxTestSupport.runOnFx("cancel leaves the event", () ->
                assertFalse(graph.commitEventEditForTest(issueId, false, "Should not save", "Braking", "no")));
        assertEquals("Brake noise", InvestigationEventCodec.titleOf(
                service.findEngineeringBookmark(issueId).orElseThrow().observation()));
        assertEquals(3, service.listEngineeringBookmarks().size());

        JavaFxTestSupport.runOnFx("invalid category is rejected", () ->
                assertFalse(graph.commitEventEditForTest(issueId, true, "Nope", "Transition", "no")));
        assertEquals("Brake noise", InvestigationEventCodec.titleOf(
                service.findEngineeringBookmark(issueId).orElseThrow().observation()));

        JavaFxTestSupport.runOnFx("edit updates the selected marker", () ->
                assertTrue(graph.commitEventEditForTest(issueId, true, "Edited brake", "Braking", "updated note")));
        assertEquals(issueId, service.findEngineeringBookmark(issueId).orElseThrow().bookmarkId());
        assertEquals(ISSUE_TIME, service.findEngineeringBookmark(issueId).orElseThrow().timestampNanos());
        assertEquals("VehicleSpeed", service.findEngineeringBookmark(issueId).orElseThrow().signalName());
        assertEquals(3, service.listEngineeringBookmarks().size());
        assertTrue(graph.eventRowsForTest().stream().anyMatch(row -> row.contains("Edited brake") && row.contains("Braking")));
        assertTrue(graph.eventRowsForTest().stream().anyMatch(row -> row.contains("Look")));
        assertEquals(java.util.List.of("Large relative step", "Look", "Edited brake"), graph.overlayLabelsForTest());
        assertEquals(ISSUE_TIME, graph.overlayTimesForTest().get(2));

        JavaFxTestSupport.runOnFx("stale selection does not delete the remaining event", () -> {
            assertTrue(service.deleteInvestigationEvent(lookId));
            assertFalse(graph.commitDeleteForTest(lookId));
        });
        assertTrue(service.findEngineeringBookmark(issueId).isPresent());
        assertTrue(service.findEngineeringBookmark(lookId).isEmpty());

        JavaFxTestSupport.runOnFx("delete removes only the selected marker", () -> {
            graph.reloadEventsForTest();
            graph.selectEventIndexForTest(1);
            assertEquals(issueId, graph.selectedEventIdForTest());
            assertTrue(graph.commitDeleteForTest(issueId));
        });
        assertTrue(service.findEngineeringBookmark(issueId).isEmpty());
        assertEquals(1, service.listEngineeringBookmarks().size());
        assertEquals("Transition", service.listEngineeringBookmarks().getFirst().eventType());
        assertEquals(java.util.List.of("Large relative step"), graph.overlayLabelsForTest());
        assertFalse(graph.editEventEnabledForTest());
        assertFalse(graph.deleteEventEnabledForTest());

        JavaFxTestSupport.runOnFx("reopen shows the remaining event", () -> {
            assertTrue(service.saveProject().saved());
            String projectId = service.currentProjectId();
            service.closeProject();
            assertTrue(service.openSavedProject(projectId).opened());
            graph.setAppService(service, null);
            graph.reloadEventsForTest();
        });
        assertEquals(1, graph.eventRowsForTest().size());
        assertTrue(graph.eventRowsForTest().getFirst().contains("Large relative step"));
        assertEquals(java.util.List.of(GENERATED_TIME), graph.overlayTimesForTest());
    }

    @Test
    @Timeout(60)
    void addEventStillAppearsInTheInspectorAndOnTheWaveform() throws Exception {
        openGraph();
        JavaFxTestSupport.runOnFx("add through the same persistence the button uses", () -> {
            long addButtons = stage.getScene().getRoot().lookupAll(".button").stream()
                    .filter(node -> node instanceof Button button && "Add Event".equals(button.getText()) && !button.isDisabled())
                    .count();
            assertEquals(1, addButtons);
            service.persistInvestigationEvent("New observation", "Observation", "note", LOOK_TIME, "EngineRPM");
            graph.reloadEventsForTest();
        });
        assertEquals(1, service.listEngineeringBookmarks().size());
        assertTrue(graph.eventRowsForTest().getFirst().contains("New observation"));
        assertTrue(graph.eventRowsForTest().getFirst().contains("Observation"));
        assertEquals(java.util.List.of("New observation"), graph.overlayLabelsForTest());
        assertEquals(java.util.List.of(LOOK_TIME), graph.overlayTimesForTest());
        JavaFxTestSupport.runOnFx("selecting the new event enables edit and delete", () -> {
            graph.selectEventIndexForTest(0);
            assertTrue(graph.editEventEnabledForTest());
            assertTrue(graph.deleteEventEnabledForTest());
        });
    }

    private void openGraph() throws Exception {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("meta.db").toString());
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        service.newProject("Inspector Events");
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
