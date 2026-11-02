package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.desktop.ui.GraphController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.shape.Polyline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphWorkspaceUiTest {

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(40)
    void graphViewExposesActiveSignalsToolbarInspectorCursorsEmptyStateAndFullScreen() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
                Parent root = loader.load();
                assertNotNull(root);
                assertNotNull(loader.getController());
                var ns = loader.getNamespace();
                for (String id : List.of(
                        "signalPanel", "signalList", "signalSearch", "explorerFilterRow",
                        "filterAllButton", "filterSelectedButton", "filterDbcButton", "filterNativeButton",
                        "filterRecommendedButton",
                        "signalPanelHeader", "collapseSignalPanelButton",
                        "selectionActionsButton", "visibilityActionsButton", "orderActionsButton",
                        "resetViewButton", "clearViewButton",
                        "moveUpItem", "moveDownItem", "visibleCountLabel", "graphCanvas", "analysisTabs",
                        "emptyStateBox", "inspectorPanel", "inspectorRail", "inspectorRailButton",
                        "inspectorHeaderToggleButton", "detailsInspector", "notesArea", "summaryStatsTable", "cursorCompareTable",
                        "cursorALabel", "cursorBLabel", "deltaLabel", "focusGraphButton", "snapshotButton",
                        "stackedLayoutButton",
                        "overlayLayoutButton", "graphMainRow", "toolbarMoreButton", "addEventButton", "eventsList",
                        "editEventButton", "deleteEventButton")) {
                    assertNotNull(ns.get(id), "missing fx:id " + id);
                }
                assertTrue(((Button) ns.get("editEventButton")).isDisabled());
                assertTrue(((Button) ns.get("deleteEventButton")).isDisabled());
                assertTrue(ns.get("addSignalsButton") == null, "Add Signals popup launcher must be gone");
                assertTrue(ns.get("detailsToggleButton") == null, "Plot Settings button must be gone");
                assertTrue(ns.get("bottomWorkspace") == null, "Plot Settings panel must be gone");
                assertNotNull(ns.get("addSignalsToGraphButton"));
                assertNotNull(ns.get("graphEmptyTitle"));
                assertEquals("Graphs & Analysis", ((javafx.scene.control.Label) ns.get("graphEmptyTitle")).getText());
                assertEquals("No signals plotted yet.", ((javafx.scene.control.Label) ns.get("graphEmptyBody")).getText());
                assertEquals("Add Signals to Graph",
                        ((javafx.scene.control.Button) ns.get("addSignalsToGraphButton")).getText());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @Test
    @Timeout(40)
    void oneInspectorTogglePointsTowardThePanelAndKeepsGraphState() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
                Parent root = loader.load();
                var ns = loader.getNamespace();
                assertNull(ns.get("inspectorToggleButton"), "toolbar Inspector toggle must be gone");
                HBox strip = (HBox) ns.get("graphFileStrip");
                int viewport = indexOfLabel(strip, "Current viewport");
                assertTrue(viewport >= 0);
                assertTrue(strip.getChildren().get(viewport + 1) instanceof Button camera
                        && camera == ns.get("snapshotButton"),
                        "camera button must follow Current viewport with no empty slot");

                Button header = (Button) ns.get("inspectorHeaderToggleButton");
                Pane rail = (Pane) ns.get("inspectorRail");
                Button railButton = (Button) ns.get("inspectorRailButton");
                Pane panel = (Pane) ns.get("inspectorPanel");
                Region split = (Region) ns.get("graphWorkspaceSplit");
                AutomotiveSignalCanvas canvas = (AutomotiveSignalCanvas) ns.get("graphCanvas");
                ToggleButton overlay = (ToggleButton) ns.get("overlayLayoutButton");
                ToggleButton stacked = (ToggleButton) ns.get("stackedLayoutButton");
                ToggleButton fileFocus = (ToggleButton) ns.get("fileFocusToggle");
                ComboBox<?> measurement = (ComboBox<?>) ns.get("measurementCombo");
                Label range = (Label) ns.get("rangeLabel");
                Label cursorA = (Label) ns.get("cursorALabel");
                Label cursorB = (Label) ns.get("cursorBLabel");
                Label selection = (Label) ns.get("selectionLabel");
                new Scene(root, 1280, 720);
                root.applyCss();
                root.layout();

                assertTrue(panel.isVisible());
                assertFalse(rail.isVisible());
                assertTrue(pointsRight(header), "open Inspector uses a right arrow");
                overlay.fire();
                fileFocus.fire();
                Object measurementValue = measurement.getValue();
                String rangeText = range.getText();
                String cursorAText = cursorA.getText();
                String cursorBText = cursorB.getText();
                String selectionText = selection.getText();
                double openSplit = split.getWidth();

                header.fire();
                root.applyCss();
                root.layout();
                assertFalse(panel.isManaged());
                assertFalse(panel.isVisible());
                assertTrue(rail.isVisible());
                assertTrue(rail.isManaged());
                assertTrue(pointsLeft(railButton), "collapsed Inspector uses a left arrow at the right edge");
                assertTrue(split.getWidth() > openSplit + 100, "graph column must grow when Inspector closes");
                assertOverlayFocusHeld(canvas, overlay, fileFocus, measurement, measurementValue,
                        range, rangeText, cursorA, cursorAText, cursorB, cursorBText, selection, selectionText);

                railButton.fire();
                root.applyCss();
                root.layout();
                assertTrue(panel.isManaged());
                assertTrue(panel.isVisible());
                assertFalse(rail.isVisible());
                assertTrue(pointsRight(header));
                assertOverlayFocusHeld(canvas, overlay, fileFocus, measurement, measurementValue,
                        range, rangeText, cursorA, cursorAText, cursorB, cursorBText, selection, selectionText);

                stacked.fire();
                fileFocus.fire();
                header.fire();
                root.applyCss();
                root.layout();
                assertFalse(panel.isManaged());
                assertTrue(pointsLeft(railButton));
                assertEquals(AutomotiveSignalCanvas.LayoutMode.STACKED, canvas.getLayoutMode());
                assertTrue(stacked.isSelected());
                assertFalse(fileFocus.isSelected());
                assertEquals(rangeText, range.getText());
                assertEquals(cursorAText, cursorA.getText());
                assertEquals(cursorBText, cursorB.getText());
                railButton.fire();
                assertTrue(panel.isManaged());
                assertTrue(pointsRight(header));
                assertEquals(AutomotiveSignalCanvas.LayoutMode.STACKED, canvas.getLayoutMode());
                assertEquals(rangeText, range.getText());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static void assertOverlayFocusHeld(
            AutomotiveSignalCanvas canvas,
            ToggleButton overlay,
            ToggleButton fileFocus,
            ComboBox<?> measurement,
            Object measurementValue,
            Label range,
            String rangeText,
            Label cursorA,
            String cursorAText,
            Label cursorB,
            String cursorBText,
            Label selection,
            String selectionText
    ) {
        assertEquals(AutomotiveSignalCanvas.LayoutMode.OVERLAY_MULTI_AXIS, canvas.getLayoutMode());
        assertTrue(overlay.isSelected());
        assertTrue(fileFocus.isSelected());
        assertEquals(measurementValue, measurement.getValue());
        assertEquals(rangeText, range.getText());
        assertEquals(cursorAText, cursorA.getText());
        assertEquals(cursorBText, cursorB.getText());
        assertEquals(selectionText, selection.getText());
    }

    private static int indexOfLabel(HBox strip, String text) {
        for (int i = 0; i < strip.getChildren().size(); i++) {
            if (strip.getChildren().get(i) instanceof Label label && text.equals(label.getText())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean pointsRight(Button button) {
        return chevronMiddleX(button) > chevronEndX(button);
    }

    private static boolean pointsLeft(Button button) {
        return chevronMiddleX(button) < chevronEndX(button);
    }

    private static double chevronMiddleX(Button button) {
        return polyline(button).getPoints().get(2);
    }

    private static double chevronEndX(Button button) {
        return polyline(button).getPoints().get(0);
    }

    private static Polyline polyline(Button button) {
        Pane host = (Pane) button.getGraphic();
        return (Polyline) host.getChildren().getFirst();
    }
}
