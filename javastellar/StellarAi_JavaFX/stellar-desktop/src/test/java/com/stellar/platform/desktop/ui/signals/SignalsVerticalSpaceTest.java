package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.desktop.ui.SignalsController;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The data table must receive the height previously reserved by the blank action row.
 */
class SignalsVerticalSpaceTest {

    private static final int[][] SIZES = {
            {1706, 900}, {1440, 900}, {1280, 800}, {1100, 720}
    };

    /** Table host height measured before this change at 1706×900 with four signals. */
    private static final double BEFORE_TABLE_1706 = 558.7;

    @BeforeAll
    static void initJavaFx() throws Exception {
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
        System.out.println("SignalsVerticalSpace outputScaleX=" + scale.get());
    }

    @Test
    @Timeout(90)
    void tableGrowsAndChipsStayOneRow() throws Exception {
        SignalsScreenModel model = new SignalsScreenModel();
        try {
            for (int count : new int[] {0, 1, 2, 4, 12}) {
                for (int[] size : SIZES) {
                    onFx(() -> measure(model, count, size[0], size[1], false));
                }
            }
            for (int count : new int[] {1, 2, 4, 12}) {
                for (int[] size : SIZES) {
                    onFx(() -> measure(model, count, size[0], size[1], true));
                }
            }
        } finally {
            model.shutdown();
        }
    }

    @Test
    @Timeout(40)
    void focusTransitionsKeepSelectionAndRestoreTheBrowser() throws Exception {
        SignalsScreenModel model = new SignalsScreenModel();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        try {
            onFx(() -> {
                Loaded loaded = open(model, 4, 1440, 900);
                stageRef.set(loaded.stage());
                List<SignalCatalogKey> keys = List.copyOf(model.workspaceKeys());
                int reloads = loaded.workspace().reloadCountForTest();
                for (int i = 0; i < 2; i++) {
                    loaded.workspace().setFocusMode(WorkspaceFocusMode.FOCUS);
                    loaded.root().applyCss();
                    loaded.root().layout();
                    assertFalse(loaded.workbench().getChildren().contains(loaded.browser()),
                            "browser stayed in the workbench during focus");
                    assertTrue(loaded.workbench().getChildren().contains(loaded.workspace()));
                    assertEquals(keys, model.workspaceKeys());
                    assertEquals(reloads, loaded.workspace().reloadCountForTest());
                    assertChipOrder(loaded.workspace(), keys);
                    loaded.workspace().setFocusMode(WorkspaceFocusMode.NORMAL);
                    loaded.root().applyCss();
                    loaded.root().layout();
                    assertTrue(loaded.workbench().getChildren().contains(loaded.browser()),
                            "browser did not return after Exit Focus");
                    assertEquals(0, loaded.workbench().getChildren().indexOf(loaded.browser()));
                    assertEquals(keys, model.workspaceKeys());
                    assertEquals(reloads, loaded.workspace().reloadCountForTest());
                    assertChipOrder(loaded.workspace(), keys);
                }
                loaded.workspace().dispose();
            });
        } finally {
            onFx(() -> {
                if (stageRef.get() != null) {
                    stageRef.get().close();
                }
            });
            model.shutdown();
        }
    }

    private static void measure(SignalsScreenModel model, int count, int width, int height, boolean focus)
            throws Exception {
        Loaded loaded = open(model, count, width, height);
        try {
            if (focus) {
                loaded.workspace().setFocusMode(WorkspaceFocusMode.FOCUS);
                loaded.root().applyCss();
                loaded.root().layout();
            }
            Region chip = (Region) loaded.workspace().lookup(".signal-chip-strip");
            Region toolbar = (Region) loaded.workspace().lookup(".analysis-toolbar");
            Region tableHost = (Region) loaded.workspace().lookup(".workspace-table-host");
            Region footer = (Region) loaded.workspace().lookup(".workspace-table-footer");
            HBox command = (HBox) loaded.workspace().lookup(".workspace-command-bar");
            assertFalse(loaded.workspace().getChildren().stream().anyMatch(node ->
                    node.getStyleClass().contains("workspace-utility-row") && node.isManaged()));
            if (count == 0) {
                assertFalse(chip.isManaged());
                assertTrue(tableHost.getHeight() > 200, width + " empty table host " + tableHost.getHeight());
                if (width == 1706) {
                    System.out.println("VERTICAL_AFTER normal " + width + "x" + height
                            + " signals=0 tableH=" + tableHost.getHeight()
                            + " workspaceH=" + loaded.workspace().getHeight());
                }
                return;
            }
            assertTrue(chip.isManaged());
            assertTrue(chip.getHeight() <= 44, width + " chip strip grew " + chip.getHeight());
            HBox bar = (HBox) ((ScrollPane) loaded.workspace().lookup(".chip-scroll")).getContent();
            double rowY = bar.getChildren().isEmpty() ? 0 : bar.getChildren().getFirst().getLayoutY();
            for (Node node : bar.getChildren()) {
                assertEquals(rowY, node.getLayoutY(), 1.5, "chips wrapped");
            }
            assertChipOrder(loaded.workspace(), model.workspaceKeys());
            Bounds chipBounds = chip.localToScene(chip.getBoundsInLocal());
            Bounds toolbarBounds = toolbar.localToScene(toolbar.getBoundsInLocal());
            double gap = toolbarBounds.getMinY() - chipBounds.getMaxY();
            assertTrue(gap <= 12, mode(focus) + " " + width + " gap " + gap);
            assertTrue(toolbar.getHeight() <= 72, mode(focus) + " toolbar " + toolbar.getHeight());
            if (width == 1706) {
                assertTrue(toolbar.getHeight() <= 44,
                        mode(focus) + " " + width + " toolbar reserved a second row " + toolbar.getHeight());
            }
            assertTrue(tableHost.getHeight() > toolbar.getHeight());
            Bounds workspaceBounds = loaded.workspace().localToScene(loaded.workspace().getBoundsInLocal());
            Bounds footerBounds = footer.localToScene(footer.getBoundsInLocal());
            assertTrue(footer.isVisible());
            assertTrue(footerBounds.getMaxY() <= workspaceBounds.getMaxY() + 1.5,
                    mode(focus) + " footer clipped");
            assertTrue(footerBounds.getMinY() >= tableHost.localToScene(tableHost.getBoundsInLocal()).getMaxY() - 1.5);
            for (String label : List.of("Focus Table", "Copy Table", "Display Mode", "Navigate")) {
                if ("Focus Table".equals(label) && focus) {
                    continue;
                }
                Node node = findLabeled(loaded.workspace(), label);
                Bounds bounds = node.localToScene(node.getBoundsInLocal());
                assertTrue(bounds.getMaxX() <= workspaceBounds.getMaxX() + 1, label + " clipped");
                assertTrue(bounds.getHeight() > 8, label);
            }
            if (command != null) {
                assertTrue(command.localToScene(command.getBoundsInLocal()).getMaxY() <= chipBounds.getMinY() + 1);
            }
            double rows = Math.max(0, (tableHost.getHeight() - 56) / 39.0);
            if (count == 4 || (width == 1706 && (count == 1 || count == 2 || count == 12))) {
                System.out.println("VERTICAL_AFTER " + mode(focus) + " " + width + "x" + height
                        + " signals=" + count
                        + " chipH=" + chip.getHeight()
                        + " toolbarH=" + toolbar.getHeight()
                        + " gap=" + gap
                        + " tableH=" + tableHost.getHeight()
                        + " visibleRowSlots=" + rows
                        + " footerY=" + footerBounds.getMinY()
                        + " workspaceH=" + workspaceBounds.getHeight());
            }
            if (width == 1706 && height == 900 && count == 4 && !focus) {
                assertTrue(tableHost.getHeight() > BEFORE_TABLE_1706 + 30,
                        "table did not grow: before " + BEFORE_TABLE_1706 + " after " + tableHost.getHeight());
            }
        } finally {
            loaded.workspace().dispose();
            loaded.stage().close();
        }
    }

    private static Loaded open(SignalsScreenModel model, int count, int width, int height) throws Exception {
        FXMLLoader loader = new FXMLLoader(SignalsController.class.getResource("/fxml/signals-view.fxml"));
        VBox root = loader.load();
        SignalsController controller = loader.getController();
        Scene scene = new Scene(root, width, height);
        scene.getStylesheets().add(SignalsVerticalSpaceTest.class.getResource("/css/stellar-desktop.css").toExternalForm());
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        InMemorySignalCatalogStore store = store(Math.max(count, 1));
        model.replaceCatalog(store);
        ActiveSignalWorkspacePane workspace = (ActiveSignalWorkspacePane) root.lookup(".active-signal-workspace");
        workspace.setModel(model);
        model.clearWorkspace();
        if (count > 0) {
            model.addToWorkspace(model.catalog().entries().stream().limit(count).map(SignalCatalogEntry::key).toList());
            workspace.reload(true);
        } else {
            workspace.reload(true);
        }
        root.applyCss();
        root.resize(width, height);
        root.layout();
        HBox workbench = (HBox) root.lookup(".signals-workbench");
        controller.applyWorkspaceWidthForTest(workbench.getWidth() > 1 ? workbench.getWidth() : width - 24);
        root.resize(width + 1, height);
        root.layout();
        root.resize(width, height);
        root.applyCss();
        root.layout();
        SignalBrowserPane browser = (SignalBrowserPane) root.lookup(".signal-browser-pane");
        return new Loaded(stage, root, workbench, workspace, browser);
    }

    private static void assertChipOrder(ActiveSignalWorkspacePane workspace, List<SignalCatalogKey> keys) {
        HBox bar = (HBox) ((ScrollPane) workspace.lookup(".chip-scroll")).getContent();
        List<String> names = new ArrayList<>();
        for (Node node : bar.getChildren()) {
            if (!(node instanceof HBox chip)) {
                continue;
            }
            for (Node child : chip.getChildren()) {
                if (child instanceof Label label && label.getStyleClass().contains("signal-chip-name")) {
                    names.add(label.getText());
                }
            }
        }
        List<String> expected = keys.stream().map(SignalCatalogKey::signalName).toList();
        assertEquals(expected, names);
    }

    private static InMemorySignalCatalogStore store(int count) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 40);
        List<CanonicalSignal> samples = new ArrayList<>();
        String[] names = {
                "VehicleSpeed", "GPS_VERTICAL_SPEED", "GPS_GROUND_SPEED", "DRV_CLUSTER_DSPEED"
        };
        for (int i = 0; i < count; i++) {
            String name = i < names.length ? names[i] : "Signal" + i;
            samples.add(new CanonicalSignal(name, "Msg", 1_000_000L, 0x100 + i, 3,
                    i + 1.0, (long) i, "km/h", null, new SourceReference(1, 1_000_000L)));
        }
        store.accept(samples);
        return store;
    }

    private static Node findLabeled(Parent root, String text) {
        for (Node node : root.lookupAll(".label")) {
            if (node instanceof Label label && text.equals(label.getText())) {
                return label;
            }
        }
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button button && text.equals(button.getText())) {
                return button;
            }
        }
        throw new AssertionError("missing " + text);
    }

    private static String mode(boolean focus) {
        return focus ? "focus" : "normal";
    }

    private record Loaded(
            Stage stage,
            VBox root,
            HBox workbench,
            ActiveSignalWorkspacePane workspace,
            SignalBrowserPane browser
    ) {
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
