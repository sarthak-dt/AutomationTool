package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.support.JavaFxTestSupport;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Graph workspace layout: Inspector collapse must return space to the canvas
 * without clearing focus, tab selection, or viewport.
 */
class GraphWorkspaceLayoutTest {

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
    @Timeout(45)
    void inspectorCollapseReturnsWidthAndPreservesTab() throws Exception {
        AtomicReference<GraphController> ctrl = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
                Parent root = loader.load();
                GraphController graph = loader.getController();
                ctrl.set(graph);
                Stage stage = new Stage();
                stage.setScene(new Scene(root, 1366, 768));
                stage.show();
                stage.sizeToScene();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                loaded.countDown();
            }
        });
        assertTrue(loaded.await(20, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }

        GraphController graph = ctrl.get();
        waitFx();

        JavaFxTestSupport.runOnFx("open inspector + stats tab", () -> {
            graph.setInspectorOpenForTest(true);
            graph.selectInspectorTabForTest(1); // Statistics
        });
        waitFx();
        assertTrue(graph.inspectorManagedForTest());
        assertFalse(graph.inspectorRailManagedForTest());
        assertEquals(1, graph.inspectorTabIndexForTest());

        double widthOpen = measureCanvasWidth(graph);
        JavaFxTestSupport.runOnFx("collapse inspector", () -> graph.setInspectorOpenForTest(false));
        waitFx();
        waitFx();

        assertFalse(graph.inspectorManagedForTest());
        assertFalse(graph.inspectorVisibleForTest());
        assertTrue(graph.inspectorRailManagedForTest(), "reveal rail must appear when collapsed");
        double widthCollapsed = measureCanvasWidth(graph);
        assertTrue(widthCollapsed > widthOpen + 80,
                "Graph must gain significant width when Inspector collapses (open="
                        + widthOpen + " collapsed=" + widthCollapsed + ")");
        assertEquals(1, graph.inspectorTabIndexForTest(), "Statistics tab must remain selected");

        JavaFxTestSupport.runOnFx("expand inspector", () -> graph.setInspectorOpenForTest(true));
        waitFx();
        waitFx();
        assertTrue(graph.inspectorManagedForTest());
        assertFalse(graph.inspectorRailManagedForTest());
        assertEquals(1, graph.inspectorTabIndexForTest());
        double widthRestored = measureCanvasWidth(graph);
        assertTrue(widthRestored < widthCollapsed - 40, "Graph must shrink again when Inspector expands");
    }

    @Test
    @Timeout(45)
    void plotSettingsControlsAreAbsentAndInteractionBarStaysPut() throws Exception {
        AtomicReference<GraphController> ctrl = new AtomicReference<>();
        AtomicReference<Parent> rootRef = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
                Parent root = loader.load();
                rootRef.set(root);
                ctrl.set(loader.getController());
                Stage stage = new Stage();
                stage.setScene(new Scene(root, 1920, 1080));
                stage.show();
            } catch (Throwable ignored) {
                // reported via null controller
            } finally {
                loaded.countDown();
            }
        });
        assertTrue(loaded.await(20, TimeUnit.SECONDS));
        GraphController graph = ctrl.get();
        assertTrue(graph != null);
        waitFx();

        Parent root = rootRef.get();
        assertTrue(root.lookupAll(".graph-plot-settings-drawer").isEmpty());
        assertTrue(root.lookupAll(".graph-plot-settings-toggle").isEmpty());
        boolean plotButton = root.lookupAll(".button").stream()
                .anyMatch(node -> node instanceof javafx.scene.control.Button button
                        && button.getText() != null
                        && button.getText().contains("Plot Settings"));
        assertFalse(plotButton);
        javafx.scene.Node scroll = graph.waveformScrollForTest();
        assertTrue(scroll.getParent() instanceof javafx.scene.layout.VBox);
        javafx.scene.layout.VBox waveform = (javafx.scene.layout.VBox) scroll.getParent();
        assertEquals(3, waveform.getChildren().size(), "waveform column is file focus, canvas and interaction bar");
        javafx.scene.Node bar = waveform.getChildren().get(2);
        assertTrue(bar.getStyleClass().contains("graph-interaction-bar"));
        assertTrue(bar.lookupAll(".button").stream().anyMatch(node -> "Cursor A".equals(textOf(node))));
        assertTrue(bar.lookupAll(".button").stream().anyMatch(node -> "Cursor B".equals(textOf(node))));
        assertTrue(bar.lookupAll(".button").stream().anyMatch(node -> "Clear".equals(textOf(node))));
        javafx.geometry.Bounds scrollBounds = scroll.localToParent(scroll.getBoundsInLocal());
        javafx.geometry.Bounds barBounds = bar.localToParent(bar.getBoundsInLocal());
        assertTrue(barBounds.getHeight() > 20 && barBounds.getHeight() < 48, "interaction bar height " + barBounds.getHeight());
        assertTrue(Math.abs(scrollBounds.getMaxY() - barBounds.getMinY()) < 2,
                "no gap between the waveform and the interaction bar");
    }

    @Test
    @Timeout(45)
    void fullScreenCollapsesInspectorAndAllowsShowAgain() throws Exception {
        AtomicReference<GraphController> ctrl = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
                Parent root = loader.load();
                ctrl.set(loader.getController());
                Stage stage = new Stage();
                stage.setScene(new Scene(root, 1366, 768));
                stage.show();
            } catch (Throwable ignored) {
            } finally {
                loaded.countDown();
            }
        });
        assertTrue(loaded.await(20, TimeUnit.SECONDS));
        GraphController graph = ctrl.get();
        waitFx();

        JavaFxTestSupport.runOnFx("open inspector", () -> graph.setInspectorOpenForTest(true));
        waitFx();
        JavaFxTestSupport.runOnFx("enter FS", graph::enterFullScreenForTest);
        waitFx();
        assertTrue(graph.focusGraphForTest());
        assertFalse(graph.inspectorManagedForTest());
        assertFalse(graph.inspectorRailManagedForTest(), "Focus Mode must not leave an Inspector rail");
        assertFalse(graph.signalPanelManagedForTest(), "Focus Mode must hide the Signals explorer");

        JavaFxTestSupport.runOnFx("show in FS", graph::toggleInspectorForTest);
        waitFx();
        assertTrue(graph.focusGraphForTest());
        assertTrue(graph.inspectorManagedForTest());
        assertFalse(graph.signalPanelManagedForTest());

        JavaFxTestSupport.runOnFx("hide in FS", graph::toggleInspectorForTest);
        waitFx();
        assertFalse(graph.inspectorManagedForTest());

        JavaFxTestSupport.runOnFx("exit FS", graph::exitFullScreenForTest);
        waitFx();
        assertFalse(graph.focusGraphForTest());
        assertTrue(graph.signalPanelManagedForTest(), "exiting Focus Mode must restore the Signals explorer");
    }

    @Test
    @Timeout(45)
    void focusModeHidesSecondaryChromeAndOverflowMovesRareActions() throws Exception {
        AtomicReference<GraphController> ctrl = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
                Parent root = loader.load();
                ctrl.set(loader.getController());
                Stage stage = new Stage();
                stage.setScene(new Scene(root, 1280, 800));
                stage.show();
            } catch (Throwable ignored) {
            } finally {
                loaded.countDown();
            }
        });
        assertTrue(loaded.await(20, TimeUnit.SECONDS));
        GraphController graph = ctrl.get();
        waitFx();
        JavaFxTestSupport.runOnFx("layout toolbar", graph::relayoutGraphToolbarForTest);
        waitFx();
        assertTrue(graph.toolbarOverflowVisibleForTest(), "More must hold investigation actions at 1280px");
        assertFalse(graph.secondaryToolbarManagedForTest());

        JavaFxTestSupport.runOnFx("open inspector", () -> graph.setInspectorOpenForTest(true));
        waitFx();
        assertTrue(graph.chipStripManagedForTest());
        JavaFxTestSupport.runOnFx("enter focus", graph::enterFullScreenForTest);
        waitFx();
        assertTrue(graph.focusGraphForTest());
        assertFalse(graph.signalPanelManagedForTest());
        assertFalse(graph.inspectorManagedForTest());
        assertFalse(graph.chipStripManagedForTest());
        assertFalse(graph.fileStripManagedForTest());
        JavaFxTestSupport.runOnFx("exit focus", graph::exitFullScreenForTest);
        waitFx();
        assertTrue(graph.chipStripManagedForTest());
        assertTrue(graph.signalPanelManagedForTest());
        assertTrue(graph.inspectorManagedForTest());
    }

    @Test
    @Timeout(45)
    void escapeExitsFocusModeAndRepeatedToggleIsStable() throws Exception {
        AtomicReference<GraphController> ctrl = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
                Parent root = loader.load();
                ctrl.set(loader.getController());
                Stage stage = new Stage();
                stage.setScene(new Scene(root, 1366, 768));
                stage.show();
            } catch (Throwable ignored) {
            } finally {
                loaded.countDown();
            }
        });
        assertTrue(loaded.await(20, TimeUnit.SECONDS));
        GraphController graph = ctrl.get();
        waitFx();

        JavaFxTestSupport.runOnFx("open inspector", () -> graph.setInspectorOpenForTest(true));
        waitFx();
        JavaFxTestSupport.runOnFx("enter FS", graph::enterFullScreenForTest);
        waitFx();
        assertFalse(graph.signalPanelManagedForTest());
        assertFalse(graph.inspectorManagedForTest());

        JavaFxTestSupport.runOnFx("Esc", graph::pressEscapeForTest);
        waitFx();
        assertFalse(graph.focusGraphForTest());
        assertTrue(graph.signalPanelManagedForTest());
        assertTrue(graph.inspectorManagedForTest());

        for (int i = 0; i < 4; i++) {
            JavaFxTestSupport.runOnFx("toggle FS " + i, graph::enterFullScreenForTest);
            waitFx();
            assertTrue(graph.focusGraphForTest());
            assertFalse(graph.signalPanelManagedForTest());
            JavaFxTestSupport.runOnFx("exit FS " + i, graph::exitFullScreenForTest);
            waitFx();
            assertFalse(graph.focusGraphForTest());
            assertTrue(graph.signalPanelManagedForTest());
        }
    }

    private static String textOf(javafx.scene.Node node) {
        return node instanceof javafx.scene.control.Labeled labeled ? labeled.getText() : "";
    }

    private static double measureCanvasWidth(GraphController graph) throws Exception {
        AtomicReference<Double> width = new AtomicReference<>(0.0);
        JavaFxTestSupport.runOnFx("measure width", () -> width.set(graph.graphCanvasPanelWidthForTest()));
        return width.get();
    }

    private static void waitFx() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(50);
    }
}
