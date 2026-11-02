package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.desktop.presentation.SignalBrowserFilter;
import com.stellar.platform.desktop.presentation.SignalBrowserViewMode;
import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.desktop.ui.SignalsController;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TableColumn;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signals workstation hierarchy: compact browser, usable table, stable return from Graphs.
 */
class SignalsWorkstationRedesignTest {

    private static final String LONG_MF4 =
            "MF4_KA04NC7760_Bengaluru_0002360_20230814_164651_processed_measurement_file.mf4";

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
    @Timeout(60)
    void laptopLayoutsKeepTheTableClearOfControls() throws Exception {
        for (int[] size : new int[][] {
                {1280, 720}, {1366, 768}, {1536, 864}, {1600, 900}, {1680, 900}, {1920, 1080}}) {
            onSignals(size[0], size[1], false, (root, controller, workbench) -> {
                SignalBrowserPane browser = find(root, SignalBrowserPane.class);
                ActiveSignalWorkspacePane workspace = find(root, ActiveSignalWorkspacePane.class);
                for (String style : List.of("workspace-toolbar", "workspace-command-row")) {
                    Node chrome = find(workspace, Parent.class, style);
                    chrome.setManaged(true);
                    chrome.setVisible(true);
                }
                root.applyCss();
                root.layout();
                Node search = find(browser, Parent.class, "search-field-shell");
                TableView<?> catalog = browser.catalogTable();
                TableView<?> table = workspace.table();
                Region tableHost = find(workspace, Region.class, "workspace-table-host");

                assertTrue(tableHost.getHeight() > 160,
                        size[0] + " table host " + tableHost.getHeight());
                assertTrue(catalog.getHeight() > 240, size[0] + " catalog " + catalog.getHeight());
                assertTrue(workspace.getWidth() > browser.getWidth(),
                        size[0] + " center " + workspace.getWidth() + " browser " + browser.getWidth());
                assertFalse(browser.measurementChooserInDefaultColumnForTest());
                assertFalse(browser.dbcFilterInDefaultColumnForTest());
                assertFalse(browser.typeFilterInDefaultColumnForTest());
                assertFalse(browser.hideDuplicatesInDefaultColumnForTest());
                assertEquals("All", browser.signalSourceLabelForTest(SignalBrowserFilter.SourceFilter.ALL));
                assertEquals("Native", browser.signalSourceLabelForTest(SignalBrowserFilter.SourceFilter.MF4_NATIVE));
                assertEquals("Derived", browser.signalSourceLabelForTest(SignalBrowserFilter.SourceFilter.DERIVED));
                assertNoOverlap(search, catalog, "search/catalog @" + size[0]);
                assertNoOverlap(catalog, find(browser, Region.class, "browser-health-card"),
                        "catalog/health @" + size[0]);
                assertEquals(Priority.ALWAYS, VBox.getVgrow(tableHost));
                assertTrue(table.getMaxHeight() > 1000);
                HBox measurementCard = field(controller, "contextMeasurementCard");
                assertNotNull(measurementCard.getOnMouseClicked());
                assertTrue(textOf(root, "MEASUREMENT"));
                assertTrue(textOf(root, "APPLIED DBC") || textOf(root, "APPLIED DBCS"));

                assertFalse(textOf(root, "Change Channels"));
                assertFalse(textOf(root, "View Processing Summary"));
                HBox headerActions = field(workspace, "headerActions");
                assertTrue(headerActions.getChildren().stream().noneMatch(node ->
                        node instanceof Labeled labeled && "Add Selected".equals(labeled.getText())));
                assertEquals(1, countText(root, "Analyze Signals"));
                assertEquals(1, countText(root, "Clear All"));
                ActiveSignalWorkspacePane workspacePane = find(root, ActiveSignalWorkspacePane.class);
                int reloadsBeforeLayout = workspacePane.reloadCountForTest();
                root.applyCss();
                root.layout();
                assertEquals(reloadsBeforeLayout, workspacePane.reloadCountForTest(),
                        size[0] + " layout reloaded samples");
                assertTrue(workspacePane.contextActionsMountedForTest());
                assertFalse(workspacePane.analyzeInWorkspaceHeaderForTest());
                assertFalse(workspacePane.clearAllInWorkspaceHeaderForTest());
                assertTrue(workspacePane.analyzeButtonForTest().getParent().getStyleClass()
                        .contains("signals-context-actions"));
                assertTrue(workspacePane.toolbarHeightForTest() <= 76,
                        size[0] + " toolbar " + workspacePane.toolbarHeightForTest());
                HBox toolbar = field(workspacePane, "toolbar");
                HBox utilityActions = field(workspacePane, "toolbarActions");
                Node displayGroup = field(workspacePane, "displayGroup");
                Node navigateGroup = field(workspacePane, "navigateGroup");
                Region chipStrip = field(workspacePane, "chipStrip");
                Button settingsButton = field(workspacePane, "settingsButton");
                HBox statusStrip = field(workspacePane, "statusStrip");
                assertEquals(headerActions, utilityActions.getParent(),
                        size[0] + " table actions are not on the title row");
                assertEquals(utilityActions, findButton(root, "Focus Table").getParent());
                assertEquals(utilityActions, settingsButton.getParent());
                assertEquals(utilityActions, findButton(root, "Copy Table").getParent());
                assertFalse(toolbar.getChildren().contains(utilityActions),
                        size[0] + " utility actions still occupy the data toolbar");
                HBox utilityRow = field(workspacePane, "utilityRow");
                assertFalse(utilityRow.isManaged(), size[0] + " utility row still consumes vertical space");
                assertFalse(workspacePane.getChildren().contains(utilityRow),
                        size[0] + " utility row is still in the workspace stack");
                if (chipStrip.isManaged()) {
                    Bounds chipBounds = chipStrip.localToScene(chipStrip.getBoundsInLocal());
                    Bounds toolbarBounds = toolbar.localToScene(toolbar.getBoundsInLocal());
                    assertTrue(toolbarBounds.getMinY() - chipBounds.getMaxY() <= 12,
                            size[0] + " gap between chips and toolbar "
                                    + (toolbarBounds.getMinY() - chipBounds.getMaxY()));
                }
                Bounds headerBounds = headerActions.localToScene(headerActions.getBoundsInLocal());
                assertTrue(utilityActions.localToScene(utilityActions.getBoundsInLocal()).getMaxX()
                                >= headerBounds.getMaxX() - 8,
                        size[0] + " utility actions are not right-aligned");
                assertEquals(toolbar, displayGroup.getParent(), size[0] + " Display Mode left the toolbar");
                Bounds displayBounds = displayGroup.localToScene(displayGroup.getBoundsInLocal());
                Bounds navigateBounds = navigateGroup.localToScene(navigateGroup.getBoundsInLocal());
                assertEquals(toolbar, navigateGroup.getParent(),
                        size[0] + " " + workspacePane.toolbarStructureForTest());
                assertTrue(workspacePane.toolbarStructureForTest().contains("navigateParent=toolbar"),
                        workspacePane.toolbarStructureForTest());
                assertFalse(textOf(workspacePane, "Go to Index"), size[0] + " old index group remains");
                assertFalse(textOf(workspacePane, "Go to Time (s)"), size[0] + " old time group remains");
                assertEquals(1, countText(workspacePane, "Navigate"), size[0] + " Navigate control count");
                assertEquals(workspacePane.getChildren().indexOf(toolbar) + 1,
                        workspacePane.getChildren().indexOf(statusStrip),
                        size[0] + " status row is not directly under the toolbar");
                assertTrue(Math.abs(displayBounds.getMinY() - navigateBounds.getMinY()) <= 4,
                        size[0] + " Navigate is on a second row");
                assertTrue(workspacePane.toolbarHeightForTest() <= SignalsLayoutMetrics.TOOLBAR_HEIGHT + 4,
                        size[0] + " toolbar " + workspacePane.toolbarHeightForTest());
                Bounds workspaceBounds = workspace.localToScene(workspace.getBoundsInLocal());
                for (String label : List.of("Display Mode", "Precision", "Rows", "Navigate")) {
                    Labeled text = findLabel(workspacePane, label);
                    assertFalse(text.getText().contains("..."), label + " abbreviated");
                    assertTrue(text.getWidth() + 1 >= text.prefWidth(-1),
                            size[0] + " " + label + " clipped " + text.getWidth()
                                    + " pref " + text.prefWidth(-1));
                }
                Button go = field(workspacePane, "navigateGoButton");
                assertTrue(go.localToScene(go.getBoundsInLocal()).getMaxX()
                                <= workspaceBounds.getMaxX() + 1,
                        size[0] + " Go clipped past the workspace");
                Bounds tableBounds = table.localToScene(table.getBoundsInLocal());
                assertTrue(navigateBounds.getMaxY() <= tableBounds.getMinY() + 1,
                        size[0] + " navigation floats into the table");
                assertTrue(browser.getMinWidth() >= 300, size[0] + " browser min " + browser.getMinWidth());
                assertTrue(browser.getWidth() >= 300, size[0] + " browser width " + browser.getWidth());
                assertTrue(workspace.getWidth() > browser.getWidth(),
                        size[0] + " table region " + workspace.getWidth());
                TableColumn<?, ?> signalColumn = catalog.getColumns().get(1);
                TableColumn<?, ?> sourceColumn = catalog.getColumns().get(2);
                TableColumn<?, ?> channelColumn = catalog.getColumns().get(3);
                TableColumn<?, ?> canColumn = catalog.getColumns().get(4);
                assertEquals("Signal", signalColumn.getText());
                assertTrue(columnWidth(signalColumn) >= 64, size[0] + " signal " + columnWidth(signalColumn));
                assertTrue(columnWidth(sourceColumn) >= 48, size[0] + " source " + columnWidth(sourceColumn));
                assertTrue(columnWidth(channelColumn) >= 32, size[0] + " channel " + columnWidth(channelColumn));
                assertTrue(columnWidth(canColumn) >= 48, size[0] + " can " + columnWidth(canColumn));
                for (String label : List.of(
                        "Select All", "Deselect All",
                        "Analyze Signals", "Clear All", "Focus Table", "Copy Table")) {
                    ButtonBase button = findButton(root, label);
                    assertEquals(label, button.getText());
                    assertFalse(button.getText().contains("..."));
                    assertTrue(button.getHeight() <= 32, label + " height " + button.getHeight());
                    assertTrue(button.getWidth() + 1 >= button.prefWidth(-1) || button.getWidth() <= 1,
                            label + " clipped " + button.getWidth() + " pref " + button.prefWidth(-1));
                }
            });
        }
    }

    @Test
    @Timeout(40)
    void defaultBrowserOmitsDuplicateContextFilters() throws Exception {
        onFx(() -> {
            SignalBrowserPane pane = styledBrowser(280, 640);
            assertFalse(pane.measurementChooserInDefaultColumnForTest());
            assertFalse(pane.dbcFilterInDefaultColumnForTest());
            assertFalse(pane.typeFilterInDefaultColumnForTest());
            assertFalse(pane.hideDuplicatesInDefaultColumnForTest());
            assertEquals("Select All", pane.selectAllButtonForTest().getText());
            assertEquals("Deselect All", pane.deselectAllButtonForTest().getText());
            assertTrue(pane.selectAllButtonForTest().getHeight() > 0);
            assertTrue(pane.catalogTable().getHeight() > 360, "catalog " + pane.catalogTable().getHeight());
            assertEquals("", pane.filtersBadgeTextForTest());
        });
    }

    @Test
    @Timeout(40)
    void signalSourceAndDbcFiltersStayAvailable() throws Exception {
        onFx(() -> {
            SignalBrowserPane pane = styledBrowser(280, 480);
            assertEquals("All", pane.signalSourceLabelForTest(SignalBrowserFilter.SourceFilter.ALL));
            assertEquals("DBC", pane.signalSourceLabelForTest(SignalBrowserFilter.SourceFilter.DBC));
            assertEquals("Native", pane.signalSourceLabelForTest(SignalBrowserFilter.SourceFilter.MF4_NATIVE));
            assertEquals("Derived", pane.signalSourceLabelForTest(SignalBrowserFilter.SourceFilter.DERIVED));
            ComboBox<?> dbc = field(pane, "dbcCombo");
            assertNotNull(dbc);
            assertFalse(pane.dbcFilterInDefaultColumnForTest());
        });
    }

    @Test
    @Timeout(40)
    void browserListScrollsAndFiltersStillWork() throws Exception {
        SignalsScreenModel model = new SignalsScreenModel();
        try {
            onFx(() -> {
                InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 80);
                List<CanonicalSignal> samples = new ArrayList<>();
                for (int i = 0; i < 24; i++) {
                    samples.add(new CanonicalSignal(
                            i == 0 ? "VehicleSpeed" : "Pressure" + i,
                            "Msg", 1_000_000_000L, 0x100, 1,
                            i, (long) i, "u", null, SourceReference.ofLine(1)));
                }
                samples.add(new CanonicalSignal(
                        "VehicleSpeed", "Msg", 2_000_000_000L, 0x100, 2,
                        1, 1L, "u", null, SourceReference.ofLine(2)));
                store.accept(samples);
                model.replaceCatalog(store);
                SignalBrowserPane pane = styledBrowser(280, 460);
                pane.setModel(model);
                pane.layout();
                TableView<?> catalog = pane.catalogTable();
                assertEquals(Priority.ALWAYS, VBox.getVgrow(catalog.getParent()));
                assertTrue(catalog.getItems().size() > 8);
                assertTrue(catalog.getHeight() > 80, "catalog has no room to scroll: " + catalog.getHeight());
                assertTrue(catalog.getHeight() + 20 < catalog.getItems().size() * catalog.getFixedCellSize(),
                        "catalog does not overflow its viewport");
                assertFalse(pane.measurementChooserInDefaultColumnForTest());
                assertFalse(pane.dbcFilterInDefaultColumnForTest());

                pane.searchField().setText("VehicleSpeed");
                pane.runFilterNow();
                assertFalse(catalog.getItems().isEmpty(), "search removed every row");
                assertTrue(catalog.getItems().size() < 20, "search did not filter: " + catalog.getItems().size());

                ButtonBase hide = field(pane, "hideDuplicatesToggle");
                hide.fire();
                assertEquals(SignalBrowserViewMode.UNIQUE_SIGNAL_NAMES, model.browserViewMode());
                hide.fire();
                assertEquals(SignalBrowserViewMode.ALL_INSTANCES, model.browserViewMode());

                AtomicBoolean selected = new AtomicBoolean();
                pane.setOnSelectAll(() -> selected.set(true));
                pane.selectAllButtonForTest().fire();
                assertTrue(selected.get());
                pane.deselectAllButtonForTest().fire();

                @SuppressWarnings("unchecked")
                ComboBox<SignalBrowserFilter.SourceFilter> sourceKind = field(pane, "typeCombo");
                sourceKind.getSelectionModel().select(SignalBrowserFilter.SourceFilter.DERIVED);
                assertEquals("1", pane.filtersBadgeTextForTest());
                assertEquals("Filters (1)", pane.moreFiltersTextForTest());
                assertTrue(catalog.getItems().isEmpty(), "derived filter kept unrelated rows");
                sourceKind.getSelectionModel().select(SignalBrowserFilter.SourceFilter.DBC);
                assertFalse(catalog.getItems().isEmpty(), "DBC filter removed every DBC row");
                @SuppressWarnings("unchecked")
                ComboBox<String> measurement = field(pane, "sourceCombo");
                if (measurement.getItems().size() > 1) {
                    measurement.getSelectionModel().select(1);
                }
                assertEquals("1", pane.filtersBadgeTextForTest(), "measurement scope counted as a filter");
                Button reset = field(pane, "clearFiltersButton");
                reset.fire();
                assertEquals("", pane.filtersBadgeTextForTest());
                assertEquals(SignalBrowserFilter.SourceFilter.ALL, sourceKind.getValue());
            });
        } finally {
            model.shutdown();
        }
    }

    @Test
    @Timeout(40)
    void chipsStayCompactAndScroll() throws Exception {
        SignalsScreenModel model = new SignalsScreenModel();
        try {
            onFx(() -> {
                InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 40);
                List<CanonicalSignal> samples = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    samples.add(new CanonicalSignal(
                            "VeryLongEngineeringSignalName" + i, "Msg", 1_000_000_000L, 0x100, i + 1,
                            i, (long) i, "km/h", null, SourceReference.ofLine(1)));
                }
                store.accept(samples);
                model.replaceCatalog(store);
                model.addToWorkspace(model.catalog().entries().stream().map(e -> e.key()).toList());
                ActiveSignalWorkspacePane pane = new ActiveSignalWorkspacePane();
                pane.setModel(model);
                Scene scene = new Scene(pane, 520, 640);
                scene.getStylesheets().add(stylesheet());
                pane.applyCss();
                pane.resize(520, 640);
                pane.layout();
                ScrollPane chips = pane.chipScrollForTest();
                assertTrue(chips.getMaxHeight() <= SignalsLayoutMetrics.CHIP_ROW_HEIGHT + 16);
                assertTrue(pane.chipCountForTest() >= 8);
                assertEquals(ScrollPane.ScrollBarPolicy.NEVER, chips.getVbarPolicy());
            });
        } finally {
            model.shutdown();
        }
    }

    @Test
    @Timeout(40)
    void collapseBrowserAndDetails() throws Exception {
        onSignals(1366, 768, false, (root, controller, workbench) -> {
            controller.collapseSignalBrowserForTest();
            root.layout();
            assertTrue(controller.signalBrowserCollapsedForTest());
            assertFalse(workbench.getChildren().getFirst() instanceof SignalBrowserPane);
            controller.expandSignalBrowserForTest();
            root.layout();
            assertFalse(controller.signalBrowserCollapsedForTest());

            SignalInspectorPane inspector = find(root, SignalInspectorPane.class);
            boolean opened = inspector.isCollapsed();
            inspector.setCollapsed(!opened);
            root.layout();
            assertEquals(!opened, inspector.isCollapsed());
            if (inspector.isCollapsed()) {
                assertTrue(inspector.getMaxWidth() <= SignalsLayoutMetrics.DETAILS_RAIL_WIDTH + 1,
                        "collapsed details width " + inspector.getMaxWidth());
            }
            inspector.setCollapsed(opened);
            root.layout();
            assertEquals(opened, inspector.isCollapsed());
        });
    }

    @Test
    @Timeout(60)
    void returnFromGraphsDoesNotBlankTheCachedTable() throws Exception {
        SignalsScreenModel model = new SignalsScreenModel();
        AtomicReference<ActiveSignalWorkspacePane> first = new AtomicReference<>();
        try {
            onFx(() -> {
                InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 20);
                List<CanonicalSignal> samples = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    samples.add(new CanonicalSignal(
                            "VehicleSpeed", "Speed", (i + 1) * 1_000_000_000L, 0x100, 1,
                            10.0 + i, 10L + i, "km/h", null, SourceReference.ofLine(1)));
                }
                store.accept(samples);
                model.replaceCatalog(store);
                model.addToWorkspace(List.of(model.catalog().entries().getFirst().key()));
                ActiveSignalWorkspacePane pane = new ActiveSignalWorkspacePane();
                first.set(pane);
                pane.setModel(model);
            });
            awaitRows(first.get(), 1);
            int rows = fxInt(() -> first.get().table().getItems().size());
            assertTrue(rows > 0);
            assertTrue(fxBool(() -> model.reusableWorkspaceSnapshot().isPresent()));

            int queries = fxInt(() -> model.workspaceAlignedQueryCountForTest());
            AtomicInteger changes = new AtomicInteger();
            onFx(() -> first.get().table().getItems().addListener((InvalidationListener) observable -> changes.incrementAndGet()));
            onFx(() -> first.get().reload(false));
            assertEquals(0, changes.get(), "refresh rebuilt the visible page");
            assertEquals(queries, fxInt(() -> model.workspaceAlignedQueryCountForTest()));
            assertEquals(rows, fxInt(() -> first.get().table().getItems().size()));

            AtomicInteger rebuiltRows = new AtomicInteger();
            onFx(() -> {
                ActiveSignalWorkspacePane rebuilt = new ActiveSignalWorkspacePane();
                rebuilt.setModel(model);
                rebuiltRows.set(rebuilt.table().getItems().size());
            });
            assertEquals(rows, rebuiltRows.get(), "rebuilt Signals page opened empty");
        } finally {
            model.shutdown();
        }
    }

    private static SignalBrowserPane styledBrowser(double width, double height) {
        SignalBrowserPane pane = new SignalBrowserPane();
        Scene scene = new Scene(pane, width, height);
        scene.getStylesheets().add(stylesheet());
        pane.applyCss();
        pane.resize(width, height);
        pane.layout();
        return pane;
    }

    private static String stylesheet() {
        var css = SignalsWorkstationRedesignTest.class.getResource("/css/stellar-desktop.css");
        assertNotNull(css);
        return css.toExternalForm();
    }

    private static int countText(Parent root, String text) {
        List<Node> nodes = new ArrayList<>();
        collect(root, nodes);
        int count = 0;
        for (Node node : nodes) {
            if (node instanceof Labeled labeled && text.equals(labeled.getText()) && labeled.isVisible()) {
                count++;
            }
        }
        return count;
    }

    private static boolean textOf(Parent root, String text) {
        List<Node> nodes = new ArrayList<>();
        collect(root, nodes);
        return nodes.stream()
                .filter(Labeled.class::isInstance)
                .map(Labeled.class::cast)
                .anyMatch(label -> text.equals(label.getText()));
    }

    private static void assertNoOverlap(Node top, Node bottom, String where) {
        Bounds a = top.localToScene(top.getBoundsInLocal());
        Bounds b = bottom.localToScene(bottom.getBoundsInLocal());
        assertFalse(a.intersects(b), where + " a=" + a + " b=" + b);
        assertTrue(a.getMaxY() <= b.getMinY() + 1, where + " stacked " + a + " / " + b);
    }

    private static List<Node> visibleArrows(Node root) {
        List<Node> arrows = new ArrayList<>();
        collect(root, arrows);
        return arrows.stream()
                .filter(node -> node.getStyleClass().contains("arrow-button"))
                .filter(Node::isVisible)
                .filter(Node::isManaged)
                .filter(node -> node.getBoundsInLocal().getWidth() > 1)
                .toList();
    }

    private static void collect(Node node, List<Node> into) {
        into.add(node);
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, into);
            }
        }
    }

    private static boolean arrowsOverlap(Parent pane, Node combo) {
        Bounds comboBounds = combo.localToScene(combo.getBoundsInLocal());
        List<Node> foreign = new ArrayList<>();
        collect(pane, foreign);
        for (Node node : foreign) {
            if (node == combo || isDescendant(combo, node)) {
                continue;
            }
            if (!node.getStyleClass().contains("arrow-button") || !node.isVisible() || !node.isManaged()) {
                continue;
            }
            if (node.getBoundsInLocal().getWidth() <= 1) {
                continue;
            }
            if (comboBounds.intersects(node.localToScene(node.getBoundsInLocal()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDescendant(Node root, Node candidate) {
        Node cursor = candidate;
        while (cursor != null) {
            if (cursor == root) {
                return true;
            }
            cursor = cursor.getParent();
        }
        return false;
    }

    private static Labeled buttonCell(ComboBox<?> combo) {
        List<Node> nodes = new ArrayList<>();
        collect(combo, nodes);
        return nodes.stream()
                .filter(Labeled.class::isInstance)
                .map(Labeled.class::cast)
                .filter(Node::isVisible)
                .filter(Node::isManaged)
                .filter(cell -> cell.getWidth() > 1)
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) {
        try {
            Field found = target.getClass().getDeclaredField(name);
            found.setAccessible(true);
            return (T) found.get(target);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void onSignals(double width, double height, boolean show, SignalsCheck check) throws Exception {
        onFx(() -> {
            FXMLLoader loader = new FXMLLoader(SignalsController.class.getResource("/fxml/signals-view.fxml"));
            VBox root = loader.load();
            SignalsController controller = loader.getController();
            Scene scene = new Scene(root, width, height);
            scene.getStylesheets().add(stylesheet());
            Stage stage = show ? new Stage() : null;
            if (stage != null) {
                stage.setScene(scene);
                stage.show();
            }
            root.applyCss();
            root.resize(width, height);
            root.layout();
            HBox workbench = find(root, HBox.class, "signals-workbench");
            controller.applyWorkspaceWidthForTest(workbench.getWidth() > 1 ? workbench.getWidth() : width - 24);
            root.applyCss();
            root.layout();
            try {
                check.check(root, controller, workbench);
            } finally {
                if (stage != null) {
                    stage.hide();
                }
            }
        });
    }

    private static void awaitRows(ActiveSignalWorkspacePane pane, int minimum) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (fxInt(() -> pane.table().getItems().size()) >= minimum
                    && fxBool(() -> pane.table().getItems().getFirst() != null)) {
                return;
            }
            Thread.sleep(40);
        }
        throw new AssertionError("workspace rows did not arrive");
    }

    private static int fxInt(IntSupplier supplier) throws Exception {
        AtomicInteger value = new AtomicInteger();
        onFx(() -> value.set(supplier.getAsInt()));
        return value.get();
    }

    private static boolean fxBool(BooleanSupplier supplier) throws Exception {
        AtomicBoolean value = new AtomicBoolean();
        onFx(() -> value.set(supplier.getAsBoolean()));
        return value.get();
    }

    private static void onFx(ThrowingRunnable action) throws Exception {
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

    private static double columnWidth(TableColumn<?, ?> column) {
        return column.getWidth() > 0 ? column.getWidth() : column.getPrefWidth();
    }

    private static Labeled findLabel(Parent root, String text) {
        List<Node> nodes = new ArrayList<>();
        collect(root, nodes);
        return nodes.stream()
                .filter(Labeled.class::isInstance)
                .map(Labeled.class::cast)
                .filter(label -> text.equals(label.getText()) && !(label instanceof ButtonBase))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing label " + text));
    }

    private static ButtonBase findButton(Parent root, String text) {
        List<Node> nodes = new ArrayList<>();
        collect(root, nodes);
        return nodes.stream()
                .filter(ButtonBase.class::isInstance)
                .map(ButtonBase.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + text));
    }

    private static <T> T find(Parent root, Class<T> type) {
        return find(root, type, null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T find(Parent root, Class<T> type, String styleClass) {
        List<Node> nodes = new ArrayList<>();
        collect(root, nodes);
        for (Node node : nodes) {
            if (type.isInstance(node) && (styleClass == null || node.getStyleClass().contains(styleClass))) {
                return (T) node;
            }
        }
        throw new AssertionError("missing " + type.getSimpleName() + " " + styleClass);
    }

    @FunctionalInterface
    private interface SignalsCheck {
        void check(VBox root, SignalsController controller, HBox workbench) throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface IntSupplier {
        int getAsInt();
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
