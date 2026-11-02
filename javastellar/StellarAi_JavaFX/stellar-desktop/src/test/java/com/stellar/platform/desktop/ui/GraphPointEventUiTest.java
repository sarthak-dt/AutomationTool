package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.EngineerEventDocuments;
import com.stellar.platform.desktop.presentation.InvestigationEventCodec;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.AutomotiveSignalCanvas;
import com.stellar.platform.desktop.ui.graph.GraphChrome;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.processing.graph.MinMaxLodReducer;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact-sample point events, plus markers, Inspector overflow, and the disabled Engineering tab.
 */
class GraphPointEventUiTest {

    private static final long EARLY = 100_000_000L;
    private static final long LATE = 300_000_000L;
    private static final long BETWEEN = 200_000_000L;
    private static final long VIEW_END = 1_000_000_000L;
    private static final long HVBAT_VIEW = 7_000_000_000L;
    private static final long HVBAT_CLICK = 4_000_000_000L;

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
        JavaFxTestSupport.runOnFx("dispose", () -> {
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
    }

    @Test
    @Timeout(60)
    void plusSitsOnTheSampleAndNotesToggleWithoutGuideLines() throws Exception {
        AtomicStage holder = new AtomicStage();
        JavaFxTestSupport.runOnFx("markers", () -> {
            AutomotiveSignalCanvas canvas = new AutomotiveSignalCanvas();
            canvas.setMinSize(900, 520);
            canvas.setPrefSize(900, 520);
            Stage window = new Stage();
            Scene scene = new Scene(canvas, 900, 520);
            scene.getStylesheets().add(GraphController.class.getResource("/css/stellar-desktop.css").toExternalForm());
            window.setScene(scene);
            window.show();
            holder.window = window;
            canvas.resize(900, 520);
            canvas.prepareSharedViewport(0L, VIEW_END);
            SignalCatalogKey torque = key();
            canvas.setSeries(List.of(series(torque)));
            canvas.setAnalysisOverlays(List.of(
                    AutomotiveSignalCanvas.AnalysisOverlay.point(
                            EARLY, "early", "Brake", "Torque",
                            "Pad touched the disc and the note is long enough to wrap onto a second line.",
                            "10 Nm", "0.100 s", 10d, false),
                    AutomotiveSignalCanvas.AnalysisOverlay.point(
                            LATE, "late", "Release", "Torque", "Pedal up", "40 Nm", "0.300 s", 40d, false)));
            canvas.layout();
            canvas.flushDeferredPaintForTest();
            canvas.flushPointMarkersForTest();
            List<Button> markers = markers(canvas);
            assertEquals(2, markers.size(), markers.toString());
            Button early = marker(markers, "Brake");
            Button late = marker(markers, "Release");
            javafx.geometry.Point2D expected = canvas.pointMarkerLocation("Torque", EARLY, 10d);
            assertNotNull(expected);
            assertEquals(expected.getX(), early.getLayoutX() + 9, 1.5);
            assertEquals(expected.getY(), early.getLayoutY() + 9, 1.5);
            assertTrue(early.getTooltip().getText().contains("Brake"));
            assertTrue(early.getTooltip().isWrapText());
            java.util.concurrent.atomic.AtomicReference<String> cursor = new java.util.concurrent.atomic.AtomicReference<>("");
            canvas.setInteractionHandlers(zoom -> {}, (from, to) -> {}, (which, time) -> cursor.set(which));
            javafx.geometry.Point2D plus = new javafx.geometry.Point2D(early.getLayoutX() + 9, early.getLayoutY() + 9);
            canvas.fireEvent(mouse(canvas, MouseEvent.MOUSE_PRESSED, plus));
            canvas.fireEvent(mouse(canvas, MouseEvent.MOUSE_RELEASED, plus));
            assertEquals("", cursor.get(), "marker click moved the selection");
            Label clicked = note(canvas);
            assertNotNull(clicked);
            assertTrue(clicked.isVisible());
            assertTrue(clicked.getHeight() > 20, "note height " + clicked.getHeight());
            save(clicked.snapshot(null, null), "note-open.png");
            canvas.fireEvent(mouse(canvas, MouseEvent.MOUSE_PRESSED, plus));
            canvas.fireEvent(mouse(canvas, MouseEvent.MOUSE_RELEASED, plus));
            assertEquals(0, noteCount(canvas));
            save(canvas.snapshot(null, null), "note-collapsed.png");
            canvas.fireEvent(new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                    javafx.scene.input.KeyCode.ESCAPE, false, false, false, false));
            early.fire();
            Label note = note(canvas);
            assertNotNull(note);
            assertTrue(note.isWrapText());
            assertTrue(note.getText().contains("Pad touched the disc"));
            assertTrue(note.getText().contains("10 Nm"));
            note.applyCss();
            note.autosize();
            assertTrue(note.prefHeight(-1) > 30, "note height " + note.prefHeight(-1));
            late.fire();
            assertEquals(2, noteCount(canvas));
            canvas.applyCss();
            canvas.layout();
            save(canvas.snapshot(null, null), "markers-stacked.png");
            early.fire();
            assertEquals(1, noteCount(canvas));
            late.fireEvent(new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                    javafx.scene.input.KeyCode.ESCAPE, false, false, false, false));
            assertEquals(0, noteCount(canvas));

            WritableImage image = canvas.snapshot(null, null);
            assertFalse(verticalGuide(image, (int) expected.getX(), GraphChrome.ANALYSIS_EVENT), "vertical event line");
            assertFalse(horizontalGuide(image, (int) expected.getY(), GraphChrome.ANALYSIS_EVENT), "horizontal event line");

            canvas.setLayoutMode(AutomotiveSignalCanvas.LayoutMode.OVERLAY_MULTI_AXIS);
            canvas.flushDeferredPaintForTest();
            canvas.flushPointMarkersForTest();
            javafx.geometry.Point2D overlay = canvas.pointMarkerLocation("Torque", EARLY, 10d);
            assertNotNull(overlay);
            Button moved = marker(markers(canvas), "Brake");
            assertEquals(overlay.getY(), moved.getLayoutY() + 9, 1.5);
            save(canvas.snapshot(null, null), "markers-overlay.png");

            canvas.setLayoutMode(AutomotiveSignalCanvas.LayoutMode.STACKED);
            double beforeZoom = marker(markers(canvas), "Brake").getLayoutX();
            canvas.prepareSharedViewport(EARLY, LATE);
            canvas.setShowGrid(true);
            canvas.flushDeferredPaintForTest();
            canvas.flushPointMarkersForTest();
            Button zoomed = marker(markers(canvas), "Brake");
            assertTrue(Math.abs(zoomed.getLayoutX() - beforeZoom) > 20, "zoom did not move the marker");
            javafx.geometry.Point2D zoomedPoint = canvas.pointMarkerLocation("Torque", EARLY, 10d);
            assertNotNull(zoomedPoint);
            assertEquals(zoomedPoint.getX(), zoomed.getLayoutX() + 9, 1.5);
            window.close();
        });
    }

    @Test
    @Timeout(90)
    void pointEventStoresTheSampleAndInspectorStaysReachable() throws Exception {
        openGraph("Point events");
        SignalCatalogKey torque = key();
        String note = "Exact sample note that must wrap instead of being clipped in the marker.";
        JavaFxTestSupport.runOnFx("save point and window", () -> {
            assertTrue(graph.engineeringTabDisabledForTest());
            TabPane tabs = (TabPane) stage.getScene().getRoot().lookup(".graph-analysis-tabs");
            int before = graph.analysisTabIndexForTest();
            Label engineering = null;
            for (Node node : tabs.lookupAll(".tab-label")) {
                if (node instanceof Label label && "Engineering".equals(label.getText())) {
                    engineering = label;
                }
            }
            assertNotNull(engineering);
            engineering.fireEvent(click(engineering));
            assertEquals(before, graph.analysisTabIndexForTest());
            tabs.fireEvent(new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                    javafx.scene.input.KeyCode.RIGHT, false, false, false, false));
            assertFalse(graph.engineeringTabDisabledForTest() && graph.analysisTabIndexForTest() == 2);

            graph.installPlottedSeriesForTest(List.of(series(torque)), 0L, VIEW_END);
            graph.pinEventSignalsForTest(List.of(torque));
            graph.selectCursorAForTest(BETWEEN);
            Dialog<?> dialog = graph.addEventDialogForTest();
            assertTrue(dialogText(dialog).contains(EARLY + " ns"), dialogText(dialog));
            assertTrue(dialogText(dialog).contains("10 Nm"), dialogText(dialog));
            fill(dialog, "Torque point", "Observation", note, false);
            assertFalse(graph.commitAddEventForTest(dialog).isBlank());

            graph.selectCursorAForTest(EARLY);
            graph.selectCursorBForTest(LATE);
            Dialog<?> window = graph.addEventDialogForTest();
            fill(window, "Torque window", "Observation", "from A to B", true);
            assertFalse(graph.commitAddEventForTest(window).isBlank());
            graph.flushPointMarkersForTest();
            assertEquals(1, graph.pointMarkerNodesForTest().stream().filter(node -> node instanceof Button).count());
            javafx.scene.control.TabPane inspectorTabs = (javafx.scene.control.TabPane) stage.getScene().getRoot().lookup(".graph-details-tabs");
            for (javafx.scene.control.Tab tab : inspectorTabs.getTabs()) {
                if ("Events".equals(tab.getText())) {
                    inspectorTabs.getSelectionModel().select(tab);
                }
            }
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            save(stage.getScene().getRoot().snapshot(null, null), "graph-point-event.png");

            graph.layoutEventActionsForTest(220);
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            MenuButton more = graph.eventActionMoreForTest();
            assertTrue(more.isVisible());
            assertTrue(more.getItems().stream().map(MenuItem::getText).anyMatch(text -> text.equals("Link Snapshot") || text.equals("Delete")));
            for (Node node : ((Parent) more.getParent()).lookupAll(".button")) {
                if (node instanceof Button button && button.isVisible() && button.getText() != null) {
                    assertFalse(button.getText().contains("…"), button.getText());
                    assertTrue(button.getWidth() + 1 >= button.prefWidth(-1) || button.getWidth() == 0,
                            button.getText() + " width " + button.getWidth());
                }
            }
            save(stage.getScene().getRoot().snapshot(null, null), "inspector-narrow.png");
            graph.layoutEventActionsForTest(720);
            assertFalse(graph.eventActionMoreForTest().isManaged());
            save(stage.getScene().getRoot().snapshot(null, null), "inspector-wide.png");
        });
        var point = service.listEngineeringBookmarks().stream()
                .filter(bookmark -> "Torque point".equals(InvestigationEventCodec.titleOf(bookmark.observation())))
                .findFirst().orElseThrow();
        var window = service.listEngineeringBookmarks().stream()
                .filter(bookmark -> "Torque window".equals(InvestigationEventCodec.titleOf(bookmark.observation())))
                .findFirst().orElseThrow();
        assertEquals(EARLY, point.timestampNanos());
        assertEquals(10d, EngineerEventDocuments.sampleNumber(point));
        assertEquals("10 Nm", EngineerEventDocuments.sampleText(point));
        assertTrue(InvestigationEventCodec.noteOf(point.observation()).contains("Exact sample note"));
        assertEquals(null, EngineerEventDocuments.endNanos(point));
        assertEquals(EARLY, window.timestampNanos());
        assertEquals(LATE, EngineerEventDocuments.endNanos(window));
        String projectId = service.currentProjectId();
        service.closeProject();
        service.newProject("Other");
        assertTrue(service.openSavedProject(projectId).opened());
        assertEquals(EARLY, service.findEngineeringBookmark(point.bookmarkId()).orElseThrow().timestampNanos());
        assertEquals(LATE, EngineerEventDocuments.endNanos(
                service.findEngineeringBookmark(window.bookmarkId()).orElseThrow()));
    }

    @Test
    @Timeout(90)
    void hvbatStateClickShowsThePlusOnTheWaveform() throws Exception {
        openGraph("HVBAT_STATE");
        String note = "Discharge held and the note is long enough to wrap on the waveform.";
        JavaFxTestSupport.runOnFx("click HVBAT_STATE and add the event", () -> {
            AutomotiveSignalCanvas canvas = graph.canvasForTest();
            canvas.resize(900, 520);
            canvas.layout();
            graph.installPlottedSeriesForTest(List.of(hvbatSeries()), 0L, HVBAT_VIEW);
            graph.pinEventSignalsForTest(List.of(hvbatKey()));
            canvas.prepareSharedViewport(0L, HVBAT_VIEW);
            canvas.flushDeferredPaintForTest();
            javafx.geometry.Point2D at = canvas.pointMarkerLocation("HVBAT_STATE", HVBAT_CLICK, 5d);
            assertNotNull(at, "categorical sample has no plotted position plotted="
                    + canvas.hasPlottedSeries()
                    + " label=" + canvas.stateLabelAt("HVBAT_STATE", HVBAT_CLICK)
                    + " size=" + canvas.getWidth() + "x" + canvas.getHeight());
            canvas.fireEvent(mouse(canvas, MouseEvent.MOUSE_PRESSED, at));
            canvas.fireEvent(mouse(canvas, MouseEvent.MOUSE_RELEASED, at));
            assertEquals(1, stage.getScene().getRoot().lookupAll(".button").stream()
                    .filter(node -> node instanceof Button button && "Add Event".equals(button.getText()))
                    .count());
            Button add = toolbarAddEvent();
            assertNotNull(add);
            assertTrue(add.getTooltip().getText().contains("Shift+click"));
            javafx.application.Platform.runLater(() -> completeAddEventDialog("Discharge point", note));
            add.fire();
            graph.installPlottedSeriesForTest(List.of(hvbatSeries()), 0L, HVBAT_VIEW);
            canvas.prepareSharedViewport(0L, HVBAT_VIEW);
            graph.reloadEventsForTest();
            graph.flushPointMarkersForTest();
            var stored = service.listEngineeringBookmarks().getFirst();
            double savedValue = EngineerEventDocuments.sampleNumber(stored);
            assertTrue(savedValue == 2d || savedValue == 3d || savedValue == 5d, String.valueOf(savedValue));
            assertEquals(null, EngineerEventDocuments.endNanos(stored));
            List<Button> plus = markers(canvas);
            assertEquals(1, plus.size(), plus.toString());
            Button marker = plus.getFirst();
            assertTrue(marker.isVisible());
            assertEquals("Discharge point", marker.getTooltip().getText().lines().findFirst().orElse(""));
            javafx.geometry.Point2D expected = canvas.pointMarkerLocation("HVBAT_STATE", stored.timestampNanos(), savedValue);
            assertNotNull(expected);
            assertEquals(expected.getX(), marker.getLayoutX() + 9, 2.0);
            assertEquals(expected.getY(), marker.getLayoutY() + 9, 2.0);
            canvas.applyCss();
            canvas.layout();
            WritableImage plotted = canvas.snapshot(null, null);
            save(plotted, "hvbat-plus.png");
            assertTrue(tealCluster(plotted, (int) expected.getX(), (int) expected.getY()), "plus is not visible on the waveform");
            assertFalse(orangeCursor(plotted, (int) expected.getX()), "selected-sample line stayed after the point event");
            assertNull(graph.cursorAForTest());
            assertNull(graph.cursorBForTest());
            assertFalse(verticalGuide(plotted, (int) expected.getX(), GraphChrome.ANALYSIS_EVENT), "event vertical line");
            assertFalse(horizontalGuide(plotted, (int) expected.getY(), GraphChrome.ANALYSIS_EVENT), "event horizontal line");
            javafx.geometry.Point2D plusAt = new javafx.geometry.Point2D(9, 9);
            marker.fireEvent(mouse(marker, MouseEvent.MOUSE_PRESSED, plusAt));
            marker.fireEvent(mouse(marker, MouseEvent.MOUSE_RELEASED, plusAt));
            assertNull(graph.cursorAForTest(), "marker click selected a time");
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            Label open = note(canvas);
            assertNotNull(open);
            assertTrue(open.isVisible());
            assertTrue(open.isWrapText());
            assertTrue(open.getText().contains("Discharge"));
            assertTrue(open.getText().contains(note));
            assertTrue(open.getHeight() > 20, "note height " + open.getHeight());
            save(open.snapshot(null, null), "hvbat-note-card.png");
            save(stage.getScene().getRoot().snapshot(null, null), "hvbat-note.png");
            marker.fireEvent(mouse(marker, MouseEvent.MOUSE_PRESSED, plusAt));
            marker.fireEvent(mouse(marker, MouseEvent.MOUSE_RELEASED, plusAt));
            assertEquals(0, noteCount(canvas));
            save(stage.getScene().getRoot().snapshot(null, null), "hvbat-note-collapsed.png");

            graph.selectCursorAForTest(stored.timestampNanos());
            graph.selectCursorBForTest(stored.timestampNanos() + 2_000_000_000L);
            assertNotNull(graph.cursorAForTest());
            assertNotNull(graph.cursorBForTest());
            Label delta = null;
            for (Node node : stage.getScene().getRoot().lookupAll(".graph-delta")) {
                if (node instanceof Label label) {
                    delta = label;
                }
            }
            assertNotNull(delta);
            assertFalse(delta.getText().isBlank());
            assertEquals(2_000_000_000L, graph.cursorBForTest() - graph.cursorAForTest());
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            save(stage.getScene().getRoot().snapshot(null, null), "cursors-ab.png");
            Dialog<?> another = graph.addEventDialogForTest();
            fill(another, "Second point", "Observation", "cursors stay", false);
            assertFalse(graph.commitAddEventForTest(another).isBlank());
            assertEquals(stored.timestampNanos(), graph.cursorAForTest());
            assertEquals(stored.timestampNanos() + 2_000_000_000L, graph.cursorBForTest());
            graph.flushPointMarkersForTest();
            assertEquals(2, markers(canvas).size());

            double beforeZoom = marker.getLayoutX();
            long savedTime = service.listEngineeringBookmarks().getFirst().timestampNanos();
            canvas.prepareSharedViewport(savedTime - 500_000_000L, savedTime + 500_000_000L);
            canvas.setShowGrid(true);
            canvas.flushDeferredPaintForTest();
            canvas.flushPointMarkersForTest();
            Button zoomed = markers(canvas).getFirst();
            assertTrue(Math.abs(zoomed.getLayoutX() - beforeZoom) > 20, "zoom did not move the marker");
            canvas.setLayoutMode(AutomotiveSignalCanvas.LayoutMode.OVERLAY_MULTI_AXIS);
            canvas.flushDeferredPaintForTest();
            canvas.flushPointMarkersForTest();
            javafx.geometry.Point2D overlay = canvas.pointMarkerLocation("HVBAT_STATE", savedTime, savedValue);
            assertNotNull(overlay);
            assertEquals(overlay.getY(), markers(canvas).getFirst().getLayoutY() + 9, 2.0);
            canvas.setLayoutMode(AutomotiveSignalCanvas.LayoutMode.STACKED);
            canvas.flushDeferredPaintForTest();
            canvas.flushPointMarkersForTest();
        });
        var saved = service.listEngineeringBookmarks().stream()
                .filter(bookmark -> "Discharge point".equals(InvestigationEventCodec.titleOf(bookmark.observation())))
                .findFirst().orElseThrow();
        assertEquals("HVBAT_STATE", saved.signalName());
        assertEquals(null, EngineerEventDocuments.endNanos(saved));
        String savedText = EngineerEventDocuments.sampleText(saved);
        assertTrue(savedText.contains("Wait") || savedText.contains("Ready") || savedText.contains("Discharge"), savedText);
        assertTrue(InvestigationEventCodec.noteOf(saved.observation()).contains("Discharge held"));
        String projectId = service.currentProjectId();
        service.closeProject();
        service.newProject("Other");
        assertTrue(service.openSavedProject(projectId).opened());
        JavaFxTestSupport.runOnFx("restore marker", () -> {
            graph.installPlottedSeriesForTest(List.of(hvbatSeries()), 0L, HVBAT_VIEW);
            graph.reloadEventsForTest();
            graph.flushPointMarkersForTest();
            assertEquals(2, markers(graph.canvasForTest()).size());
            graph.canvasForTest().pointMarkerNodesForTest().stream()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .filter(button -> button.getTooltip() != null && button.getTooltip().getText().contains("Discharge point"))
                    .findFirst()
                    .orElseThrow()
                    .fire();
            assertTrue(note(graph.canvasForTest()).getText().contains(note));
        });
    }

    private void openGraph(String project) throws Exception {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("point-events.db").toString());
        service = new DesktopAppService(new DesktopSession(),
                new SqliteProjectMetadataStore(temp.resolve("point-events.db")));
        service.newProject(project);
        JavaFxTestSupport.runOnFx("load graph", () -> {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                    GraphController.class.getResource("/fxml/graph-view.fxml"));
            Parent root = loader.load();
            graph = loader.getController();
            stage = new Stage();
            Scene scene = new Scene(root, 1280, 760);
            scene.getStylesheets().add(GraphController.class.getResource("/css/stellar-desktop.css").toExternalForm());
            stage.setScene(scene);
            stage.show();
            graph.setAppService(service, null);
        });
        JavaFxTestSupport.drainFxPulse();
    }

    private static GraphRenderSeries series(SignalCatalogKey key) {
        GraphBucket early = new GraphBucket(EARLY, 10, EARLY, 10, EARLY, 10, EARLY, 10, 1);
        GraphBucket late = new GraphBucket(LATE, 40, LATE, 40, LATE, 40, LATE, 40, 1);
        return new GraphRenderSeries(key, "Nm", 2, true, 0L, VIEW_END, List.of(early, late));
    }

    private static GraphRenderSeries hvbatSeries() {
        java.util.ArrayList<SignalSamplePoint> samples = new java.util.ArrayList<>(6020);
        String[] labels = {"Wait", "Ready", "Discharge"};
        double[] values = {2d, 3d, 5d};
        for (int i = 0; i < 6020; i++) {
            int state = Math.min(2, i / 2000);
            samples.add(new SignalSamplePoint(i * 1_000_000L, values[state], (long) values[state], labels[state], SignalValidity.VALID));
        }
        return new GraphRenderSeries(hvbatKey(), "", 6020, false, 0L, HVBAT_VIEW,
                MinMaxLodReducer.reduce(samples, 240));
    }

    private static SignalCatalogKey hvbatKey() {
        return new SignalCatalogKey("HVBAT_STATE", SignalSourceType.DBC_CAN, "HVBAT_205h", 517, 1,
                "ASCII_eCC21.asc", "2.dbc");
    }

    private Button toolbarAddEvent() {
        Button found = null;
        for (Node node : stage.getScene().getRoot().lookupAll(".button")) {
            if (node instanceof Button button && "Add Event".equals(button.getText())
                    && button.getStyleClass().contains("graph-tool-button")) {
                found = button;
            }
        }
        return found;
    }

    private static void completeAddEventDialog(String title, String note) {
        Window[] windows = Window.getWindows().toArray(Window[]::new);
        for (Window window : windows) {
            if (!window.isShowing() || !(window instanceof Stage dialog) || !"Add Event".equals(dialog.getTitle())) {
                continue;
            }
            if (!(dialog.getScene().getRoot() instanceof DialogPane pane)) {
                continue;
            }
            for (Node node : List.copyOf(pane.lookupAll(".text-field"))) {
                if (node instanceof TextField field) {
                    field.setText(title);
                }
            }
            for (Node node : List.copyOf(pane.lookupAll(".text-area"))) {
                if (node instanceof TextArea area) {
                    area.setText(note);
                }
            }
            Node ok = pane.lookupButton(ButtonType.OK);
            if (ok instanceof Button button) {
                button.fire();
            }
            return;
        }
    }

    private static MouseEvent mouse(Node node, javafx.event.EventType<MouseEvent> type, javafx.geometry.Point2D at) {
        return new MouseEvent(node, node, type,
                at.getX(), at.getY(), at.getX(), at.getY(), MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, true, null);
    }

    private static boolean tealCluster(WritableImage image, int x, int y) {
        PixelReader reader = image.getPixelReader();
        int hits = 0;
        for (int dy = -12; dy <= 12; dy++) {
            int py = y + dy;
            if (py < 0 || py >= image.getHeight()) {
                continue;
            }
            for (int dx = -12; dx <= 12; dx++) {
                int px = x + dx;
                if (px < 0 || px >= image.getWidth()) {
                    continue;
                }
                if (near(reader.getColor(px, py), GraphChrome.ANALYSIS_EVENT)) {
                    hits++;
                }
            }
        }
        return hits >= 20;
    }

    private static boolean orangeCursor(WritableImage image, int x) {
        PixelReader reader = image.getPixelReader();
        int hits = 0;
        int from = Math.max(0, x - 2);
        int to = Math.min((int) image.getWidth() - 1, x + 2);
        for (int column = from; column <= to; column++) {
            for (int y = 0; y < image.getHeight(); y += 2) {
                Color pixel = reader.getColor(column, y);
                if (pixel.getOpacity() > 0.4
                        && pixel.getRed() > 0.75
                        && pixel.getGreen() > 0.35
                        && pixel.getGreen() < 0.85
                        && pixel.getBlue() < 0.3) {
                    hits++;
                }
            }
        }
        return hits > 8;
    }

    private static SignalCatalogKey key() {
        return new SignalCatalogKey("Torque", SignalSourceType.DBC_CAN, "Power", 1, 1, "power.asc", "power.dbc");
    }

    private static List<Button> markers(AutomotiveSignalCanvas canvas) {
        return canvas.pointMarkerNodesForTest().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .toList();
    }

    private static Button marker(List<Button> markers, String title) {
        return markers.stream()
                .filter(button -> button.getTooltip() != null && button.getTooltip().getText().contains(title))
                .findFirst()
                .orElseThrow();
    }

    private static Label note(AutomotiveSignalCanvas canvas) {
        for (Node node : canvas.pointMarkerNodesForTest()) {
            if (node instanceof Label label && label.getStyleClass().contains("graph-event-note")) {
                return label;
            }
        }
        return null;
    }

    private static int noteCount(AutomotiveSignalCanvas canvas) {
        int count = 0;
        for (Node node : canvas.pointMarkerNodesForTest()) {
            if (node instanceof Label label && label.getStyleClass().contains("graph-event-note")) {
                count++;
            }
        }
        return count;
    }

    private static boolean verticalGuide(WritableImage image, int x, Color color) {
        PixelReader reader = image.getPixelReader();
        int hits = 0;
        int column = Math.max(0, Math.min((int) image.getWidth() - 1, x));
        for (int y = 0; y < image.getHeight(); y += 4) {
            if (near(reader.getColor(column, y), color)) {
                hits++;
            }
        }
        return hits > 12;
    }

    private static boolean horizontalGuide(WritableImage image, int y, Color color) {
        PixelReader reader = image.getPixelReader();
        int hits = 0;
        int row = Math.max(0, Math.min((int) image.getHeight() - 1, y));
        for (int x = 0; x < image.getWidth(); x += 4) {
            if (near(reader.getColor(x, row), color)) {
                hits++;
            }
        }
        return hits > 20;
    }

    private static boolean near(Color pixel, Color expected) {
        return Math.abs(pixel.getRed() - expected.getRed()) < 0.08
                && Math.abs(pixel.getGreen() - expected.getGreen()) < 0.08
                && Math.abs(pixel.getBlue() - expected.getBlue()) < 0.08
                && pixel.getOpacity() > 0.4;
    }

    private static MouseEvent click(Node node) {
        return new MouseEvent(node, node, MouseEvent.MOUSE_CLICKED,
                4, 4, 4, 4, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, true, null);
    }

    private static void save(WritableImage image, String name) {
        try {
            Path dir = Path.of("target", "point-event-ui");
            Files.createDirectories(dir);
            ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(image, null), "png", dir.resolve(name).toFile());
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String dialogText(Dialog<?> dialog) {
        StringBuilder text = new StringBuilder();
        VBox body = (VBox) dialog.getDialogPane().getContent();
        for (Node node : body.getChildren()) {
            if (node instanceof Label label && label.getText() != null) {
                text.append(label.getText()).append('\n');
            }
        }
        return text.toString();
    }

    private static void fill(Dialog<?> dialog, String title, String category, String note, boolean window) {
        VBox body = (VBox) dialog.getDialogPane().getContent();
        for (Node node : body.getChildren()) {
            if (node instanceof TextField field) {
                field.setText(title);
            } else if (node instanceof TextArea area) {
                area.setText(note);
            } else if (node instanceof ComboBox<?> combo) {
                select(combo, "shape".equals(combo.getUserData()) ? (window ? "Time window" : "Point") : category);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void select(ComboBox<?> combo, String value) {
        ((ComboBox<String>) combo).getSelectionModel().select(value);
    }

    private static final class AtomicStage {
        private Stage window;
    }
}
