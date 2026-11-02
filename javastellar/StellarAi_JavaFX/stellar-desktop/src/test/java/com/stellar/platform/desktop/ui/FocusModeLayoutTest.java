package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane;
import com.stellar.platform.desktop.ui.signals.SignalBrowserPane;
import com.stellar.platform.desktop.ui.signals.SignalInspectorPane;
import com.stellar.platform.desktop.ui.signals.WorkspaceFocusMode;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focus Mode is presentation-only; temporary drawers keep FOCUS active. */
class FocusModeLayoutTest {

    @BeforeAll
    static void initJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        Platform.runLater(() -> Platform.setImplicitExit(false));
        CountDownLatch configured = new CountDownLatch(1);
        Platform.runLater(configured::countDown);
        assertTrue(configured.await(5, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(30)
    void focusModeHidesSidePanesAndDrawersPreserveFocus() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            Stage stage = null;
            try {
                FXMLLoader loader = new FXMLLoader(SignalsController.class.getResource("/fxml/signals-view.fxml"));
                VBox root = loader.load();
                SignalsController controller = loader.getController();
                ActiveSignalWorkspacePane workspace = findType(root, ActiveSignalWorkspacePane.class);
                SignalBrowserPane browser = findType(root, SignalBrowserPane.class);
                SignalInspectorPane inspector = findType(root, SignalInspectorPane.class);
                HBox workbench = findType(root, HBox.class, "signals-workbench");
                StackPane host = findType(root, StackPane.class, "signals-workbench-host");

                stage = new Stage();
                stage.setScene(new Scene(root, 1600, 900));
                stage.show();
                root.applyCss();
                root.layout();

                assertEquals(WorkspaceFocusMode.NORMAL, workspace.focusMode());
                assertEquals(WorkspaceFocusMode.NORMAL, controller.focusMode());
                assertEquals(3, workbench.getChildren().size());
                assertTrue(browser.isManaged());
                assertTrue(inspector.isManaged());
                assertEquals(Priority.ALWAYS, VBox.getVgrow(host));
                StackPane tableHost = findType(workspace, StackPane.class);
                assertEquals(Priority.ALWAYS, VBox.getVgrow(tableHost));
                assertEquals(0, tableHost.getMinHeight(), 0.1);

                findButton(workspace, "Focus Table").fire();
                root.layout();
                assertEquals(WorkspaceFocusMode.FOCUS, controller.focusMode());
                assertEquals(1, workbench.getChildren().size());
                assertFalse(controller.isFocusPaletteOpen());

                findButton(workspace, "Add Signals").fire();
                root.layout();
                assertEquals(WorkspaceFocusMode.FOCUS, controller.focusMode());
                assertTrue(controller.isFocusPaletteOpen());

                findButton(workspace, "Signal Details").fire();
                root.layout();
                assertEquals(WorkspaceFocusMode.FOCUS, controller.focusMode());
                assertTrue(controller.isFocusDetailsOpen());

                findButton(workspace, "Exit Focus").fire();
                root.layout();
                assertEquals(WorkspaceFocusMode.NORMAL, controller.focusMode());
                assertEquals(3, workbench.getChildren().size());
                assertTrue(browser.isManaged());
                assertTrue(inspector.isManaged());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                if (stage != null) {
                    stage.close();
                }
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @Test
    @Timeout(40)
    void focusModeKeepsWorkspaceInsideContentHostBounds() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                FXMLLoader shellLoader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                BorderPane shellRoot = shellLoader.load();
                MainShellController shell = shellLoader.getController();

                FXMLLoader sigLoader = new FXMLLoader(SignalsController.class.getResource("/fxml/signals-view.fxml"));
                VBox signals = sigLoader.load();
                SignalsController controller = sigLoader.getController();
                StackPane contentHost = findType(shellRoot, StackPane.class, "content-host");
                contentHost.getChildren().setAll(signals);

                Stage stage = new Stage();
                Scene scene = new Scene(shellRoot, 1600, 900);
                scene.getStylesheets().add(
                        MainShellController.class.getResource("/css/stellar-desktop.css").toExternalForm());
                stage.setScene(scene);
                stage.show();
                shellRoot.applyCss();
                shellRoot.layout();

                ActiveSignalWorkspacePane workspace = findType(signals, ActiveSignalWorkspacePane.class);
                StackPane host = findType(signals, StackPane.class, "signals-workbench-host");
                TableView<?> table = findType(workspace, TableView.class, "workspace-table");
                Label title = workspace.titleLabel();

                var shellField = SignalsController.class.getDeclaredField("shell");
                shellField.setAccessible(true);
                shellField.set(controller, shell);

                findButton(workspace, "Focus Table").fire();
                shellRoot.layout();
                signals.layout();

                Bounds lastWorkspace = null;
                for (double width : new double[] {1280, 1366, 1536, 1920}) {
                    stage.setWidth(width);
                    stage.setHeight(900);
                    shellRoot.resize(width, 900);
                    shellRoot.applyCss();
                    shellRoot.layout();
                    signals.layout();
                    lastWorkspace = assertFocusContained(width, contentHost, host, workspace, table, title);
                }

                double wsX = lastWorkspace.getMinX();
                double wsW = lastWorkspace.getWidth();
                findButton(workspace, "Add Signals").fire();
                shellRoot.layout();
                assertEquals(WorkspaceFocusMode.FOCUS, controller.focusMode());
                // Close via Exit not needed; close drawer by opening details then exit path
                findButton(workspace, "Signal Details").fire();
                shellRoot.layout();
                findButton(workspace, "Exit Focus").fire();
                shellRoot.layout();
                findButton(workspace, "Focus Table").fire();
                shellRoot.layout();
                Bounds after = workspace.localToScene(workspace.getBoundsInLocal());
                assertEquals(wsX, after.getMinX(), 2.0, "workspace x shifted after drawer cycle");
                assertEquals(wsW, after.getWidth(), 4.0, "workspace width shifted after drawer cycle");

                // Timestamp column useful minimum when columns exist
                if (!table.getColumns().isEmpty()) {
                    TableColumn<?, ?> first = table.getColumns().getFirst();
                    assertTrue(first.getMinWidth() >= 120.0 || first.getWidth() >= 120.0,
                            "timestamp column too narrow");
                }

                // Restore normal panes
                findButton(workspace, "Exit Focus").fire();
                shellRoot.layout();
                HBox workbench = findType(signals, HBox.class, "signals-workbench");
                assertEquals(3, workbench.getChildren().size());
                stage.close();
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

    private static Bounds assertFocusContained(
            double width,
            StackPane contentHost,
            StackPane host,
            ActiveSignalWorkspacePane workspace,
            TableView<?> table,
            Label title
    ) {
        Bounds contentScene = contentHost.localToScene(contentHost.getBoundsInLocal());
        Bounds workspaceScene = workspace.localToScene(workspace.getBoundsInLocal());
        Bounds tableScene = table.localToScene(table.getBoundsInLocal());
        Bounds titleScene = title.localToScene(title.getBoundsInLocal());
        assertTrue(workspaceScene.getMinX() + 0.5 >= contentScene.getMinX()
                        && workspaceScene.getMaxX() <= contentScene.getMaxX() + 1.0
                        && workspaceScene.getMinY() + 0.5 >= contentScene.getMinY()
                        && workspaceScene.getMaxY() <= contentScene.getMaxY() + 1.0,
                "workspace outside content host at " + width
                        + " ws=" + workspaceScene + " content=" + contentScene);
        assertTrue(tableScene.getMinX() + 0.5 >= workspaceScene.getMinX(),
                "table left of workspace at " + width);
        assertTrue(tableScene.getMaxX() <= workspaceScene.getMaxX() + 1.0,
                "table exceeds workspace right at " + width);
        assertTrue(titleScene.getMinX() + 0.5 >= workspaceScene.getMinX()
                        && titleScene.getMaxX() <= workspaceScene.getMaxX() + 1.0,
                "title clipped at " + width);
        assertTrue(workspace.getWidth() > 700, "workspace too narrow at " + width + ": " + workspace.getWidth());
        for (Node node : workspace.lookupAll(".button")) {
            if (!(node instanceof Button button) || !button.isVisible() || button.getWidth() < 8) {
                continue;
            }
            Bounds buttonBounds = button.localToScene(button.getBoundsInLocal());
            assertTrue(buttonBounds.getMinX() + 0.5 >= workspaceScene.getMinX()
                            && buttonBounds.getMaxX() <= workspaceScene.getMaxX() + 1.0,
                    "toolbar action clipped at " + width + ": " + button.getText());
        }
        Button leftEdge = findStyle(host, Button.class, "focus-edge-left");
        assertTrue(leftEdge.isVisible(), "left focus edge missing at " + width);
        Bounds edgeScene = leftEdge.localToScene(leftEdge.getBoundsInLocal());
        assertTrue(edgeScene.getMaxX() <= workspaceScene.getMinX() + 1.0,
                "left edge overlays workspace at " + width
                        + " edgeMax=" + edgeScene.getMaxX() + " wsMin=" + workspaceScene.getMinX());
        Button rightEdge = findStyle(host, Button.class, "focus-edge-right");
        if (rightEdge.isVisible()) {
            Bounds rightScene = rightEdge.localToScene(rightEdge.getBoundsInLocal());
            assertTrue(rightScene.getMinX() + 1.0 >= workspaceScene.getMaxX(),
                    "right edge overlays workspace at " + width);
        }
        return workspaceScene;
    }

    private static Button findButton(Parent root, String text) {
        Deque<Node> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            Node n = q.removeFirst();
            if (n instanceof Button b && text.equals(b.getText())) {
                return b;
            }
            if (n instanceof Parent p) {
                q.addAll(p.getChildrenUnmodifiable());
            }
        }
        throw new AssertionError("Button not found: " + text);
    }

    private static <T> T findStyle(Node root, Class<T> type, String styleClass) {
        return findType(root, type, styleClass);
    }

    private static <T> T findType(Node root, Class<T> type) {
        return findType(root, type, null);
    }

    private static <T> T findType(Node root, Class<T> type, String styleClass) {
        Deque<Node> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            Node n = q.removeFirst();
            if (type.isInstance(n)
                    && (styleClass == null || n.getStyleClass().contains(styleClass))) {
                return type.cast(n);
            }
            if (n instanceof Parent p) {
                q.addAll(p.getChildrenUnmodifiable());
            }
        }
        throw new AssertionError(type.getSimpleName() + " not found");
    }
}
