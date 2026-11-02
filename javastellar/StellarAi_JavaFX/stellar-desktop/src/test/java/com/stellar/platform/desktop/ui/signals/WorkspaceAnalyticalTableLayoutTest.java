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
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Presentation-only guards for the Signals analytical table structure. */
class WorkspaceAnalyticalTableLayoutTest {

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
    void formatDeltaMillisIsDisplayOnlyArithmetic() {
        assertEquals("10", ActiveSignalWorkspacePane.formatDeltaMillis(10_000_000L));
        assertEquals("23.29", ActiveSignalWorkspacePane.formatDeltaMillis(23_290_000L));
        assertEquals("-1.5", ActiveSignalWorkspacePane.formatDeltaMillis(-1_500_000L));
    }

    @Test
    @Timeout(30)
    void analyticalTableHasIndexTimestampThenSignalsWithDeltaHidden() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                ActiveSignalWorkspacePane pane = new ActiveSignalWorkspacePane();
                Scene scene = new Scene(pane, 1100, 720);
                assertNotEquals(null, scene);

                SignalCatalogEntry speed = entry(
                        "VehicleSpeed", SignalSourceType.DBC_CAN, "Chassis", 0x200, 1, "km/h");
                SignalCatalogEntry rpm = entry(
                        "EngineSpeed", SignalSourceType.DBC_CAN, "Powertrain", 0x100, 1, "rpm");

                Method rebuild = ActiveSignalWorkspacePane.class.getDeclaredMethod(
                        "rebuildColumns", List.class);
                rebuild.setAccessible(true);
                rebuild.invoke(pane, List.of(speed, rpm));

                TableView<AlignedSampleRow> table = pane.table();
                assertEquals(5, table.getColumns().size(), "Index + Timestamp + delta + 2 signals");

                TableColumn<AlignedSampleRow, ?> c0 = table.getColumns().get(0);
                TableColumn<AlignedSampleRow, ?> c1 = table.getColumns().get(1);
                TableColumn<AlignedSampleRow, ?> c2 = table.getColumns().get(2);
                TableColumn<AlignedSampleRow, ?> c3 = table.getColumns().get(3);
                TableColumn<AlignedSampleRow, ?> c4 = table.getColumns().get(4);

                assertEquals("Aligned Index", c0.getText());
                assertTrue(c0.isVisible());
                assertTrue(headerText(c1).contains("Timestamp"), "second column Timestamp");
                assertTrue(c1.isVisible());
                assertTrue(headerText(c2).contains("t (ms)"), "delta remains available: " + headerText(c2));
                assertFalse(c2.isVisible(), "Δt hidden by default");

                assertTrue(table.getColumnResizePolicy() == TableView.UNCONSTRAINED_RESIZE_POLICY);
                assertEquals(SignalsLayoutMetrics.INDEX_COL_MAX, c0.getMaxWidth(), 0.1);
                assertEquals(SignalsLayoutMetrics.TIMESTAMP_COL_MAX, c1.getMaxWidth(), 0.1);
                assertEquals(SignalsLayoutMetrics.DELTA_COL_MAX, c2.getMaxWidth(), 0.1);
                assertEquals(SignalsLayoutMetrics.SIGNAL_COL_MAX, c3.getMaxWidth(), 0.1);
                assertTrue(c3.getMaxWidth() < 400, "signal must not flex full width");

                assertTrue(headerText(c3).contains("VehicleSpeed"));
                assertTrue(headerText(c4).contains("EngineSpeed"));
                assertFalse(headerContainsBadge(c3), "no DBC/MF4 badge in signal header");
                assertFalse(headerContainsBadge(c4), "no DBC/MF4 badge in signal header");
                assertInstanceOf(VBox.class, c3.getGraphic());
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
    @Timeout(30)
    void singleSignalDoesNotConsumeEntireTableWidth() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                ActiveSignalWorkspacePane pane = new ActiveSignalWorkspacePane();
                new Scene(pane, 1000, 700);
                SignalCatalogEntry only = entry(
                        "ThrottlePosition", SignalSourceType.DBC_CAN, "Pedal", 0x300, 2, "%");
                Method rebuild = ActiveSignalWorkspacePane.class.getDeclaredMethod(
                        "rebuildColumns", List.class);
                rebuild.setAccessible(true);
                rebuild.invoke(pane, List.of(only));
                pane.resize(1000, 700);
                pane.layout();

                TableView<AlignedSampleRow> table = pane.table();
                assertEquals(4, table.getColumns().size());
                TableColumn<AlignedSampleRow, ?> signal = table.getColumns().get(3);
                assertEquals(SignalsLayoutMetrics.SIGNAL_COL_MAX, signal.getMaxWidth(), 0.1);
                assertTrue(signal.getMaxWidth() <= 220.1);
                assertTrue(table.getColumnResizePolicy() == TableView.UNCONSTRAINED_RESIZE_POLICY);
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
    @Timeout(30)
    void deltaUsesPagePredecessorAndDoesNotMutateTimestamps() throws Exception {
        model = new SignalsScreenModel();
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("meas.mf4", "def.dbc", 1_000);
        store.accept(List.of(
                can("VehicleSpeed", "Chassis", 0x200, 1, 1_000_000_000L, 10.0),
                can("VehicleSpeed", "Chassis", 0x200, 1, 1_023_290_000L, 11.0),
                can("VehicleSpeed", "Chassis", 0x200, 1, 1_033_290_000L, 12.0)
        ));
        model.replaceCatalog(store);
        SignalCatalogKey key = model.catalog().entries().getFirst().key();
        model.addToWorkspace(List.of(key));
        AlignedWorkspaceSnapshot snap = awaitAligned(model);
        assertEquals(3, snap.rows().size());

        long[] original = snap.rows().stream().mapToLong(AlignedSampleRow::timestampNanos).toArray();
        assertEquals(1_000_000_000L, original[0]);
        assertEquals(1_023_290_000L, original[1]);
        assertEquals(1_033_290_000L, original[2]);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                ActiveSignalWorkspacePane pane = new ActiveSignalWorkspacePane();
                pane.setModel(model);
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }

        AlignedWorkspaceSnapshot after = awaitAligned(model);
        long[] afterTs = after.rows().stream().mapToLong(AlignedSampleRow::timestampNanos).toArray();
        assertEquals(original[0], afterTs[0]);
        assertEquals(original[1], afterTs[1]);
        assertEquals(original[2], afterTs[2]);
        assertEquals("23.29", ActiveSignalWorkspacePane.formatDeltaMillis(afterTs[1] - afterTs[0]));
        assertEquals(10.0, after.rows().get(0).physicalAt(0));
        assertEquals(11.0, after.rows().get(1).physicalAt(0));
    }

    private static String headerText(TableColumn<?, ?> col) {
        StringBuilder sb = new StringBuilder();
        if (col.getText() != null) {
            sb.append(col.getText());
        }
        collectLabels(col.getGraphic(), sb);
        return sb.toString();
    }

    private static void collectLabels(Node node, StringBuilder sb) {
        if (node == null) {
            return;
        }
        if (node instanceof Label label && label.getText() != null) {
            sb.append(' ').append(label.getText());
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectLabels(child, sb);
            }
        }
    }

    private static boolean headerContainsBadge(TableColumn<?, ?> col) {
        Deque<Node> q = new ArrayDeque<>();
        if (col.getGraphic() != null) {
            q.add(col.getGraphic());
        }
        while (!q.isEmpty()) {
            Node n = q.removeFirst();
            if (n.getStyleClass().stream().anyMatch(s ->
                    s.contains("badge-dbc") || s.contains("badge-native")
                            || s.contains("source-badge") || s.equals("dbc-badge")
                            || s.equals("native-badge"))) {
                return true;
            }
            if (n instanceof Label label) {
                String t = label.getText();
                if ("DBC".equals(t) || "MF4".equals(t)) {
                    return true;
                }
            }
            if (n instanceof javafx.scene.Parent parent) {
                q.addAll(parent.getChildrenUnmodifiable());
            }
        }
        return false;
    }

    private static SignalCatalogEntry entry(
            String name,
            SignalSourceType type,
            String message,
            long canId,
            int channel,
            String unit
    ) {
        return new SignalCatalogEntry(
                new SignalCatalogKey(name, type, message, canId, channel, "run.mf4", "veh.dbc"),
                unit,
                3L,
                1_000_000_000L,
                2_000_000_000L,
                1.0,
                2.0,
                "run.mf4",
                "veh.dbc"
        );
    }

    private static CanonicalSignal can(
            String name, String message, long canId, int channel, long ts, double physical) {
        return new CanonicalSignal(
                name,
                message,
                ts,
                canId,
                channel,
                physical,
                (long) physical,
                "km/h",
                null,
                SourceReference.ofLine(1)
        );
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