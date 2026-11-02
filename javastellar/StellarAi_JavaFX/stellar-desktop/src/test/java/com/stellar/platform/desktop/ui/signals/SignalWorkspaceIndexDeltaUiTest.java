package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.desktop.presentation.AlignedSampleRow;
import com.stellar.platform.desktop.presentation.AlignedWorkspaceSnapshot;
import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalWorkspaceIndexDeltaUiTest {

    private SignalsScreenModel model;

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

    @AfterEach
    void tearDown() {
        if (model != null) {
            model.shutdown();
            model = null;
        }
    }

    @Test
    @Timeout(30)
    void indexIsFirstTimestampSecondAndDeltaHiddenByDefault() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                ActiveSignalWorkspacePane pane = new ActiveSignalWorkspacePane();
                new Scene(pane, 1100, 720);
                Method rebuild = ActiveSignalWorkspacePane.class.getDeclaredMethod(
                        "rebuildColumns", List.class);
                rebuild.setAccessible(true);
                rebuild.invoke(pane, List.of(
                        entry("SignalA"),
                        entry("SignalB")
                ));
                TableView<AlignedSampleRow> table = pane.table();
                assertTrue(table.getColumns().size() >= 3);
                assertEquals("Aligned Index", table.getColumns().get(0).getText());
                assertTrue(table.getColumns().get(0).isVisible());
                assertTrue(header(table.getColumns().get(1)).contains("Timestamp"));
                assertTrue(table.getColumns().get(1).isVisible());
                assertFalse(pane.isDeltaColumnVisibleForTest());
                assertTrue(header(table.getColumns().get(2)).contains("t (ms)"));

                pane.setDeltaColumnVisibleForTest(true);
                assertTrue(pane.isDeltaColumnVisibleForTest());
                assertEquals("Aligned Index", table.getColumns().get(0).getText());
                assertTrue(header(table.getColumns().get(1)).contains("Timestamp"));
                assertTrue(header(table.getColumns().get(2)).contains("t (ms)"));
                assertTrue(table.getColumns().get(2).isVisible());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @Test
    @Timeout(45)
    void displayedIndexIsZeroBasedAndGoToIndexUsesIt() throws Exception {
        model = new SignalsScreenModel();
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("meas.mf4", "def.dbc", 1_000);
        store.accept(List.of(
                can("VehicleSpeed", 1_000_000_000L, 10.0),
                can("VehicleSpeed", 1_002_190_000L, 11.0),
                can("VehicleSpeed", 1_004_190_000L, 12.0)
        ));
        model.replaceCatalog(store);
        SignalCatalogKey key = model.catalog().entries().getFirst().key();
        model.addToWorkspace(List.of(key));
        AlignedWorkspaceSnapshot snap = awaitAligned(model);
        assertEquals(3, snap.rows().size());
        assertEquals(0L, snap.rows().get(0).index());
        assertEquals(1L, snap.rows().get(1).index());
        assertEquals(2L, snap.rows().get(2).index());
        assertEquals("2.19", ActiveSignalWorkspacePane.formatDeltaMillis(
                snap.rows().get(1).timestampNanos() - snap.rows().get(0).timestampNanos()));

        CountDownLatch shown = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<ActiveSignalWorkspacePane> paneRef = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                ActiveSignalWorkspacePane pane = new ActiveSignalWorkspacePane();
                Stage stage = new Stage();
                stage.setScene(new Scene(pane, 1100, 720));
                stage.show();
                pane.setModel(model);
                paneRef.set(pane);
            } catch (Throwable t) {
                error.set(t);
            } finally {
                shown.countDown();
            }
        });
        assertTrue(shown.await(10, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        waitForRows(paneRef.get(), 3);

        CountDownLatch checked = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ActiveSignalWorkspacePane pane = paneRef.get();
                TableView<AlignedSampleRow> table = pane.table();
                assertEquals("0", table.getColumns().get(0).getCellData(0));
                assertEquals("1", table.getColumns().get(0).getCellData(1));
                assertEquals("2", table.getColumns().get(0).getCellData(2));
                pane.setDeltaColumnVisibleForTest(true);
                table.refresh();
                table.applyCss();
                table.layout();
                String firstDelta = deltaCellText(table, 0);
                String secondDelta = deltaCellText(table, 1);
                assertEquals(AlignedSampleRow.MISSING, firstDelta);
                assertEquals("2.19", secondDelta);

                pane.indexFieldForTest().setText("0");
                pane.indexGoButtonForTest().fire();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                checked.countDown();
            }
        });
        assertTrue(checked.await(10, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }

        CountDownLatch jumped = new CountDownLatch(1);
        AtomicInteger selected = new AtomicInteger(-1);
        AtomicReference<Long> selectedIndex = new AtomicReference<>();
        model.jumpToIndexAsync(0, s -> Platform.runLater(() -> {
            ActiveSignalWorkspacePane pane = paneRef.get();
            selected.set(pane.table().getSelectionModel().getSelectedIndex());
            if (!pane.table().getItems().isEmpty()) {
                selectedIndex.set(pane.table().getItems().get(
                        Math.max(0, pane.table().getSelectionModel().getSelectedIndex())).index());
            }
            jumped.countDown();
        }));
        assertTrue(jumped.await(10, TimeUnit.SECONDS));
        assertEquals(0, selected.get());
        assertEquals(0L, selectedIndex.get());
    }

    @Test
    @Timeout(30)
    void alignedTimelineIsTheOnlySignalTable() throws Exception {
        model = new SignalsScreenModel();
        model.setSamplePageSize(2);
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("sierra.mf4", "temp.dbc", 1_000);
        store.accept(List.of(
                can("CH01", 84_940_000L, 25),
                can("CH01", 184_950_000L, 26),
                can("CH05", 85_130_000L, 79)
        ));
        model.replaceCatalog(store);
        SignalCatalogKey ch01 = model.catalog().entries().stream()
                .filter(entry -> "CH01".equals(entry.signalName())).findFirst().orElseThrow().key();
        SignalCatalogKey ch05 = model.catalog().entries().stream()
                .filter(entry -> "CH05".equals(entry.signalName())).findFirst().orElseThrow().key();
        model.addToWorkspace(List.of(ch01, ch05));
        model.setFocusedSignalKey(ch01);

        AtomicReference<ActiveSignalWorkspacePane> paneRef = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch built = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ActiveSignalWorkspacePane pane = new ActiveSignalWorkspacePane();
                new Scene(pane, 1200, 760);
                pane.setModel(model);
                paneRef.set(pane);
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                built.countDown();
            }
        });
        assertTrue(built.await(10, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        ActiveSignalWorkspacePane pane = paneRef.get();
        try {
            waitForRows(pane, 2);
            assertEquals("Aligned Index", columnText(pane, 0));
            assertEquals(2, signalColumnCount(pane));
            assertTrue(csvTooltip(pane).contains("full aligned dataset"));
            AlignedSampleRow first = rowAt(pane, 0);
            AlignedSampleRow second = rowAt(pane, 1);
            assertEquals(0L, first.index());
            assertEquals(84_940_000L, first.timestampNanos());
            assertEquals(25.0, first.physicalAt(0));
            assertEquals(null, first.physicalAt(1));
            assertEquals(85_130_000L, second.timestampNanos());
            assertEquals(79.0, second.physicalAt(1));
            CountDownLatch laidOut = new CountDownLatch(1);
            AtomicReference<Throwable> layoutError = new AtomicReference<>();
            Platform.runLater(() -> {
                try {
                    pane.applyCss();
                    pane.resize(1200, 760);
                    pane.layout();
                    assertFalse(containsControl(pane, "Aligned Timeline"));
                    assertFalse(containsControl(pane, "This Signal"));
                    assertTrue(pane.toolbarHeightForTest() <= SignalsLayoutMetrics.TOOLBAR_HEIGHT + 4,
                            "toolbar " + pane.toolbarHeightForTest());
                } catch (Throwable thrown) {
                    layoutError.set(thrown);
                } finally {
                    laidOut.countDown();
                }
            });
            assertTrue(laidOut.await(5, TimeUnit.SECONDS));
            if (layoutError.get() != null) {
                throw new AssertionError(layoutError.get());
            }
        } finally {
            CountDownLatch disposed = new CountDownLatch(1);
            Platform.runLater(() -> {
                pane.dispose();
                disposed.countDown();
            });
            assertTrue(disposed.await(5, TimeUnit.SECONDS));
        }
    }

    private static String columnText(ActiveSignalWorkspacePane pane, int index) throws Exception {
        AtomicReference<String> text = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            text.set(pane.table().getColumns().get(index).getText());
            latch.countDown();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        return text.get();
    }

    private static int signalColumnCount(ActiveSignalWorkspacePane pane) throws Exception {
        AtomicInteger count = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            int n = 0;
            for (TableColumn<AlignedSampleRow, ?> column : pane.table().getColumns()) {
                String text = header(column);
                if (column.isVisible() && (text.contains("CH01") || text.contains("CH05"))) {
                    n++;
                }
            }
            count.set(n);
            latch.countDown();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        return count.get();
    }

    private static AlignedSampleRow rowAt(ActiveSignalWorkspacePane pane, int index) throws Exception {
        AtomicReference<AlignedSampleRow> row = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            row.set(pane.table().getItems().get(index));
            latch.countDown();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        return row.get();
    }

    private static String csvTooltip(ActiveSignalWorkspacePane pane) throws Exception {
        AtomicReference<String> tip = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            var tooltip = pane.exportCsvButtonForTest().getTooltip();
            tip.set(tooltip == null ? "" : tooltip.getText());
            latch.countDown();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        return tip.get() == null ? "" : tip.get();
    }

    private static boolean containsControl(javafx.scene.Parent root, String text) {
        java.util.ArrayDeque<Node> pending = new java.util.ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Node node = pending.removeFirst();
            if (node instanceof Button button && text.equals(button.getText())) {
                return true;
            }
            if (node instanceof Label label && text.equals(label.getText())) {
                return true;
            }
            if (node instanceof javafx.scene.Parent parent) {
                pending.addAll(parent.getChildrenUnmodifiable());
            }
        }
        return false;
    }

    private static String deltaCellText(TableView<AlignedSampleRow> table, int row) {
        for (Node n : table.lookupAll(".workspace-delta-cell")) {
            if (n instanceof TableCell<?, ?> cell && cell.getIndex() == row && cell.getText() != null) {
                return cell.getText();
            }
        }
        return null;
    }

    private static void waitForRows(ActiveSignalWorkspacePane pane, int min) throws Exception {
        for (int i = 0; i < 40; i++) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger size = new AtomicInteger();
            Platform.runLater(() -> {
                size.set(pane.table().getItems().size());
                latch.countDown();
            });
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            if (size.get() >= min) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("workspace table did not load rows");
    }

    private static String header(TableColumn<?, ?> col) {
        StringBuilder sb = new StringBuilder();
        if (col.getText() != null) {
            sb.append(col.getText());
        }
        collect(col.getGraphic(), sb);
        return sb.toString();
    }

    private static void collect(Node node, StringBuilder sb) {
        if (node == null) {
            return;
        }
        if (node instanceof javafx.scene.control.Label label && label.getText() != null) {
            sb.append(' ').append(label.getText());
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, sb);
            }
        }
    }

    private static SignalCatalogEntry entry(String name) {
        return new SignalCatalogEntry(
                new SignalCatalogKey(name, SignalSourceType.DBC_CAN, "Msg", 0x100, 1, "run.mf4", "veh.dbc"),
                "km/h", 3L, 1_000_000_000L, 2_000_000_000L, 1.0, 2.0, "run.mf4", "veh.dbc");
    }

    private static CanonicalSignal can(String name, long ts, double physical) {
        return new CanonicalSignal(
                name, "Chassis", ts, 0x200, 1, physical, (long) physical, "km/h", null, SourceReference.ofLine(1));
    }

    private static AlignedWorkspaceSnapshot awaitAligned(SignalsScreenModel model) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AlignedWorkspaceSnapshot> snap = new AtomicReference<>();
        model.loadWorkspaceAlignedAsync(true, s -> {
            snap.set(s);
            latch.countDown();
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        return snap.get();
    }
}
