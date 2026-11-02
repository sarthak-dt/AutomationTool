package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.map.MapRoutePane;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.SplitPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Graph / Map Route must remain usable at supported desktop sizes. The map is primary content;
 * the right inspector scrolls internally and must not collapse the map.
 */
class MapRouteResponsiveLayoutTest {

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @Test
    @Timeout(45)
    void mapRouteKeepsUsableMapAtSupportedSizes() throws Exception {
        for (int[] size : new int[][]{{1920, 1080}, {1536, 864}, {1366, 768}, {1280, 720}}) {
            assertUsableMapWorkspace(size[0], size[1]);
        }
    }

    @Test
    @Timeout(45)
    void focusGraphGrowsTheMapAndExitRestoresExplorer() throws Exception {
        GraphHarness harness = showGraph(1366, 768);
        try {
            JavaFxTestSupport.runOnFx("prepare map tab", () -> {
                harness.graph.selectMapRouteTabForTest();
                harness.map.ensureUiBuiltForTest();
                harness.stage.getScene().getRoot().applyCss();
                harness.stage.getScene().getRoot().layout();
            });
            waitFx();
            double normal = mapHeight(harness);
            assertTrue(normal >= MapRoutePane.MAP_MIN_USABLE_HEIGHT - 1,
                    "normal map height " + normal);

            JavaFxTestSupport.runOnFx("enter focus", harness.graph::enterFullScreenForTest);
            waitFx();
            JavaFxTestSupport.runOnFx("layout after focus", () -> {
                harness.stage.getScene().getRoot().applyCss();
                harness.stage.getScene().getRoot().layout();
            });
            waitFx();
            assertTrue(harness.graph.focusGraphForTest());
            assertFalse(harness.graph.signalPanelManagedForTest());
            double focused = mapHeight(harness);
            assertTrue(focused + 0.5 >= normal,
                    "Focus Graph must not shrink the map (normal=" + normal + " focus=" + focused + ")");
            assertTrue(focused >= MapRoutePane.MAP_MIN_USABLE_HEIGHT - 1);
            assertTrue(harness.map.routeDetailsToggleReachableForTest());

            JavaFxTestSupport.runOnFx("exit focus", harness.graph::exitFullScreenForTest);
            waitFx();
            assertFalse(harness.graph.focusGraphForTest());
            assertTrue(harness.graph.signalPanelManagedForTest());
        } finally {
            close(harness);
        }
    }

    @Test
    @Timeout(45)
    void expandedRouteDetailsDoNotZeroTheMap() throws Exception {
        GraphHarness harness = showGraph(1366, 768);
        try {
            JavaFxTestSupport.runOnFx("expand details", () -> {
                harness.graph.selectMapRouteTabForTest();
                harness.map.ensureUiBuiltForTest();
                harness.stage.getScene().getRoot().layout();
                harness.map.toggleRouteDetailsForTest();
                harness.stage.getScene().getRoot().layout();
            });
            waitFx();
            assertTrue(harness.map.routeDetailsExpandedForTest());
            double height = mapHeight(harness);
            assertTrue(height >= MapRoutePane.MAP_MIN_USABLE_HEIGHT - 1,
                    "expanded Route Data must not collapse the map: " + height);
            assertTrue(harness.map.routeDataVisibleForTest());
            assertTrue(harness.map.railScrollableForTest());
        } finally {
            close(harness);
        }
    }

    private static void assertUsableMapWorkspace(int width, int height) throws Exception {
        GraphHarness harness = showGraph(width, height);
        try {
            JavaFxTestSupport.runOnFx("layout " + width + "x" + height, () -> {
                harness.graph.selectMapRouteTabForTest();
                harness.map.ensureUiBuiltForTest();
                harness.stage.getScene().getRoot().applyCss();
                harness.stage.getScene().getRoot().layout();
            });
            waitFx();
            double mapH = mapHeight(harness);
            assertTrue(mapH >= MapRoutePane.MAP_MIN_USABLE_HEIGHT - 1,
                    width + "x" + height + " map height " + mapH);
            assertTrue(harness.map.railScrollableForTest(), "right inspector must scroll");
            assertTrue(harness.map.routeDataVisibleForTest(), "Route Data must stay visible");
            assertTrue(harness.map.routeDetailsToggleReachableForTest(), "Show/Hide Details must stay reachable");
            SplitPane split = harness.map.workspaceSplitForTest();
            assertNotNull(split);
            assertFalse(importantNodeHasInvalidBounds(harness.stage.getScene().getRoot()),
                    width + "x" + height + " has a zero/negative important node");
        } finally {
            close(harness);
        }
    }

    private static double mapHeight(GraphHarness harness) throws Exception {
        AtomicReference<Double> height = new AtomicReference<>(0.0);
        JavaFxTestSupport.runOnFx("measure map", () -> height.set(harness.map.mapStageHeightForTest()));
        return height.get();
    }

    private static boolean importantNodeHasInvalidBounds(Node node) {
        if (node == null || !node.isVisible() || !node.isManaged()) {
            return false;
        }
        Bounds bounds = node.getBoundsInParent();
        if (bounds.getWidth() < 0 || bounds.getHeight() < 0) {
            return true;
        }
        if (node instanceof SplitPane || node instanceof MapRoutePane) {
            if (bounds.getWidth() == 0 || bounds.getHeight() == 0) {
                return true;
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (importantNodeHasInvalidBounds(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static GraphHarness showGraph(int width, int height) throws Exception {
        AtomicReference<GraphHarness> out = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
                Parent root = loader.load();
                GraphController graph = loader.getController();
                Stage stage = new Stage();
                stage.setScene(new Scene(root, width, height));
                stage.show();
                root.applyCss();
                root.layout();
                out.set(new GraphHarness(stage, graph, graph.mapRoutePaneForTest()));
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
        waitFx();
        return out.get();
    }

    private static void close(GraphHarness harness) throws Exception {
        if (harness == null) {
            return;
        }
        JavaFxTestSupport.runOnFx("close", () -> {
            if (harness.map != null) {
                harness.map.dispose();
            }
            harness.stage.close();
        });
    }

    private static void waitFx() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(80);
    }

    private record GraphHarness(Stage stage, GraphController graph, MapRoutePane map) {
    }
}
