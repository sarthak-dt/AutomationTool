package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionCandidateKind;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickDecodeSelectedDbcLayoutTest {

    private static final int[][] SIZES = {{1706, 900}, {1440, 900}, {1280, 800}, {1100, 720}};
    private static String previousHome;

    @BeforeAll
    static void initJavaFx() throws Exception {
        previousHome = System.getProperty("stellarai.home");
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        CountDownLatch configured = new CountDownLatch(1);
        Platform.runLater(() -> {
            Platform.setImplicitExit(false);
            configured.countDown();
        });
        assertTrue(configured.await(5, TimeUnit.SECONDS));
    }

    @AfterAll
    static void restoreHome() {
        if (previousHome == null) {
            System.clearProperty("stellarai.home");
        } else {
            System.setProperty("stellarai.home", previousHome);
        }
    }

    @Test
    @Timeout(90)
    void overrideAllComboFitsInSelectedDbcRows(@TempDir Path temp) throws Exception {
        runOnFx(() -> {
            System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
            Fixture fixture = Fixture.create();
            QuickDecodePane pane = new QuickDecodePane();
            pane.bind(fixture.service, key -> { }, () -> { }, () -> { });
            pane.setModeActive(true);
            pane.refresh();

            Stage stage = new Stage();
            Scene scene = new Scene(pane, 1440, 900);
            scene.getStylesheets().add(QuickDecodePane.class.getResource("/css/stellar-desktop.css").toExternalForm());
            stage.setScene(scene);
            stage.show();
            try {
                for (ProjectSourceId sourceId : fixture.sources) {
                    assertEquals("Best per Channel", pane.sourceStrategyTextForTest(sourceId));
                    assertFalse(pane.sourceExpandedForTest(sourceId));
                }
                for (int[] size : SIZES) {
                    stage.setWidth(size[0]);
                    stage.setHeight(size[1]);
                    pane.resize(size[0], size[1]);
                    settle(pane);
                    assertRows(pane, size[0], false);
                    expandAll(pane, true);
                    settle(pane);
                    assertRows(pane, size[0], true);
                    expandAll(pane, false);
                    settle(pane);
                    assertRows(pane, size[0], false);
                }
                expandAll(pane, true);
                settle(pane);
                selectOverride(pane, fixture);
                pane.refresh();
                settle(pane);
                assertEquals("Override All · body.dbc", pane.sourceStrategyTextForTest(fixture.sources.getFirst()));
                TreeTableView<?> table = pane.tableForTest();
                table.getSelectionModel().select(1);
                settle(pane);
                assertEquals(1, table.getSelectionModel().getSelectedIndex());
                assertTrue(table.getSelectionModel().getSelectedItem() != null);
            } finally {
                stage.close();
            }
        });
    }

    private static void selectOverride(QuickDecodePane pane, Fixture fixture) {
        ComboBox<?> combo = (ComboBox<?>) pane.tableForTest().lookup(".quick-source-override-combo");
        assertTrue(combo != null && !combo.getItems().isEmpty(), "Override All combo has no choices");
        combo.show();
        assertTrue(combo.isShowing(), "Override All dropdown did not open");
        combo.getSelectionModel().select(0);
        combo.hide();
        assertFalse(combo.isShowing(), "Override All dropdown stayed open");
        assertTrue(fixture.service.quickDecodeSelectionModel().overrideFor(
                SourceChannelKey.of(fixture.sources.getFirst(), 1)).isPresent());
    }

    private static void assertRows(QuickDecodePane pane, int width, boolean expanded) {
        TreeTableView<?> table = pane.tableForTest();
        assertTrue(table.getHeight() > 120, width + " table height " + table.getHeight());
        List<ComboBox<?>> overrides = combos(table, ".quick-source-override-combo");
        assertFalse(overrides.isEmpty(), width + " missing Override All combo");
        for (ComboBox<?> combo : overrides) {
            assertInsideCell(combo, width + " Override All");
            assertPromptReadable(combo, width);
            Label strategy = strategyLabel(combo);
            assertTrue(strategy != null, width + " missing Best per Channel label");
            assertFalse(strategy.getText() == null || strategy.getText().isBlank(), width + " blank strategy");
            Bounds labelBox = strategy.localToScene(strategy.getLayoutBounds());
            Bounds comboBox = combo.localToScene(combo.getLayoutBounds());
            assertTrue(labelBox.getHeight() >= 10, width + " strategy height " + labelBox.getHeight());
            assertTrue(labelBox.getMaxY() <= comboBox.getMinY() + 0.5,
                    width + " strategy overlaps the dropdown " + labelBox + " " + comboBox);
        }
        if (expanded) {
            List<ComboBox<?>> channels = combos(table, ".quick-dbc-combo").stream()
                    .filter(combo -> !combo.getStyleClass().contains("quick-source-override-combo"))
                    .toList();
            assertFalse(channels.isEmpty(), width + " expanded table has no channel dropdown");
            channels.forEach(combo -> assertInsideCell(combo, width + " channel DBC"));
        }
        assertNoRowOverlap(table, width);
        assertSourceTooltips(table, width);
        assertHorizontalScrollUsable(table, width);
    }

    private static void assertPromptReadable(ComboBox<?> combo, int width) {
        Node text = combo.lookup(".list-cell");
        assertTrue(text != null, width + " Override All has no button cell");
        Bounds textBox = text.localToScene(text.getLayoutBounds());
        Bounds comboBox = combo.localToScene(combo.getLayoutBounds());
        assertTrue(textBox.getHeight() >= 12, width + " Override All text height " + textBox.getHeight());
        assertTrue(textBox.getMinY() >= comboBox.getMinY() - 0.5, width + " text above the combo border");
        assertTrue(textBox.getMaxY() <= comboBox.getMaxY() + 0.5, width + " text below the combo border");
        assertEquals("Override All…", combo.getPromptText());
    }

    private static void assertInsideCell(ComboBox<?> combo, String where) {
        TreeTableCell<?, ?> cell = ancestor(combo, TreeTableCell.class);
        assertTrue(cell != null, where + " is not in a cell");
        Bounds control = combo.localToScene(combo.getLayoutBounds());
        Bounds cellBox = cell.localToScene(cell.getLayoutBounds());
        assertTrue(control.getHeight() >= 26, where + " height " + control.getHeight());
        assertTrue(control.getMinY() >= cellBox.getMinY() - 0.5, where + " top " + control + " cell " + cellBox);
        assertTrue(control.getMaxY() <= cellBox.getMaxY() + 0.5, where + " bottom " + control + " cell " + cellBox);
        assertTrue(control.getMinX() >= cellBox.getMinX() - 0.5, where + " left");
        assertTrue(control.getMaxX() <= cellBox.getMaxX() + 1, where + " right");
        assertFalse(combo.isDisabled());
    }

    private static void assertNoRowOverlap(TreeTableView<?> table, int width) {
        List<Bounds> rows = new ArrayList<>();
        for (Node node : table.lookupAll(".tree-table-row-cell")) {
            if (node instanceof TreeTableRow<?> row && !row.isEmpty() && row.getHeight() > 1) {
                rows.add(row.localToScene(row.getLayoutBounds()));
            }
        }
        rows.sort(Comparator.comparingDouble(Bounds::getMinY));
        assertTrue(rows.size() >= 4, width + " visible rows " + rows.size());
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(rows.get(i).getMinY() >= rows.get(i - 1).getMaxY() - 0.5,
                    width + " rows overlap " + rows.get(i - 1) + " " + rows.get(i));
        }
    }

    private static void assertSourceTooltips(TreeTableView<?> table, int width) {
        boolean found = false;
        for (Node node : table.lookupAll(".quick-source-name")) {
            if (!(node instanceof Label label) || label.getTooltip() == null) {
                continue;
            }
            found = true;
            String tip = label.getTooltip().getText();
            assertTrue(tip.contains("VeryLongEngineeringMeasurement"), width + " tooltip " + tip);
            assertTrue(tip.endsWith(".mf4"), width + " tooltip " + tip);
            assertTrue(tip.length() >= label.getText().replace("…", "").replace("...", "").length());
        }
        assertTrue(found, width + " source filename tooltip missing");
    }

    private static void assertHorizontalScrollUsable(TreeTableView<?> table, int width) {
        double columns = table.getColumns().stream().mapToDouble(column -> column.getWidth()).sum();
        ScrollBar bar = null;
        for (Node node : table.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar scrollBar && scrollBar.getOrientation() == Orientation.HORIZONTAL) {
                bar = scrollBar;
            }
        }
        if (columns > table.getWidth() + 2) {
            assertTrue(bar != null && bar.isVisible(), width + " horizontal scrollbar missing");
            assertTrue(bar.getHeight() >= 8, width + " horizontal scrollbar height " + bar.getHeight());
        }
    }

    private static Label strategyLabel(ComboBox<?> combo) {
        Parent parent = combo.getParent();
        if (parent == null) {
            return null;
        }
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Label label && label.getStyleClass().contains("quick-source-strategy")) {
                return label;
            }
        }
        return null;
    }

    private static List<ComboBox<?>> combos(TreeTableView<?> table, String selector) {
        List<ComboBox<?>> found = new ArrayList<>();
        for (Node node : table.lookupAll(selector)) {
            if (node instanceof ComboBox<?> combo && combo.isVisible() && combo.getHeight() > 1) {
                found.add(combo);
            }
        }
        return found;
    }

    private static <T> T ancestor(Node node, Class<T> type) {
        Node current = node;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getParent();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void expandAll(QuickDecodePane pane, boolean expanded) {
        TreeItem<?> root = pane.tableForTest().getRoot();
        for (TreeItem<?> source : root.getChildren()) {
            source.setExpanded(expanded);
        }
    }

    private static void settle(Parent root) {
        for (int i = 0; i < 4; i++) {
            root.applyCss();
            root.layout();
        }
    }

    private static void runOnFx(FxBody body) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(80, TimeUnit.SECONDS), "JavaFX task timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @FunctionalInterface
    private interface FxBody {
        void run() throws Exception;
    }

    private record Fixture(DesktopAppService service, List<ProjectSourceId> sources) {
        static Fixture create() {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile body = service.workspace().addFile(new WorkspaceFile(
                    Path.of("body.dbc").toAbsolutePath(), FileKind.DEFINITION_DBC, 100));
            WorkspaceFile powertrain = service.workspace().addFile(new WorkspaceFile(
                    Path.of("powertrain.dbc").toAbsolutePath(), FileKind.DEFINITION_DBC, 100));
            List<ProjectSourceId> sources = new ArrayList<>();
            for (int sourceIndex = 1; sourceIndex <= 4; sourceIndex++) {
                String name = "VeryLongEngineeringMeasurement_" + sourceIndex + "_VehicleDynamicsSession.mf4";
                WorkspaceFile source = service.workspace().addFile(new WorkspaceFile(
                        Path.of(name).toAbsolutePath(), FileKind.MEASUREMENT_MF4, 100));
                sources.add(source.projectSourceId());
                List<CanChannelInventory> channels = List.of(
                        channel(1, 67, 1000), channel(2, 55, 1000), channel(3, 31, 1000));
                service.sourceInventoryModel().put(source.id(), new CanSourceInventory(
                        source.path(), source.fileName(), MeasurementSourceFormat.MF4,
                        CanInventoryStatus.INVENTORY_READY, 3, 3000, 121, true, channels, List.of()));
                install(service, source, 1, body, 54, 67, 920, 1000, powertrain, 10, 150);
                install(service, source, 2, powertrain, 44, 55, 810, 1000, body, 5, 100);
                install(service, source, 3, body, 31, 31, 1000, 1000, powertrain, 3, 80);
            }
            return new Fixture(service, List.copyOf(sources));
        }

        private static void install(
                DesktopAppService service,
                WorkspaceFile source,
                int channelId,
                WorkspaceFile recommended,
                long matchedIds,
                long observedIds,
                long matchedFrames,
                long observedFrames,
                WorkspaceFile alternate,
                long alternateIds,
                long alternateFrames
        ) {
            SourceChannelDefinitionCandidate best = candidate(
                    source, channelId, recommended, matchedIds, observedIds, matchedFrames, observedFrames);
            SourceChannelDefinitionCandidate other = candidate(
                    source, channelId, alternate, alternateIds, observedIds, alternateFrames, observedFrames);
            List<SourceChannelDefinitionCandidate> all = new ArrayList<>(service.definitionMatchingModel().allCandidates());
            all.add(best);
            all.add(other);
            service.definitionMatchingModel().replaceCandidates(all);
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    best.sourceChannelKey(), ChannelMappingStatus.RECOMMENDED,
                    ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    best.definitionSourceId(), best.definitionDisplayName(), null, "",
                    best.observedUniqueMessageCount(), best.matchedUniqueMessageCount(), best.coverageRatio()));
        }

        private static SourceChannelDefinitionCandidate candidate(
                WorkspaceFile source,
                int channelId,
                WorkspaceFile dbc,
                long matchedIds,
                long observedIds,
                long matchedFrames,
                long observedFrames
        ) {
            return new SourceChannelDefinitionCandidate(
                    SourceChannelKey.of(source.projectSourceId(), channelId),
                    dbc.definitionSourceId(), dbc.fileName(), 100,
                    matchedIds > 0 ? DefinitionCandidateKind.HAS_OVERLAP : DefinitionCandidateKind.NO_OVERLAP,
                    observedIds, matchedIds, Math.max(0L, observedIds - matchedIds),
                    observedIds == 0 ? 0.0 : matchedIds / (double) observedIds,
                    observedFrames, matchedFrames,
                    observedFrames == 0 ? 0.0 : matchedFrames / (double) observedFrames);
        }

        private static CanChannelInventory channel(int id, long uniqueIds, long frames) {
            return new CanChannelInventory(
                    id, "Channel " + id, frames, uniqueIds, uniqueIds, 0, 0,
                    List.of(new CanMessageInventory(new CanMessageKey(id, 0x100L + id, false),
                            frames, 8, 8, false)));
        }
    }
}
