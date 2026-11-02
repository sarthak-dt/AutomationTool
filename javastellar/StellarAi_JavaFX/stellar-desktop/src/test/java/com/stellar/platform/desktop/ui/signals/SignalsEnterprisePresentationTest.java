package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.desktop.presentation.SignalBrowserFilter;
import com.stellar.platform.desktop.presentation.SignalsProcessingContext;
import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.desktop.ui.SignalsController;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signals page presentation: channel identity, single-line browser names, chips,
 * center headers, and toolbar reachability. Signal values and units are fixtures only.
 */
class SignalsEnterprisePresentationTest {

    private static final int[][] SIZES = {
            {1706, 900}, {1440, 900}, {1280, 800}, {1100, 720}
    };

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
        System.out.println("SignalsEnterprisePresentation outputScaleX=" + scale.get());
    }

    @Test
    @Timeout(90)
    void browserRowsKeepChannelIdentityAndCenterHeadersKeepUnits() throws Exception {
        SignalsScreenModel model = new SignalsScreenModel();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<ActiveSignalWorkspacePane> workspaceRef = new AtomicReference<>();
        try {
            onFx(() -> {
                InMemorySignalCatalogStore store = fixtureStore();
                model.replaceCatalog(store);
                SignalBrowserPane browser = new SignalBrowserPane();
                ActiveSignalWorkspacePane workspace = new ActiveSignalWorkspacePane();
                browser.setModel(model);
                browser.refreshFromModel();
                workspace.setModel(model);
                workspaceRef.set(workspace);
                browser.setAddToWorkspaceListener(entries -> {
                    model.addToWorkspace(entries.stream().map(SignalCatalogEntry::key).toList());
                    workspace.reload(true);
                });
                browser.setRemoveFromWorkspaceListener(entries -> {
                    for (SignalCatalogEntry entry : entries) {
                        model.removeFromWorkspace(entry.key());
                    }
                    workspace.reload(true);
                });
                HBox root = new HBox(8, browser, workspace);
                HBox.setHgrow(workspace, Priority.ALWAYS);
                browser.setMinWidth(320);
                browser.setPrefWidth(340);
                browser.setMaxWidth(350);
                Scene scene = new Scene(root, 1706, 900);
                scene.getStylesheets().add(stylesheet());
                Stage stage = new Stage();
                stageRef.set(stage);
                stage.setScene(scene);
                stage.show();
                List<SignalCatalogEntry> ordered = List.copyOf(model.catalog().entries());
                model.addToWorkspace(ordered.stream().map(SignalCatalogEntry::key).toList());
                workspace.reload(true);
                for (int[] size : SIZES) {
                    stage.setWidth(size[0]);
                    stage.setHeight(size[1]);
                    root.resize(size[0], size[1]);
                    root.applyCss();
                    root.layout();
                    browser.applyCss();
                    browser.layout();
                    workspace.applyCss();
                    workspace.layout();
                    assertBrowser(browser, size[0]);
                    assertChipsAndToolbar(workspace, ordered, size[0]);
                }
            });
            awaitRows(workspaceRef);
            onFx(() -> {
                ActiveSignalWorkspacePane workspace = workspaceRef.get();
                workspace.applyCss();
                workspace.layout();
                assertMissingAndValues(workspace, model);
                Button copy = field(workspace, "exportButton");
                assertEquals("Copy Table", copy.getText());
                assertFalse(copy.isDisabled());
                copy.fire();
                ComboBox<?> display = field(workspace, "displayModeBox");
                display.getSelectionModel().select(1);
                display.getSelectionModel().select(0);
                assertEquals("km/h", unitOf(model, "VehicleSpeed"));
                assertEquals("Km/h", unitOf(model, "DRV_CLUSTER_DSPEED"));
                assertEquals("", unitOf(model, "UnspecifiedSignal"));
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

    @Test
    @Timeout(60)
    void modeBadgeSearchAndKeyboardStayReadable() throws Exception {
        SignalsScreenModel model = new SignalsScreenModel();
        try {
            for (int[] size : SIZES) {
                onFx(() -> {
                    FXMLLoader loader = new FXMLLoader(SignalsController.class.getResource("/fxml/signals-view.fxml"));
                    VBox root = loader.load();
                    SignalsController controller = loader.getController();
                    Scene scene = new Scene(root, size[0], size[1]);
                    scene.getStylesheets().add(stylesheet());
                    Stage stage = new Stage();
                    stage.setScene(scene);
                    stage.show();
                    try {
                        root.applyCss();
                        root.resize(size[0], size[1]);
                        root.layout();
                        Label mode = field(controller, "contextModeBadge");
                        mode.setText(SignalsProcessingContext.MODE_QUICK_DECODE);
                        mode.applyCss();
                        root.layout();
                        assertEquals("AUTO DECODE", mode.getText());
                        assertTrue(mode.getWidth() + 1 >= mode.prefWidth(-1),
                                size[0] + " mode width " + mode.getWidth() + " pref " + mode.prefWidth(-1));
                        assertFalse(mode.getText().contains("..."));
                        Bounds modeBounds = mode.localToScene(mode.getBoundsInLocal());
                        for (String label : List.of("Analyze Signals", "Map Route", "Clear All")) {
                            ButtonBase button = findButton(root, label);
                            assertEquals(label, button.getText());
                            assertFalse(button.getText().contains("..."));
                            assertTrue(button.getWidth() + 1 >= button.prefWidth(-1),
                                    size[0] + " " + label + " width " + button.getWidth());
                            Bounds bounds = button.localToScene(button.getBoundsInLocal());
                            assertFalse(intersects(modeBounds, bounds), size[0] + " mode overlaps " + label);
                            assertTrue(bounds.getMaxX() <= size[0] + 1, size[0] + " " + label + " past the window");
                        }
                    } finally {
                        stage.close();
                    }
                });
            }
            onFx(() -> {
                SignalBrowserPane browser = new SignalBrowserPane();
                model.replaceCatalog(fixtureStore());
                browser.setModel(model);
                browser.refreshFromModel();
                Scene scene = new Scene(browser, 340, 720);
                scene.getStylesheets().add(stylesheet());
                Stage stage = new Stage();
                stage.setScene(scene);
                stage.show();
                try {
                    browser.applyCss();
                    browser.layout();
                    int all = browser.catalogTable().getItems().size();
                    assertTrue(all >= 8);
                    javafx.scene.control.TextField search = field(browser, "searchField");
                    search.setText("VehicleSpeed");
                    browser.runFilterNow();
                    assertEquals(1, browser.catalogTable().getItems().size());
                    assertEquals("VehicleSpeed", browser.catalogTable().getItems().getFirst().signalName());
                    search.clear();
                    browser.runFilterNow();
                    assertEquals(all, browser.catalogTable().getItems().size());
                    @SuppressWarnings("unchecked")
                    ComboBox<SignalBrowserFilter.SourceFilter> type = field(browser, "typeCombo");
                    type.getSelectionModel().select(SignalBrowserFilter.SourceFilter.MF4_NATIVE);
                    assertTrue(browser.catalogTable().getItems().stream()
                            .allMatch(e -> "Native".equals(SignalRowTooltip.sourceLabel(e.sourceType()))));
                    assertFalse(browser.catalogTable().getItems().isEmpty());
                    type.getSelectionModel().select(SignalBrowserFilter.SourceFilter.ALL);
                    assertEquals(all, browser.catalogTable().getItems().size());

                    TableView<SignalCatalogEntry> table = browser.catalogTable();
                    table.requestFocus();
                    table.getSelectionModel().select(0);
                    table.fireEvent(new KeyEvent(
                            KeyEvent.KEY_PRESSED, "", "", KeyCode.DOWN, false, false, false, false));
                    assertTrue(table.getSelectionModel().getSelectedIndex() >= 0);
                    assertTrue(table.isFocusTraversable());
                    assertTrue(search.isFocusTraversable());
                } finally {
                    stage.close();
                }
            });
        } finally {
            model.shutdown();
        }
    }

    private static void assertBrowser(SignalBrowserPane browser, int width) throws Exception {
        TableView<SignalCatalogEntry> table = browser.catalogTable();
        assertTrue(table.lookupAll(".browser-signal-meta").isEmpty(), width + " unit line still rendered");
        assertTrue(table.getColumns().get(3).getWidth() + 0.5 >= 58, width + " channel col " + table.getColumns().get(3).getWidth());
        assertTrue(table.getColumns().get(2).getWidth() + 0.5 >= 64, width + " source col " + table.getColumns().get(2).getWidth());
        assertTrue(table.getColumns().get(4).getWidth() + 0.5 >= 68, width + " can col " + table.getColumns().get(4).getWidth());
        long horizontal = table.lookupAll(".scroll-bar").stream()
                .filter(ScrollBar.class::isInstance)
                .map(ScrollBar.class::cast)
                .filter(bar -> bar.getOrientation() == javafx.geometry.Orientation.HORIZONTAL && bar.isVisible())
                .count();
        assertTrue(horizontal <= 1, width + " horizontal bars " + horizontal);
        Label speed = requireName(table, "VehicleSpeed");
        assertEquals("VehicleSpeed", speed.getText());
        assertFalse(speed.getText().contains("km/h"));
        String tip = speed.getTooltip().getText();
        assertTrue(tip.contains("VehicleSpeed"));
        assertTrue(tip.contains("Unit: km/h"), tip);
        assertTrue(tip.contains("Source: DBC"), tip);
        assertTrue(tip.contains("Channel: Ch 3"), tip);
        assertTrue(tip.contains("CAN ID: 0x38D"), tip);
        Label channel = channelLabel(speed);
        assertEquals("Ch 3", channel.getText());
        assertTrue(channel.getWidth() + 1 >= channel.prefWidth(-1),
                width + " channel clipped " + channel.getWidth() + " pref " + channel.prefWidth(-1));
        assertInside(table, channel, width + " channel");
        assertInside(table, speed, width + " name");
        Label cluster = requireName(table, "DRV_CLUSTER_DSPEED");
        assertTrue(cluster.getTooltip().getText().contains("Unit: Km/h"));
        assertEquals("Ch 3", channelLabel(cluster).getText());
        Label blank = requireName(table, "UnspecifiedSignal");
        assertTrue(blank.getTooltip().getText().contains("Unit: \u2014"), blank.getTooltip().getText());
        assertEquals("Ch 2", channelLabel(blank).getText());
        Label engine = requireName(table, "EngineSpeed");
        assertTrue(engine.getTooltip().getText().contains("Unit: rpm"));
        assertEquals("Ch 1", channelLabel(engine).getText());
        Label measured = requireName(table, "BatteryVoltage");
        assertTrue(measured.getTooltip().getText().contains("Source: Native"));
        assertEquals("\u2014", channelLabel(measured).getText());
        Label longName = requireName(table, "Battery_SOC_VeryLongEngineeringSignalName_ForBrowserEllipsis");
        assertTrue(longName.getTooltip().getText().startsWith(
                "Battery_SOC_VeryLongEngineeringSignalName_ForBrowserEllipsis"));
        assertTrue(longName.getWidth() + 4 < longName.prefWidth(-1),
                width + " long name was not ellipsized " + longName.getWidth());
        String source = sourceText(speed);
        assertEquals("DBC", source);
        String can = canText(speed);
        assertEquals("0x38D", can);
        assertNoNameChannelOverlap(table);
    }

    private static void assertChipsAndToolbar(ActiveSignalWorkspacePane workspace, List<SignalCatalogEntry> ordered, int width) throws Exception {
        assertEquals(ordered.size(), workspace.previewChipCountForTest());
        HBox bar = field(workspace, "chipBar");
        List<HBox> chips = bar.getChildren().stream().filter(HBox.class::isInstance).map(HBox.class::cast).toList();
        assertEquals(ordered.size(), chips.size());
        Bounds previous = null;
        for (int i = 0; i < chips.size(); i++) {
            HBox chip = chips.get(i);
            assertEquals(ordered.get(i).signalName(), chipName(chip).getText());
            Label name = chipName(chip);
            assertTrue(name.getTooltip().getText().contains(ordered.get(i).signalName()));
            assertTrue(name.getTooltip().getText().contains("Source:"));
            assertTrue(name.getTooltip().getText().contains("Unit:"));
            Button remove = chip.getChildren().stream()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .filter(button -> "\u00d7".equals(button.getText()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(remove.getWidth() >= 12 || remove.prefWidth(-1) >= 12);
            Bounds bounds = chip.localToScene(chip.getBoundsInLocal());
            if (previous != null && Math.abs(bounds.getMinY() - previous.getMinY()) < 2) {
                assertFalse(intersects(previous, bounds), width + " chips overlap");
            }
            previous = bounds;
        }
        assertHeader(workspace, "VehicleSpeed", "Ch 3", "km/h");
        assertHeader(workspace, "DRV_CLUSTER_DSPEED", "Ch 3", "Km/h");
        assertHeader(workspace, "GPS_VERTICAL_SPEED", "Ch 3", "m/s");
        assertHeader(workspace, "EngineSpeed", "Ch 1", "rpm");
        assertHeaderAbsentUnit(workspace, "UnspecifiedSignal");
        for (String label : List.of("Display Mode", "Precision", "Rows", "Navigate")) {
            Label text = findLabel(workspace, label);
            assertEquals(label, text.getText());
            assertTrue(text.getWidth() + 1 >= text.prefWidth(-1) || text.getWidth() <= 1,
                    width + " " + label + " clipped");
        }
        Button go = field(workspace, "navigateGoButton");
        Button copy = field(workspace, "exportButton");
        Button focus = field(workspace, "focusTableButton");
        Bounds workspaceBounds = workspace.localToScene(workspace.getBoundsInLocal());
        for (Button button : List.of(go, copy, focus)) {
            Bounds bounds = button.localToScene(button.getBoundsInLocal());
            assertTrue(bounds.getWidth() > 8, button.getText());
            assertTrue(bounds.getMaxX() <= workspaceBounds.getMaxX() + 1, width + " " + button.getText());
            assertTrue(bounds.getMaxY() <= workspaceBounds.getMaxY() + 1, width + " " + button.getText());
        }
        assertTrue(workspace.toolbarHeightForTest() <= 76, width + " toolbar " + workspace.toolbarHeightForTest());
    }

    private static void assertMissingAndValues(ActiveSignalWorkspacePane workspace, SignalsScreenModel model) throws Exception {
        workspace.table().applyCss();
        workspace.table().layout();
        List<TableCell<?, ?>> cells = new ArrayList<>();
        for (Node node : workspace.table().lookupAll(".workspace-signal-cell")) {
            if (node instanceof TableCell<?, ?> cell && cell.getText() != null && !cell.getText().isBlank()) {
                cells.add(cell);
            }
        }
        assertFalse(cells.isEmpty(), "signal cells were not realized");
        boolean dash = false;
        boolean value = false;
        for (TableCell<?, ?> cell : cells) {
            if ("\u2014".equals(cell.getText())) {
                dash = true;
                assertTrue(cell.getTooltip() != null
                                && cell.getTooltip().getText().contains("No sample for this signal at this timestamp."),
                        cell.getTooltip() == null ? "" : cell.getTooltip().getText());
            } else if (cell.getText().contains("42")) {
                value = true;
                assertFalse("\u2014".equals(cell.getText()));
            }
        }
        assertTrue(dash, "aligned gap was not kept as an em dash");
        assertTrue(value, "physical sample 42 was not shown");
        assertEquals("km/h", unitOf(model, "VehicleSpeed"));
    }

    private static void assertHeader(Parent workspace, String name, String channel, String unit) {
        VBox header = header(workspace, name);
        assertEquals(channel, textWithStyle(header, "workspace-column-header-meta"));
        assertEquals(unit, textWithStyle(header, "workspace-column-header-unit"));
        Label unitLabel = labelWithStyle(header, "workspace-column-header-unit");
        assertTrue(unitLabel.getHeight() > 8, name + " unit height " + unitLabel.getHeight());
        Label nameLabel = labelWithStyle(header, "workspace-column-header-name");
        assertTrue(nameLabel.getTooltip() != null && nameLabel.getTooltip().getText().contains(name));
        assertTrue(nameLabel.getTooltip().getText().contains("Unit: " + unit));
    }

    private static void assertHeaderAbsentUnit(Parent workspace, String name) {
        VBox header = header(workspace, name);
        assertTrue(header.lookupAll(".workspace-column-header-unit").isEmpty(), name + " invented a unit");
    }

    private static VBox header(Parent workspace, String name) {
        for (Node node : workspace.lookupAll(".workspace-column-header")) {
            if (node instanceof VBox box && name.equals(textWithStyle(box, "workspace-column-header-name"))) {
                return box;
            }
        }
        throw new AssertionError("missing header " + name);
    }

    private static InMemorySignalCatalogStore fixtureStore() {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 200);
        List<CanonicalSignal> samples = new ArrayList<>();
        samples.add(signal("EngineSpeed", "Msg", 1_000_000L, 0x10, 1, 900, "rpm"));
        samples.add(signal("UnspecifiedSignal", "Msg", 1_000_000L, 0x20, 2, 1, ""));
        samples.add(signal("VehicleSpeed", "Msg", 1_000_000_000L, 0x38D, 3, 42, "km/h"));
        samples.add(signal("DRV_CLUSTER_DSPEED", "Msg", 1_000_000_000L, 0x100, 3, 7, "Km/h"));
        samples.add(signal("GPS_VERTICAL_SPEED", "Msg", 5_000_000_000L, 0x201, 3, 3, "m/s"));
        samples.add(signal("GPS_GROUND_SPEED", "Msg", 5_000_000_000L, 0x202, 3, 4, "m/s"));
        samples.add(signal(
                "Battery_SOC_VeryLongEngineeringSignalName_ForBrowserEllipsis",
                "Msg", 1_000_000L, 0x300, 3, 11, "km/h"));
        for (int i = 0; i < 4; i++) {
            samples.add(signal("Signal" + i, "Msg", 1_000_000L, 0x400 + i, 3, i + 1, "km/h"));
        }
        samples.add(new CanonicalSignal(
                "BatteryVoltage",
                InMemorySignalCatalogStore.MF4_MEASUREMENT_MESSAGE,
                1_000_000L, 0, 0, 12.4, 12L, "", null, new SourceReference(1, 1_000_000L)));
        store.accept(samples);
        return store;
    }

    private static CanonicalSignal signal(
            String name, String message, long timestamp, long canId, int channel, double value, String unit) {
        return new CanonicalSignal(
                name, message, timestamp, canId, channel, value, (long) value, unit, null,
                new SourceReference(1, timestamp));
    }

    private static void awaitRows(AtomicReference<ActiveSignalWorkspacePane> workspace) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            AtomicReference<Integer> rows = new AtomicReference<>(0);
            onFx(() -> rows.set(workspace.get().table().getItems().size()));
            if (rows.get() >= 2) {
                return;
            }
            Thread.sleep(40);
        }
        throw new AssertionError("aligned rows did not arrive");
    }

    private static String unitOf(SignalsScreenModel model, String name) {
        return model.catalog().entries().stream()
                .filter(entry -> name.equals(entry.signalName()))
                .findFirst()
                .orElseThrow()
                .unit();
    }

    private static Label requireName(TableView<?> table, String name) {
        int index = -1;
        for (int i = 0; i < table.getItems().size(); i++) {
            Object item = table.getItems().get(i);
            if (item instanceof SignalCatalogEntry entry && name.equals(entry.signalName())) {
                index = i;
                break;
            }
        }
        if (index >= 0) {
            table.scrollTo(index);
        }
        table.layout();
        for (Node node : table.lookupAll(".browser-signal-name")) {
            if (node instanceof Label label && name.equals(label.getText())) {
                return label;
            }
        }
        throw new AssertionError("missing browser name " + name);
    }

    private static Label channelLabel(Label name) {
        TableRow<?> row = rowOf(name);
        Node channel = row.lookup(".browser-channel-label");
        assertTrue(channel instanceof Label, "channel label");
        return (Label) channel;
    }

    private static String sourceText(Label name) {
        TableRow<?> row = rowOf(name);
        for (Node node : row.lookupAll(".table-cell")) {
            if (node instanceof TableCell<?, ?> cell && "DBC".equals(cell.getText())) {
                return cell.getText();
            }
        }
        throw new AssertionError("DBC source cell missing");
    }

    private static String canText(Label name) {
        TableRow<?> row = rowOf(name);
        for (Node node : row.lookupAll(".table-cell")) {
            if (node instanceof TableCell<?, ?> cell && cell.getText() != null && cell.getText().startsWith("0x")) {
                return cell.getText();
            }
        }
        throw new AssertionError("CAN cell missing");
    }

    private static void assertNoNameChannelOverlap(TableView<?> table) {
        for (Node node : table.lookupAll(".browser-signal-name")) {
            if (!(node instanceof Label name) || name.getText() == null || name.getText().isBlank()) {
                continue;
            }
            Label channel = channelLabel(name);
            assertFalse(intersects(
                    name.localToScene(name.getBoundsInLocal()),
                    channel.localToScene(channel.getBoundsInLocal())));
        }
    }

    @SuppressWarnings("unchecked")
    private static TableRow<?> rowOf(Node node) {
        Node current = node;
        while (current != null && !(current instanceof TableRow<?>)) {
            current = current.getParent();
        }
        if (current == null) {
            throw new AssertionError("row missing");
        }
        return (TableRow<?>) current;
    }

    private static Label chipName(HBox chip) {
        return chip.getChildren().stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .filter(label -> label.getStyleClass().contains("signal-chip-name"))
                .findFirst()
                .orElseThrow();
    }

    private static String textWithStyle(Parent parent, String style) {
        Label label = labelWithStyle(parent, style);
        return label.getText();
    }

    private static Label labelWithStyle(Parent parent, String style) {
        for (Node node : parent.lookupAll("." + style)) {
            if (node instanceof Label label) {
                return label;
            }
        }
        throw new AssertionError("missing " + style);
    }

    private static Label findLabel(Parent root, String text) {
        for (Node node : root.lookupAll(".label")) {
            if (node instanceof Label label && text.equals(label.getText())) {
                return label;
            }
        }
        throw new AssertionError("missing label " + text);
    }

    private static ButtonBase findButton(Parent root, String text) {
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof ButtonBase button && text.equals(button.getText())) {
                return button;
            }
        }
        throw new AssertionError("missing button " + text);
    }

    private static void assertInside(Node container, Node child, String where) {
        Bounds outer = container.localToScene(container.getBoundsInLocal());
        Bounds inner = child.localToScene(child.getBoundsInLocal());
        assertTrue(inner.getMinX() >= outer.getMinX() - 1 && inner.getMaxX() <= outer.getMaxX() + 1,
                where + " x " + inner + " outside " + outer);
        assertTrue(inner.getMinY() >= outer.getMinY() - 1 && inner.getMaxY() <= outer.getMaxY() + 1,
                where + " y " + inner + " outside " + outer);
    }

    private static boolean intersects(Bounds a, Bounds b) {
        return a.getMinX() < b.getMaxX() - 1
                && a.getMaxX() > b.getMinX() + 1
                && a.getMinY() < b.getMaxY() - 1
                && a.getMaxY() > b.getMinY() + 1;
    }

    private static String stylesheet() {
        var css = SignalsEnterprisePresentationTest.class.getResource("/css/stellar-desktop.css");
        if (css == null) {
            throw new AssertionError("stylesheet missing");
        }
        return css.toExternalForm();
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Field found = target.getClass().getDeclaredField(name);
        found.setAccessible(true);
        return (T) found.get(target);
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
