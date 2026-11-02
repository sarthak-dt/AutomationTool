package com.stellar.platform.desktop.ui.signals;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometry guardrails for the table-first Active Signal Workspace.
 * Large summary-card grids were intentionally replaced by a compact status strip.
 */
class WorkspaceGeometryLayoutTest {

    @BeforeAll
    static void initJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(30)
    void wideWorkspaceKeepsCompactChromeAndLargeDataTable() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                ActiveSignalWorkspacePane pane = new ActiveSignalWorkspacePane();
                Scene scene = new Scene(pane, 1200, 780);
                assertNotNull(scene);
                pane.applyCss();
                pane.resize(1200, 780);
                pane.layout();

                ScrollPane chipScroll = findByStyle(pane, ScrollPane.class, "chip-scroll");
                HBox statusStrip = findByStyle(pane, HBox.class, "workspace-status-strip");
                HBox toolbar = findByStyle(pane, HBox.class, "analysis-toolbar");
                StackPane tableHost = findByStyle(pane, StackPane.class, null);
                TableView<?> table = findByStyle(pane, TableView.class, "workspace-table");

                // Constructor starts empty (chrome unmanaged). Force populated chrome into layout.
                for (Node n : List.of(chipScroll, statusStrip, toolbar)) {
                    n.setManaged(true);
                    n.setVisible(true);
                }
                table.setManaged(true);
                table.setVisible(true);
                tableHost.setMinHeight(0);
                pane.resize(1200, 780);
                pane.layout();

                assertTrue(chipScroll.isVisible());
                assertTrue(statusStrip.isVisible());
                assertTrue(toolbar.isVisible());
                assertTrue(table.isVisible());

                // Compact status strip — not a six-card GridPane.
                assertTrue(statusStrip.getPrefHeight() <= 30,
                        "status strip too tall: " + statusStrip.getPrefHeight());
                assertTrue(statusStrip.getChildren().stream().anyMatch(n ->
                        n instanceof Label && n.getStyleClass().contains("workspace-status-item")));
                assertTrue(findByStyleOptional(pane, GridPane.class, "workspace-summary-bar").isEmpty(),
                        "obsolete summary-card GridPane must not exist");

                assertFalse(pane.getChildren().stream().anyMatch(n ->
                        n.getStyleClass().contains("workspace-utility-row") && n.isManaged()),
                        "utility row must not reserve a band above the toolbar");
                assertFalse(toolbar.getChildren().stream().anyMatch(n ->
                        n.getStyleClass().contains("workspace-toolbar-actions")));
                assertTrue(pane.getChildren().indexOf(chipScroll.getParent()) < pane.getChildren().indexOf(toolbar));
                assertTrue(toolbar.getPrefHeight() <= SignalsLayoutMetrics.TOOLBAR_HEIGHT * 2 + 8,
                        "toolbar too tall: " + toolbar.getPrefHeight());

                assertEquals(Priority.ALWAYS, VBox.getVgrow(tableHost));
                assertEquals(0, tableHost.getMinHeight(), 0.1, "hard table minHeight causes clipping");
                assertEquals(Double.MAX_VALUE, table.getMaxHeight(), 0.1);
                assertTrue(tableHost.getHeight() >= SignalsLayoutMetrics.TABLE_PREFERRED_MIN_HEIGHT - 1,
                        "table host too short: " + tableHost.getHeight());
                assertTrue(tableHost.getHeight() > statusStrip.getHeight() * 3,
                        "table should dominate status chrome: table="
                                + tableHost.getHeight() + " status=" + statusStrip.getHeight());
                assertTrue(tableHost.getHeight() > toolbar.getHeight(),
                        "table should be taller than toolbar");

                // Footer must remain inside the workspace (no overflow clipping).
                javafx.scene.layout.HBox footer = findByStyle(pane, javafx.scene.layout.HBox.class, "workspace-table-footer");
                footer.setManaged(true);
                footer.setVisible(true);
                pane.layout();
                Bounds workspaceBounds = pane.localToScene(pane.getBoundsInLocal());
                Bounds footerBounds = footer.localToScene(footer.getBoundsInLocal());
                assertTrue(footerBounds.getMaxY() <= workspaceBounds.getMaxY() + 1.5,
                        "footer clipped below workspace");

                assertNoVerticalOverlap(chipScroll, toolbar);
                assertNoVerticalOverlap(toolbar, statusStrip);
                assertNoVerticalOverlap(statusStrip, tableHost);
                assertNoVerticalOverlap(tableHost, footer);

                HBox chipBar = findByStyle(pane, HBox.class, "chip-bar");
                HBox chip1 = new HBox();
                chip1.setPrefWidth(280);
                HBox chip2 = new HBox();
                chip2.setPrefWidth(280);
                chipBar.getChildren().setAll(chip1, chip2);
                Method refresh = ActiveSignalWorkspacePane.class.getDeclaredMethod("refreshChipScrollPolicy");
                refresh.setAccessible(true);
                refresh.invoke(pane);
                assertEquals(ScrollPane.ScrollBarPolicy.AS_NEEDED, chipScroll.getHbarPolicy());

                assertTrue(findLabelText(pane, "Navigate"));
                assertFalse(findLabelText(pane, "Go to Index"));
                assertFalse(findLabelText(pane, "Go to Time (s)"));
                assertTrue(findLabelText(pane, "Display Mode"));
                assertTrue(findLabelText(pane, "Precision"));
                assertTrue(findLabelText(pane, "Rows"));
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static void assertNoVerticalOverlap(Node above, Node below) {
        Bounds a = above.localToScene(above.getBoundsInLocal());
        Bounds b = below.localToScene(below.getBoundsInLocal());
        assertTrue(a.getMaxY() <= b.getMinY() + 1.5,
                above.getStyleClass() + " overlaps " + below.getStyleClass()
                        + " aMaxY=" + a.getMaxY() + " bMinY=" + b.getMinY());
    }

    private static boolean findLabelText(Parent root, String text) {
        Deque<Node> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            Node n = q.removeFirst();
            if (n instanceof Label label && text.equals(label.getText())) {
                return true;
            }
            if (n instanceof Parent parent) {
                q.addAll(parent.getChildrenUnmodifiable());
            }
        }
        return false;
    }

    private static <T extends Node> Optional<T> findByStyleOptional(Parent root, Class<T> type, String styleClass) {
        try {
            return Optional.of(findByStyle(root, type, styleClass));
        } catch (AssertionError missing) {
            return Optional.empty();
        }
    }

    private static <T extends Node> T findByStyle(Parent root, Class<T> type, String styleClass) {
        Deque<Node> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            Node n = q.removeFirst();
            if (type.isInstance(n)
                    && (styleClass == null || n.getStyleClass().contains(styleClass))) {
                // Prefer the direct workspace child StackPane hosting the table.
                if (styleClass == null && type == StackPane.class) {
                    if (n.getParent() == root && ((StackPane) n).getChildren().stream()
                            .anyMatch(c -> c instanceof TableView)) {
                        return type.cast(n);
                    }
                } else {
                    return type.cast(n);
                }
            }
            if (n instanceof Parent parent) {
                q.addAll(parent.getChildrenUnmodifiable());
            }
        }
        if (styleClass == null && type == StackPane.class) {
            // Fallback: first stack pane containing a TableView.
            q.add(root);
            while (!q.isEmpty()) {
                Node n = q.removeFirst();
                if (n instanceof StackPane stack
                        && stack.getChildren().stream().anyMatch(c -> c instanceof TableView)) {
                    return type.cast(stack);
                }
                if (n instanceof Parent parent) {
                    q.addAll(parent.getChildrenUnmodifiable());
                }
            }
        }
        throw new AssertionError(type.getSimpleName()
                + (styleClass == null ? "" : ("." + styleClass))
                + " not found");
    }
}
