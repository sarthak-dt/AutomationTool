package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One checkbox click must issue exactly one membership command and survive explorer refresh.
 */
class GraphExplorerCheckboxStabilityTest {

    @TempDir Path temp;
    private DesktopAppService service;
    private GraphController graph;
    private Stage stage;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        JavaFxTestSupport.runOnFx("dispose graph", () -> {
            if (graph != null) {
                graph.dispose();
                graph = null;
            }
            if (stage != null) {
                stage.close();
                stage = null;
            }
        });
        if (service != null) {
            service.shutdown();
            service = null;
        }
        JavaFxTestSupport.drainFxPulse();
    }

    @Test
    @Timeout(60)
    void checkboxClickAddsOnceAndSurvivesRefreshThenRemovesOnce() throws Exception {
        List<SignalCatalogKey> keys = openFortyTwoSignalGraph();
        SignalCatalogKey a = keys.get(0);
        SignalCatalogKey b = keys.get(1);
        SignalCatalogKey c = keys.get(2);
        SignalCatalogKey d = keys.get(3);
        SignalCatalogKey e = keys.get(4);
        SignalCatalogKey f = keys.get(5);
        SignalCatalogKey g = keys.get(6);

        JavaFxTestSupport.runOnFx("select ABCD", () -> {
            graph.toggleMembershipForTest(a, true);
            graph.toggleMembershipForTest(b, true);
            graph.toggleMembershipForTest(c, true);
            graph.toggleMembershipForTest(d, true);
            graph.resetMembershipCommandCountForTest();
            layoutExplorer();
        });
        JavaFxTestSupport.drainFxPulse();

        assertEquals(List.of(a, b, c, d), graph.selectedMembershipForTest());
        assertEquals(42, graph.graphCatalogForTest().size());

        JavaFxTestSupport.runOnFx("click E once plus bubbled row event", () -> {
            graph.resetCheckboxActionCountForTest();
            graph.fireExplorerCheckboxMouseGestureForTest(e);
            graph.simulateExplorerRowClickOnCheckboxForTest(e);
            layoutExplorer();
        });
        JavaFxTestSupport.drainFxPulse();
        JavaFxTestSupport.drainFxPulse();

        assertEquals(1, graph.checkboxActionCountForTest(), "CheckBox.onAction must fire once");
        assertEquals(1, graph.membershipCommandCountForTest(), "one physical click must be one membership command");
        assertEquals(5, graph.selectedMembershipForTest().size());
        assertEquals(List.of(a, b, c, d, e), graph.selectedMembershipForTest());
        assertTrue(graph.isSelectedForTest(e));
        assertTrue(graph.isVisibleForTest(e));
        assertTrue(graph.isSelectedForTest(a));
        assertTrue(graph.isSelectedForTest(b));
        assertTrue(graph.isSelectedForTest(c));
        assertTrue(graph.isSelectedForTest(d));

        JavaFxTestSupport.runOnFx("apply async-style refresh", () -> {
            graph.requestViewportForTest();
            layoutExplorer();
        });
        JavaFxTestSupport.drainFxPulse();
        JavaFxTestSupport.drainFxPulse();
        assertEquals(5, graph.selectedMembershipForTest().size());
        assertEquals(List.of(a, b, c, d, e), graph.selectedMembershipForTest());
        assertEquals(1, graph.membershipCommandCountForTest());
        assertEquals(1, graph.checkboxActionCountForTest());

        JavaFxTestSupport.runOnFx("click E again", () -> {
            graph.fireExplorerCheckboxMouseGestureForTest(e);
            graph.simulateExplorerRowClickOnCheckboxForTest(e);
            layoutExplorer();
        });
        JavaFxTestSupport.drainFxPulse();
        assertEquals(2, graph.checkboxActionCountForTest());
        assertEquals(2, graph.membershipCommandCountForTest());
        assertEquals(4, graph.selectedMembershipForTest().size());
        assertEquals(List.of(a, b, c, d), graph.selectedMembershipForTest());
        assertFalse(graph.isSelectedForTest(e));

        JavaFxTestSupport.runOnFx("click E F G", () -> {
            graph.resetMembershipCommandCountForTest();
            graph.resetCheckboxActionCountForTest();
            graph.fireExplorerCheckboxMouseGestureForTest(e);
            graph.fireExplorerCheckboxMouseGestureForTest(f);
            graph.fireExplorerCheckboxMouseGestureForTest(g);
            layoutExplorer();
        });
        JavaFxTestSupport.drainFxPulse();
        assertEquals(3, graph.checkboxActionCountForTest());
        assertEquals(3, graph.membershipCommandCountForTest());
        assertEquals(7, graph.selectedMembershipForTest().size());
        assertEquals(List.of(a, b, c, d, e, f, g), graph.selectedMembershipForTest());
    }

    @Test
    @Timeout(60)
    void explorerCheckboxClickDoesNotStealWaveformSelectedTime() throws Exception {
        List<SignalCatalogKey> keys = openFortyTwoSignalGraph();
        SignalCatalogKey a = keys.get(0);
        SignalCatalogKey b = keys.get(1);
        SignalCatalogKey c = keys.get(2);
        SignalCatalogKey d = keys.get(3);
        SignalCatalogKey e = keys.get(4);
        long t1 = 2_000L;
        JavaFxTestSupport.runOnFx("select ABCD and pin selected time", () -> {
            graph.toggleMembershipForTest(a, true);
            graph.toggleMembershipForTest(b, true);
            graph.toggleMembershipForTest(c, true);
            graph.toggleMembershipForTest(d, true);
            graph.setViewportForTest(1_000L, 10_000L);
            graph.selectCursorAForTest(t1);
            graph.resetMembershipCommandCountForTest();
            graph.resetCheckboxActionCountForTest();
            layoutExplorer();
        });
        awaitSettledViewport();
        assertEquals(t1, graph.cursorAForTest());

        JavaFxTestSupport.runOnFx("checkbox click must not change selected time", () -> {
            graph.fireExplorerCheckboxMouseGestureForTest(e);
            layoutExplorer();
        });
        JavaFxTestSupport.drainFxPulse();
        assertEquals(t1, graph.cursorAForTest(), "LEFT explorer click must not change Selected Time");
        assertEquals(5, graph.selectedMembershipForTest().size());
        assertTrue(graph.isSelectedForTest(e));
        assertEquals(1, graph.checkboxActionCountForTest());
        awaitSettledViewport();

        long[] observed = new long[2];
        JavaFxTestSupport.runOnFx("waveform click owns selected time", () -> {
            observed[0] = graph.waveformTimestampAtXForTest(400);
            graph.clickWaveformAtXForTest(400);
            // Width and viewport are JavaFX state. Reading them off this thread
            // reports the unset canvas and makes the geometric time look like 0.
            observed[0] = graph.waveformTimestampAtXForTest(400);
            Long cursor = graph.cursorAForTest();
            observed[1] = cursor == null ? Long.MIN_VALUE : cursor;
        });
        JavaFxTestSupport.drainFxPulse();
        long t2 = observed[0];
        assertEquals(t2, observed[1]);
        assertTrue(t2 != t1, "waveform click must move Selected Time");
        assertEquals(5, graph.selectedMembershipForTest().size());
    }

    @Test
    @Timeout(60)
    void rowTextClickDoesNotToggleMembershipAndRecreationDoesNotDuplicateListeners() throws Exception {
        List<SignalCatalogKey> keys = openFortyTwoSignalGraph();
        SignalCatalogKey a = keys.get(0);
        SignalCatalogKey e = keys.get(4);
        JavaFxTestSupport.runOnFx("select A", () -> {
            graph.toggleMembershipForTest(a, true);
            graph.resetMembershipCommandCountForTest();
            graph.singleClickExplorerForTest(e);
            layoutExplorer();
        });
        JavaFxTestSupport.drainFxPulse();
        assertEquals(List.of(a), graph.selectedMembershipForTest());
        assertEquals(0, graph.membershipCommandCountForTest());
        assertEquals(e, graph.focusedExplorerKeyForTest());

        GraphController first = graph;
        JavaFxTestSupport.runOnFx("recreate controller", () -> {
            first.dispose();
            FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
            Parent root = loader.load();
            graph = loader.getController();
            stage.setScene(new Scene(root, 1366, 768));
            graph.bindMembershipForTest(service);
            layoutExplorer();
            graph.resetMembershipCommandCountForTest();
            graph.fireExplorerCheckboxForTest(e);
        });
        JavaFxTestSupport.drainFxPulse();
        assertEquals(1, graph.membershipCommandCountForTest());
        assertTrue(graph.isSelectedForTest(e));
        assertEquals(1, graph.selectedMembershipForTest().stream().filter(e::equals).count());
    }

    private List<SignalCatalogKey> openFortyTwoSignalGraph() throws Exception {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        service.newProject("Explorer Stability");
        CanonicalSignal[] signals = new CanonicalSignal[42];
        List<SignalCatalogKey> keys = new ArrayList<>(42);
        for (int i = 0; i < 42; i++) {
            String name = i == 4 ? "S01PID41_MonStatusDriveCycle" : "Sig" + String.format("%02d", i);
            signals[i] = new CanonicalSignal(
                    name, "OBD2", 1_000L * (i + 1), 0x100 + i, i + 1, i + 1.0, (long) i, "u", null,
                    SourceReference.ofLine(i + 1));
            keys.add(new SignalCatalogKey(
                    name, SignalSourceType.DBC_CAN, "OBD2", 0x100 + i, i + 1, "run.mf4", "veh.dbc"));
        }
        InMemorySignalCatalogStore catalog = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 1_000);
        catalog.accept(List.of(signals));
        service.signalsModel().replaceCatalog(catalog);

        JavaFxTestSupport.runOnFx("load graph", () -> {
            FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
            Parent root = loader.load();
            graph = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, 1366, 768));
            stage.show();
            graph.bindMembershipForTest(service);
            layoutExplorer();
        });
        JavaFxTestSupport.drainFxPulse();
        assertEquals(42, graph.graphCatalogForTest().size());
        assertEquals(42, graph.explorerRowsForTest().size());
        return keys;
    }

    private void awaitSettledViewport() throws Exception {
        long previous = Long.MIN_VALUE;
        for (int attempt = 0; attempt < 40; attempt++) {
            long[] sample = new long[1];
            JavaFxTestSupport.runOnFx("sample geometric time", () -> sample[0] = graph.waveformTimestampAtXForTest(400));
            JavaFxTestSupport.drainFxPulse();
            boolean loading = graph.graphStatusForTest() != null && graph.graphStatusForTest().startsWith("Loading");
            if (!loading && sample[0] == previous && graph.viewportStartForTest() == 1_000L
                    && graph.viewportEndForTest() == 10_000L) {
                return;
            }
            previous = sample[0];
            Thread.sleep(50);
        }
    }

    private void layoutExplorer() {
        ListView<SignalCatalogEntry> list = graph.signalListForTest();
        if (list != null) {
            list.applyCss();
            list.layout();
            if (list.getScene() != null && list.getScene().getRoot() != null) {
                list.getScene().getRoot().applyCss();
                list.getScene().getRoot().layout();
            }
        }
    }
}
