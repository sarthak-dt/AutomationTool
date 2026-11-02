package com.stellar.platform.desktop.ui;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainShellLayoutTest {

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
    void shellExposesLightThemeChromeAndPhaseNav() throws Exception {
        AtomicReference<Parent> rootRef = new AtomicReference<>();
        AtomicReference<MainShellController> controllerRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                rootRef.set(loader.load());
                controllerRef.set(loader.getController());
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

        Parent root = rootRef.get();
        MainShellController controller = controllerRef.get();
        assertTrue(root.getStyleClass().contains("app-root"));
        assertTrue(root instanceof BorderPane);

        HBox header = findFirst(root, HBox.class, "top-header");
        assertTrue(header.getPrefHeight() <= 72);
        assertTrue(findLabels(header).stream().anyMatch(t -> t.equals("StellarAI")));
        assertTrue(findLabels(header).stream().anyMatch(t -> t.contains("AUTOMOTIVE PLATFORM")));
        assertFalse(findLabels(header).stream().anyMatch(t -> t.contains("StellarAI Automotive Engineering Platform")));
        assertTrue(controller.hasProjectStatusChipForTest());
        assertFalse(controller.projectStatusTextForTest().isBlank());
        assertFalse(controller.projectStatusTextForTest().contains("/ 6"));
        assertFalse(root.lookupAll(".workflow-bar").stream().anyMatch(n -> n.isManaged() && n.isVisible()
                && n.getLayoutBounds().getHeight() > 40));

        int selectorIndex = indexOf(header.getChildren(), n -> n.getStyleClass().contains("project-selector"));
        int searchIndex = indexOf(header.getChildren(), n -> n.getStyleClass().contains("header-search-host"));
        int actionsIndex = indexOf(header.getChildren(), n -> n.getStyleClass().contains("header-actions"));
        assertTrue(selectorIndex >= 0 && searchIndex == selectorIndex + 1,
                "search must sit immediately after the project selector");
        assertTrue(actionsIndex > searchIndex, "Save actions must sit after search, never between selector and search");
        Button save = findFirst(header, Button.class, "header-save-button");
        assertEquals("Save", save.getText());
        assertFalse(save.isVisible(), "Save stays hidden until a durable project is open");
        javafx.scene.control.TextField search = findFirst(header, javafx.scene.control.TextField.class, "header-search-field");
        assertEquals("Search projects and signals", search.getPromptText());
        assertEquals("No active project", controller.projectNameChromeForTest());

        VBox sidebar = (VBox) ((BorderPane) root).getLeft();
        assertTrue(sidebar.getStyleClass().contains("sidebar"));
        List<Button> navButtons = findButtons(sidebar);
        List<String> nav = navButtons.stream()
                .filter(Node::isManaged)
                .map(Button::getText)
                .toList();
        assertEquals(
                List.of("Workspace", "Sources", "Decode & Process", "Signals", "Graphs & Analysis",
                        "Investigations", "Configuration", "Collapse"),
                nav
        );
        for (String retired : List.of("Project Files", "Processing", "Inventory", "DBC Mapping",
                "Diagnostics", "Tests")) {
            assertFalse(nav.contains(retired), retired + " must not be permanent navigation");
        }

        for (Button b : navButtons) {
            if ("Collapse".equals(b.getText())) {
                continue;
            }
            assertNotNull(b.getGraphic(), b.getText() + " missing icon");
        }
        assertNotNull(findButton(sidebar, "Collapse").getGraphic());

        int last = sidebar.getChildren().size() - 1;
        assertTrue(sidebar.getChildren().get(last) instanceof Button);
        assertEquals("Collapse", ((Button) sidebar.getChildren().get(last)).getText());

        assertEquals(MainShellController.SIDEBAR_EXPANDED_WIDTH, sidebar.getPrefWidth(), 0.1);
        assertFalse(controller.isSidebarCollapsed());

        Scene expanded = new Scene(root, 1366, 768);
        root.applyCss();
        root.resize(1366, 768);
        root.layout();
        assertTrue(sidebar.getWidth() <= 260, "expanded sidebar too wide: " + sidebar.getWidth());
        assertTrue(sidebar.getWidth() >= 240, "expanded sidebar too narrow: " + sidebar.getWidth());
        assertTrue(((BorderPane) root).getCenter().getLayoutBounds().getWidth() > 900);

        controller.setSidebarCollapsed(true);
        root.applyCss();
        root.resize(1366, 768);
        root.layout();
        assertTrue(controller.isSidebarCollapsed());
        assertEquals(MainShellController.SIDEBAR_COLLAPSED_WIDTH, sidebar.getPrefWidth(), 0.1);
        assertTrue(sidebar.getWidth() <= 80, "collapsed sidebar too wide: " + sidebar.getWidth());
        assertTrue(sidebar.getWidth() >= 68, "collapsed sidebar too narrow: " + sidebar.getWidth());
        assertEquals(ContentDisplay.GRAPHIC_ONLY, findButton(sidebar, "Workspace").getContentDisplay());
        assertEquals("Workspace", tooltipText(findButton(sidebar, "Workspace")));
        assertEquals("Expand navigation", tooltipText(findButtonByStyle(sidebar, "sidebar-collapse")));

        for (double[] size : new double[][] {{1366, 768}, {1600, 900}, {1920, 1080}}) {
            root.resize(size[0], size[1]);
            root.layout();
            assertTrue(sidebar.getWidth() <= 80);
            assertTrue(((BorderPane) root).getCenter().prefWidth(-1) >= 0);
            assertFalse(root.getLayoutBounds().getWidth() > size[0] + 1);
        }

        controller.setSidebarCollapsed(false);
        root.resize(1600, 900);
        root.layout();
        assertEquals(ContentDisplay.LEFT, findButton(sidebar, "Signals").getContentDisplay());
        assertTrue(sidebar.getWidth() >= 240 && sidebar.getWidth() <= 260);
        assertNotNull(expanded);
    }

    private static Button findButton(Node root, String text) {
        return findButtons(root).stream().filter(b -> text.equals(b.getText())).findFirst().orElseThrow();
    }

    private static Button findButtonByStyle(Node root, String styleClass) {
        return findButtons(root).stream().filter(b -> b.getStyleClass().contains(styleClass)).findFirst().orElseThrow();
    }

    private static String tooltipText(Button button) {
        Tooltip tip = button.getTooltip();
        assertNotNull(tip, button + " missing tooltip");
        return tip.getText();
    }

    private static int indexOf(List<Node> children, java.util.function.Predicate<Node> pred) {
        for (int i = 0; i < children.size(); i++) {
            if (pred.test(children.get(i))) {
                return i;
            }
        }
        throw new AssertionError("child not found");
    }

    private static <T extends Node> T findFirst(Node root, Class<T> type, String styleClass) {
        Deque<Node> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            Node n = q.removeFirst();
            if (type.isInstance(n) && n.getStyleClass().contains(styleClass)) {
                return type.cast(n);
            }
            enqueue(q, n);
        }
        throw new AssertionError("Missing " + type.getSimpleName() + "." + styleClass);
    }

    private static List<Button> findButtons(Node root) {
        List<Button> out = new ArrayList<>();
        walkVisual(root, out);
        return out;
    }

    private static void walkVisual(Node node, List<Button> out) {
        if (node instanceof Button b) {
            out.add(b);
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                walkVisual(child, out);
            }
        }
    }

    private static List<String> findLabels(Node root) {
        List<String> out = new ArrayList<>();
        Deque<Node> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            Node n = q.removeFirst();
            if (n instanceof Label l && l.getText() != null) {
                out.add(l.getText());
            }
            enqueue(q, n);
        }
        return out;
    }

    private static void enqueue(Deque<Node> q, Node node) {
        if (node instanceof javafx.scene.Parent parent) {
            q.addAll(parent.getChildrenUnmodifiable());
        }
    }
}
