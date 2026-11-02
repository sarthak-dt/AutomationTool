package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.desktop.ui.GraphController;
import com.stellar.platform.desktop.ui.graph.AutomotiveSignalCanvas;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Map Route must live inside the existing Signal Plot workspace as a fourth analysis tab, and
 * loading that workspace must not pay any Map Route cost until the tab is opened.
 */
class MapRouteWorkspaceIntegrationTest {

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
    void signalPlotExposesMapRouteAsTheFourthAnalysisTab() throws Exception {
        AtomicReference<List<String>> titles = new AtomicReference<>();
        AtomicReference<Node> mapContent = new AtomicReference<>();
        runOnFx(() -> {
            TabPane tabs = analysisTabs(loadWorkspace());
            titles.set(tabs.getTabs().stream().map(Tab::getText).toList());
            mapContent.set(tabs.getTabs().get(3).getContent());
        });

        assertEquals(List.of("Waveforms", "Analytics", "Engineering", "Map Route"), titles.get());
        assertInstanceOf(MapRoutePane.class, mapContent.get());
    }

    @Test
    void openingTheWorkspaceDoesNotInitialiseTheMapRouteUi() throws Exception {
        AtomicReference<Boolean> built = new AtomicReference<>(true);
        AtomicReference<Integer> selectedTab = new AtomicReference<>(-1);
        runOnFx(() -> {
            TabPane tabs = analysisTabs(loadWorkspace());
            selectedTab.set(tabs.getSelectionModel().getSelectedIndex());
            built.set(((MapRoutePane) tabs.getTabs().get(3).getContent()).uiBuiltForTest());
        });

        assertEquals(0, selectedTab.get(), "Waveforms must remain the default analysis tab");
        assertFalse(built.get(), "Map Route must stay uninitialised until the tab is opened");
    }

    @Test
    void existingSignalPanelAndWaveformTabAreUnchanged() throws Exception {
        AtomicReference<Boolean> hasSignalPanel = new AtomicReference<>(false);
        AtomicReference<Boolean> hasCanvas = new AtomicReference<>(false);
        runOnFx(() -> {
            BorderPane root = loadWorkspace();
            hasSignalPanel.set(containsSignalList(root));
            // Tab content lives outside the laid-out scene graph until the TabPane is skinned,
            // so walk the declared structure rather than relying on a CSS lookup.
            Node waveformTab = analysisTabs(root).getTabs().get(0).getContent();
            boolean canvas = false;
            if (waveformTab instanceof VBox box) {
                for (Node child : box.getChildren()) {
                    if (child instanceof ScrollPane scroll && scroll.getContent() instanceof AutomotiveSignalCanvas) {
                        canvas = true;
                    }
                }
            }
            hasCanvas.set(canvas);
        });

        assertTrue(hasSignalPanel.get(), "the left Signals panel must remain in place");
        assertTrue(hasCanvas.get(), "the waveform canvas must remain in place");
    }

    private static BorderPane loadWorkspace() {
        try {
            FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
            BorderPane root = loader.load();
            assertNotNull(loader.getController(), "graph-view.fxml must instantiate its controller");
            return root;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static TabPane analysisTabs(BorderPane root) {
        TabPane tabs = findAnalysisTabs(root);
        assertNotNull(tabs, "analysis TabPane must exist in the Signal Plot workspace");
        return tabs;
    }

    private static boolean containsSignalList(Node node) {
        if (node instanceof ListView<?>) {
            return true;
        }
        if (node instanceof SplitPane split) {
            for (Node item : split.getItems()) {
                if (containsSignalList(item)) {
                    return true;
                }
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (containsSignalList(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Node findNamed(Node node, String id) {
        if (node != null && id.equals(node.getId())) {
            return node;
        }
        if (node instanceof SplitPane split) {
            for (Node item : split.getItems()) {
                Node found = findNamed(item, id);
                if (found != null) {
                    return found;
                }
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = findNamed(child, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static TabPane findAnalysisTabs(Node node) {
        if (node instanceof TabPane pane && pane.getTabs().size() >= 4) {
            return pane;
        }
        if (node instanceof SplitPane split) {
            for (Node item : split.getItems()) {
                TabPane found = findAnalysisTabs(item);
                if (found != null) {
                    return found;
                }
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                TabPane found = findAnalysisTabs(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void runOnFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
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
}
