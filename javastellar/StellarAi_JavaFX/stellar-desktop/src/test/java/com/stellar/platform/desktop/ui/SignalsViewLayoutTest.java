package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane;
import com.stellar.platform.desktop.ui.signals.SignalBrowserPane;
import com.stellar.platform.desktop.ui.signals.SignalInspectorPane;
import com.stellar.platform.desktop.ui.signals.SignalsLayoutMetrics;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalsViewLayoutTest {

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
    void signalsViewUsesConstrainedBrowserWorkspaceInspector() throws Exception {
        AtomicReference<VBox> rootRef = new AtomicReference<>();
        AtomicReference<SignalsController> controllerRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(SignalsController.class.getResource("/fxml/signals-view.fxml"));
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

        VBox root = rootRef.get();
        assertNotNull(root);
        assertFalse(containsType(root, AnchorPane.class));

        StackPane host = findType(root, StackPane.class, "signals-workbench-host");
        assertEquals(Priority.ALWAYS, VBox.getVgrow(host));
        HBox workbench = findType(root, HBox.class, "signals-workbench");
        assertEquals(3, workbench.getChildren().size());

        SignalBrowserPane browser = findType(root, SignalBrowserPane.class);
        assertNotNull(browser);
        assertEquals(SignalsLayoutMetrics.BROWSER_PREF_WIDTH, browser.getPrefWidth(), 0.1);
        assertTrue(browser.getMaxWidth() <= SignalsLayoutMetrics.BROWSER_MAX_WIDTH + 0.1);
        assertEquals(Priority.NEVER, HBox.getHgrow(browser));

        ActiveSignalWorkspacePane workspace = findType(root, ActiveSignalWorkspacePane.class);
        assertNotNull(workspace);
        assertEquals(Priority.ALWAYS, HBox.getHgrow(workspace));
        assertTrue(workspace.getMinWidth() >= SignalsLayoutMetrics.WORKSPACE_MIN_WIDTH - 0.1);

        SignalInspectorPane inspector = findType(root, SignalInspectorPane.class);
        assertNotNull(inspector);
        assertEquals(SignalsLayoutMetrics.INSPECTOR_PREF_WIDTH, inspector.getPrefWidth(), 0.1);
        assertTrue(inspector.getMinWidth() >= SignalsLayoutMetrics.INSPECTOR_MIN_WIDTH - 0.1);
        assertEquals(Priority.NEVER, HBox.getHgrow(inspector));

        Scene scene = new Scene(root, 1600, 900);
        root.applyCss();
        root.resize(1600, 900);
        root.layout();
        assertTrue(browser.getWidth() <= SignalsLayoutMetrics.BROWSER_MAX_WIDTH + 2,
                "browser grew too wide: " + browser.getWidth());
        assertTrue(inspector.getWidth() >= SignalsLayoutMetrics.INSPECTOR_MIN_WIDTH - 2,
                "inspector too narrow: " + inspector.getWidth());
        assertTrue(workspace.getWidth() >= SignalsLayoutMetrics.WORKSPACE_MIN_WIDTH - 0.1,
                "center too narrow: " + workspace.getWidth());

        TextField search = findSearch(root);
        assertNotNull(search.getParent());
        assertEquals(Priority.ALWAYS, HBox.getHgrow(search.getParent()));
        assertTrue(search.getPromptText().startsWith("Search signal"));
        assertTrue(search.getParent().getStyleClass().contains("search-field-shell"));

        assertTrue(countTables(root) >= 2);
        assertNotNull(controllerRef.get());
    }

    private static <T> T findType(Node root, Class<T> type) {
        return findType(root, type, null);
    }

    private static <T> T findType(Node root, Class<T> type, String styleClass) {
        Deque<Node> q = new ArrayDeque<>();
        enqueue(q, root);
        while (!q.isEmpty()) {
            Node n = q.removeFirst();
            if (type.isInstance(n)
                    && (styleClass == null || n.getStyleClass().contains(styleClass))) {
                return type.cast(n);
            }
            enqueueChildren(q, n);
        }
        throw new AssertionError(type.getSimpleName() + (styleClass == null ? "" : ("." + styleClass)) + " not found");
    }

    private static TextField findSearch(Node root) {
        Deque<Node> q = new ArrayDeque<>();
        enqueue(q, root);
        while (!q.isEmpty()) {
            Node n = q.removeFirst();
            if (n instanceof TextField tf
                    && tf.getPromptText() != null
                    && tf.getPromptText().startsWith("Search signal")) {
                return tf;
            }
            enqueueChildren(q, n);
        }
        throw new AssertionError("searchField not found");
    }

    private static int countTables(Node root) {
        int count = 0;
        Deque<Node> q = new ArrayDeque<>();
        enqueue(q, root);
        while (!q.isEmpty()) {
            Node n = q.removeFirst();
            if (n instanceof TableView) {
                count++;
            }
            enqueueChildren(q, n);
        }
        return count;
    }

    private static boolean containsType(Node root, Class<?> type) {
        Deque<Node> q = new ArrayDeque<>();
        enqueue(q, root);
        while (!q.isEmpty()) {
            Node n = q.removeFirst();
            if (type.isInstance(n)) {
                return true;
            }
            enqueueChildren(q, n);
        }
        return false;
    }

    private static void enqueue(Deque<Node> q, Node node) {
        if (node != null) {
            q.add(node);
        }
    }

    private static void enqueueChildren(Deque<Node> q, Node node) {
        if (node instanceof javafx.scene.Parent parent) {
            q.addAll(parent.getChildrenUnmodifiable());
        }
    }
}