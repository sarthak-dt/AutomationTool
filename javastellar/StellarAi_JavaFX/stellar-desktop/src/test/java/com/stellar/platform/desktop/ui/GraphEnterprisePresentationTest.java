package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.ui.GraphController;
import com.stellar.platform.desktop.ui.graph.AutomotiveSignalCanvas;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Orientation;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compact Graphs chrome keeps selection, order, and the waveform reachable. */
class GraphEnterprisePresentationTest {

    private static final int[][] SIZES = {{1706, 900}, {1440, 900}, {1280, 800}, {1100, 720}};

    @TempDir Path temp;

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(() -> {
                Platform.setImplicitExit(false);
                started.countDown();
            });
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        AtomicReference<Double> scale = new AtomicReference<>();
        onFx(() -> scale.set(Screen.getPrimary().getOutputScaleX()));
        System.out.println("GraphEnterprisePresentation outputScaleX=" + scale.get());
    }

    @Test
    @Timeout(90)
    void compactSidebarPreservesActionsAndGrowsTheWaveform() throws Exception {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        service.newProject("Graph layout");
        String longName = "VehicleSpeed_VeryLongEngineeringSignalName_ForTooltip";
        List<CanonicalSignal> samples = new ArrayList<>();
        samples.add(signal(longName, 0x10, 1));
        samples.add(signal("GPS_GROUND_SPEED", 0x20, 2));
        samples.add(signal("BatteryVoltage", 0x30, 3));
        InMemorySignalCatalogStore catalog = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 40);
        catalog.accept(samples);
        service.signalsModel().replaceCatalog(catalog);

        AtomicReference<GraphController> graphRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        try {
            onFx(() -> {
                FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
                Parent root = loader.load();
                GraphController graph = loader.getController();
                graphRef.set(graph);
                Scene scene = new Scene(root, 1706, 900);
                scene.getStylesheets().add(GraphController.class.getResource("/css/stellar-desktop.css").toExternalForm());
                Stage stage = new Stage();
                stageRef.set(stage);
                stage.setScene(scene);
                stage.show();
                graph.bindMembershipForTest(service);
                for (int[] size : SIZES) {
                    stage.setWidth(size[0]);
                    stage.setHeight(size[1]);
                    root.resize(size[0], size[1]);
                    root.applyCss();
                    root.layout();
                    measure(root, graph, size[0], size[1]);
                }
                root.resize(1440, 900);
                root.applyCss();
                root.layout();

                assertFalse(graph.orderActionEnabledForTest("Move Up"));
                graph.searchExplorerForTest(longName);
                assertEquals(1, graph.explorerRowsForTest().size());
                Label name = (Label) root.lookup(".graph-explorer-name");
                assertTrue(name != null && name.getTooltip() != null && name.getTooltip().getText().contains(longName),
                        "long signal name tooltip");
                assertTrue(name.getTooltip().getText().contains("File:") || name.getTooltip().getText().contains("run.mf4")
                        || name.getTooltip().getText().contains("Source:"));

                graph.searchExplorerForTest("");
                graph.setExplorerFilterForTest("dbc");
                assertFalse(graph.explorerRowsForTest().isEmpty());
                graph.setExplorerFilterForTest("all");
                int before = graph.selectedMembershipForTest().size();
                graph.fireExplorerMenuForTest("selection", "Select All");
                assertEquals(3, graph.selectedMembershipForTest().size());
                assertTrue(graph.selectedMembershipForTest().size() >= before);
                List<SignalCatalogKey> order = graph.selectedMembershipForTest();
                graph.selectExplorerRowForTest(0);
                assertTrue(graph.orderActionEnabledForTest("Move Down"));
                graph.fireExplorerMenuForTest("order", "Move Down");
                List<SignalCatalogKey> moved = graph.selectedMembershipForTest();
                assertEquals(order.get(1), moved.get(0));
                assertEquals(order.get(0), moved.get(1));
                assertEquals(order.get(2), moved.get(2));
                graph.fireExplorerMenuForTest("visibility", "Hide All");
                assertEquals(moved, graph.selectedMembershipForTest(), "Hide All must not drop selection");
                graph.fireExplorerMenuForTest("visibility", "Show All");
                assertEquals(moved, graph.selectedMembershipForTest());
                for (int[] size : SIZES) {
                    stage.setWidth(size[0]);
                    stage.setHeight(size[1]);
                    root.resize(size[0], size[1]);
                    root.applyCss();
                    root.layout();
                    assertExplorerRowsReadable(root, size[0]);
                    snapshotSignalPanel(root, size[0]);
                }

                ToggleButton stacked = (ToggleButton) root.lookup("#stackedLayoutButton");
                ToggleButton overlay = (ToggleButton) root.lookup("#overlayLayoutButton");
                stacked.fire();
                assertEquals(AutomotiveSignalCanvas.LayoutMode.STACKED, graph.canvasForTest().getLayoutMode());
                overlay.fire();
                assertEquals(AutomotiveSignalCanvas.LayoutMode.OVERLAY_MULTI_AXIS, graph.canvasForTest().getLayoutMode());

                Region canvasColumn = graph.graphCanvasColumnForTest();
                double open = canvasColumn.getWidth();
                graph.collapseSignalPanelForTest();
                root.applyCss();
                root.layout();
                assertTrue(canvasColumn.getWidth() > open + 40, "sidebar collapse width " + canvasColumn.getWidth());
                graph.expandSignalPanelForTest();
                graph.toggleInspectorForTest();
                root.applyCss();
                root.layout();
                assertFalse(graph.inspectorOpenForTest());
                graph.toggleInspectorForTest();
                assertTrue(graph.inspectorOpenForTest());
                assertEquals(moved, graph.selectedMembershipForTest());
            });
        } finally {
            onFx(() -> {
                if (graphRef.get() != null) {
                    graphRef.get().dispose();
                }
                if (stageRef.get() != null) {
                    stageRef.get().close();
                }
            });
            service.shutdown();
        }
    }

    private static void assertPlotUnchanged(int width, int height, double[] plot) {
        double expectedW = switch (width) {
            case 1706 -> 914.6666666666666;
            case 1440 -> 648.6666666666666;
            case 1280 -> 569.3333333333334;
            case 1100 -> 389.3333333333333;
            default -> Double.NaN;
        };
        double expectedH = switch (height) {
            case 900 -> 598.6666666666666;
            case 800 -> 498.66666666666663;
            case 720 -> 418.6666666666667;
            default -> Double.NaN;
        };
        assertEquals(82, plot[0], 0.6, width + " plot X " + plot[0]);
        assertEquals(28, plot[1], 0.6, width + " plot Y " + plot[1]);
        assertEquals(expectedW, plot[2], 0.6, width + " plot W " + plot[2]);
        assertEquals(expectedH, plot[3], 0.6, width + " plot H " + plot[3]);
    }

    private static void measure(Parent root, GraphController graph, int width, int height) {
        Region filters = (Region) root.lookup(".graph-filter-scroll");
        Region chips = (Region) root.lookup(".graph-chip-strip");
        Region cursor = (Region) root.lookup(".graph-cursor-bar");
        Region canvasColumn = graph.graphCanvasColumnForTest();
        Region list = (Region) root.lookup(".graph-signal-explorer");
        assertTrue(filters.getHeight() <= 60 && filters.getHeight() >= 24, width + " filter strip " + filters.getHeight());
        assertTrue(chips.getHeight() <= 40, width + " chip strip " + chips.getHeight());
        assertTrue(cursor.getHeight() <= 40 && cursor.getHeight() >= 28, width + " cursor bar " + cursor.getHeight());
        Bounds bar = cursor.localToScene(cursor.getBoundsInLocal());
        Bounds column = canvasColumn.localToScene(canvasColumn.getBoundsInLocal());
        for (String text : List.of("Cursor A", "Cursor B", "Clear")) {
            Button button = findButton(root, text);
            Bounds bounds = button.localToScene(button.getBoundsInLocal());
            assertTrue(bounds.getHeight() >= 20, text + " clipped height " + bounds.getHeight());
            assertTrue(bounds.getWidth() >= 60, text + " width " + bounds.getWidth());
            assertFalse(button.getText().contains("..."), text + " abbreviated");
            assertTrue(bounds.getMaxX() <= bar.getMaxX() + 1, text + " past the cursor bar");
            assertTrue(bounds.getMaxY() <= column.getMaxY() + 1, text + " past the graph column");
        }
        assertTrue(list.getHeight() > 120, width + " list " + list.getHeight());
        assertSignalChromeReadable(root, width);
        double[] plot = graph.plotBoundsForTest();
        assertPlotUnchanged(width, height, plot);
        Region toolbar = (Region) root.lookup(".graph-toolbar");
        assertTrue(toolbar.getHeight() <= 40.6, width + " toolbar " + toolbar.getHeight());
        MenuButton more = (MenuButton) root.lookup("#toolbarMoreButton");
        if (more != null && more.isManaged()) {
            assertTrue(more.getWidth() >= 26 && more.getWidth() <= 32, width + " More width " + more.getWidth());
            assertTrue(more.getHeight() >= 26 && more.getHeight() <= 32, width + " More height " + more.getHeight());
            assertEquals(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY, more.getContentDisplay());
            assertTrue(more.getGraphic() != null, width + " More has no icon");
            assertFalse(more.getText().contains("..."), width + " More text clipped");
        }
        System.out.println("GRAPH_AFTER " + width + "x" + height
                + " filterH=" + filters.getHeight()
                + " chipH=" + chips.getHeight()
                + " cursorH=" + cursor.getHeight()
                + " listH=" + list.getHeight()
                + " canvasColumnW=" + canvasColumn.getWidth()
                + " canvasColumnH=" + canvasColumn.getHeight()
                + " plotX=" + plot[0]
                + " plotY=" + plot[1]
                + " plotW=" + plot[2]
                + " plotH=" + plot[3]
                + " canvasW=" + plot[4]
                + " canvasH=" + plot[5]
                + " toolbarH=" + ((Region) root.lookup(".graph-toolbar")).getHeight()
                + " fileStripH=" + ((Region) root.lookup(".graph-file-strip")).getHeight());
    }

    private static void assertSignalChromeReadable(Parent root, int width) {
        Label count = (Label) root.lookup(".graph-explorer-count");
        Region panel = (Region) root.lookup(".graph-signal-panel");
        String real = count.getText();
        count.setText("7 selected · 7 visible / 30 signals");
        root.applyCss();
        root.layout();
        assertFullyWrapped(count, width + " count");
        assertTrue(count.getText().contains("7 selected · 7 visible"), width + " count text " + count.getText());
        Bounds countBounds = count.localToScene(count.getBoundsInLocal());
        Bounds panelBounds = panel.localToScene(panel.getBoundsInLocal());
        assertTrue(countBounds.getMaxX() <= panelBounds.getMaxX() + 1, width + " count past the panel");
        count.setText(real);
        root.layout();

        Region filters = (Region) root.lookup(".graph-filter-scroll");
        ToggleButton nativeChip = findToggle(root, "Native");
        ToggleButton recommendedChip = findToggle(root, "Recommended");
        assertTrue(nativeChip == null || !nativeChip.isManaged(), width + " Native chip is visible");
        assertTrue(recommendedChip == null || !recommendedChip.isManaged(), width + " Recommended chip is visible");
        for (String text : List.of("All", "Selected", "DBC")) {
            ToggleButton chip = findToggle(root, text);
            assertTrue(chip != null && chip.isManaged(), width + " missing filter " + text);
            assertTrue(chip.getWidth() + 0.5 >= chip.prefWidth(-1), width + " filter " + text + " clipped");
            assertFalse(chip.getText().contains("..."), width + " filter " + text + " abbreviated");
            Bounds chipBounds = chip.localToScene(chip.getBoundsInLocal());
            assertTrue(chipBounds.getMaxX() <= panelBounds.getMaxX() + 1, width + " filter " + text + " past the panel");
            assertTrue(chipBounds.getMaxY() <= filters.localToScene(filters.getBoundsInLocal()).getMaxY() + 1,
                    width + " filter " + text + " outside the filter row");
        }
        for (String text : List.of("Select", "Show", "Order")) {
            MenuButton menu = findMenu(root, text);
            assertTrue(menu.getWidth() + 0.5 >= menu.prefWidth(-1), width + " " + text + " clipped " + menu.getWidth());
            assertFalse(menu.getText().contains("..."), width + " " + text + " abbreviated");
            Bounds menuBounds = menu.localToScene(menu.getBoundsInLocal());
            assertTrue(menuBounds.getMaxX() <= panelBounds.getMaxX() + 1, width + " " + text + " past the panel");
            assertTrue(menuBounds.getMaxY() <= panelBounds.getMaxY() + 1, width + " " + text + " below the panel");
        }
        Label hint = (Label) root.lookup(".graph-signal-panel .graph-hint");
        assertTrue(hint.isWrapText(), width + " footer does not wrap");
        assertFullyWrapped(hint, width + " footer");
        assertTrue(hint.getText().contains("Checkbox adds a signal"));
        assertNoHorizontalListScroll(root, width);
    }

    private static void assertExplorerRowsReadable(Parent root, int width) {
        Label name = (Label) root.lookup(".graph-explorer-name");
        Label meta = (Label) root.lookup(".graph-explorer-meta");
        Label badge = (Label) root.lookup(".graph-signal-panel .source-badge");
        assertTrue(name != null && meta != null && badge != null, width + " signal row is incomplete");
        Bounds nameBounds = name.localToScene(name.getBoundsInLocal());
        Bounds metaBounds = meta.localToScene(meta.getBoundsInLocal());
        Bounds badgeBounds = badge.localToScene(badge.getBoundsInLocal());
        assertTrue(nameBounds.getHeight() >= 10, width + " signal name clipped");
        assertTrue(metaBounds.getHeight() >= 8, width + " signal metadata clipped");
        assertTrue(nameBounds.getMaxY() <= metaBounds.getMinY() + 1, width + " name overlaps metadata");
        assertTrue(nameBounds.getMaxX() <= badgeBounds.getMinX() + 1, width + " name overlaps the type badge");
        assertTrue(metaBounds.getMaxX() <= badgeBounds.getMinX() + 1, width + " metadata overlaps the type badge");
        assertTrue(badge.getWidth() + 0.5 >= badge.prefWidth(-1), width + " type badge clipped " + badge.getText());
        assertTrue(name.getTooltip() != null && name.getTooltip().getText().contains(name.getText()));
        assertTrue(meta.getTooltip() != null && !meta.getTooltip().getText().isBlank());
        Button visibility = null;
        for (Node node : root.lookupAll(".graph-explorer-visibility")) {
            if (node instanceof Button button && button.isManaged() && button.isVisible()) {
                visibility = button;
                break;
            }
        }
        assertTrue(visibility != null, width + " selected signal has no visibility control");
        assertTrue(visibility.getWidth() + 0.5 >= visibility.prefWidth(-1), width + " visibility clipped");
        Bounds visibilityBounds = visibility.localToScene(visibility.getBoundsInLocal());
        assertTrue(badgeBounds.getMaxX() <= visibilityBounds.getMinX() + 1, width + " badge overlaps visibility");
        assertNoHorizontalListScroll(root, width);
    }

    private static void assertFullyWrapped(Label label, String id) {
        double width = Math.max(1, label.getWidth());
        assertTrue(label.getHeight() + 1 >= label.prefHeight(width),
                id + " clipped " + label.getWidth() + "x" + label.getHeight()
                        + " prefH=" + label.prefHeight(width) + " text=" + label.getText());
    }

    private static void assertNoHorizontalListScroll(Parent root, int width) {
        Node list = root.lookup(".graph-signal-explorer");
        for (Node node : list.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.HORIZONTAL && bar.isVisible()) {
                assertTrue(bar.getHeight() < 2 && bar.getWidth() < 2, width + " signal list horizontal scrollbar");
            }
        }
    }

    private static void snapshotSignalPanel(Parent root, int width) throws Exception {
        Region panel = (Region) root.lookup(".graph-signal-panel");
        WritableImage image = panel.snapshot(new SnapshotParameters(), null);
        Path dir = Path.of("target", "graph-ui-enterprise");
        Files.createDirectories(dir);
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", dir.resolve("panel-" + width + ".png").toFile());
        WritableImage page = root.snapshot(new SnapshotParameters(), null);
        ImageIO.write(SwingFXUtils.fromFXImage(page, null), "png", dir.resolve("page-" + width + ".png").toFile());
    }

    private static ToggleButton findToggle(Parent root, String text) {
        for (Node node : root.lookupAll(".graph-signal-panel .toggle-button")) {
            if (node instanceof ToggleButton button && text.equals(button.getText())) {
                return button;
            }
        }
        return null;
    }

    private static MenuButton findMenu(Parent root, String text) {
        for (Node node : root.lookupAll(".graph-signal-panel .menu-button")) {
            if (node instanceof MenuButton menu && text.equals(menu.getText())) {
                return menu;
            }
        }
        throw new AssertionError("missing menu " + text);
    }

    private static Button findButton(Parent root, String text) {
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button button && text.equals(button.getText()) && button.isManaged()) {
                return button;
            }
        }
        throw new AssertionError("missing " + text);
    }

    private static CanonicalSignal signal(String name, int canId, int channel) {
        return new CanonicalSignal(
                name, "Msg", 1_000_000L, canId, channel, 1, 1L, "km/h", null,
                new SourceReference(1, 1_000_000L));
    }

    private static void onFx(FxAction action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @FunctionalInterface
    private interface FxAction {
        void run() throws Exception;
    }
}
