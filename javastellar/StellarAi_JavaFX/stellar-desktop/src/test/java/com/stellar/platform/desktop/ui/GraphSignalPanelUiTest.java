package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphSignalPanelUiTest {

    @TempDir Path temp;

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
    @Timeout(60)
    void collapseExpandsTheGraphAndBulkSelectionStaysOnTheFilteredRows() throws Exception {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        service.newProject("Graph panel");
        List<SignalCatalogKey> keys = new ArrayList<>();
        List<CanonicalSignal> signals = new ArrayList<>();
        String[] names = {"Alpha", "AlphaTwo", "AlphaThree", "Beta"};
        for (int i = 0; i < names.length; i++) {
            signals.add(new CanonicalSignal(
                    names[i], "OBD2", 1_000L * (i + 1), 0x100 + i, 1, i + 1.0, (long) i, "u", null,
                    SourceReference.ofLine(i + 1)));
            keys.add(new SignalCatalogKey(
                    names[i], SignalSourceType.DBC_CAN, "OBD2", 0x100 + i, 1, "run.mf4", "veh.dbc"));
        }
        InMemorySignalCatalogStore catalog = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 1_000);
        catalog.accept(signals);
        service.signalsModel().replaceCatalog(catalog);

        AtomicReference<GraphController> graphRef = new AtomicReference<>();
        try {
        run(() -> {
            FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
            Parent root = loader.load();
            GraphController graph = loader.getController();
            graphRef.set(graph);
            Stage stage = new Stage();
            stage.setScene(new Scene(root, 1366, 768));
            stage.show();
            root.applyCss();
            root.layout();
            assertTrue(graph.collapseButtonInHeaderForTest());
            assertEquals(
                    java.util.List.of("All", "Selected", "DBC"),
                    graph.explorerFilterLabelsForTest());
            assertTrue(graph.bulkActionsReadableForTest());

            long requests = graph.viewportRequestsForTest();
            VBox canvas = graph.graphCanvasColumnForTest();
            assertNotNull(canvas);
            graph.collapseSignalPanelForTest();
            assertTrue(graph.signalPanelCollapsedForTest());
            assertFalse(graph.signalPanelAttachedForTest());
            assertTrue(graph.signalPanelRailVisibleForTest());
            assertTrue(graph.graphWorkspaceSplitForTest().getItems().contains(canvas));
            assertEquals(requests, graph.viewportRequestsForTest());

            graph.expandSignalPanelForTest();
            assertFalse(graph.signalPanelCollapsedForTest());
            assertTrue(graph.signalPanelAttachedForTest());
            assertFalse(graph.signalPanelRailVisibleForTest());
            assertEquals(requests, graph.viewportRequestsForTest());

            graph.bindMembershipForTest(service);
            graph.searchExplorerForTest("Alpha");
            assertEquals(3, graph.explorerRowsForTest().size());
            long beforeSelect = graph.viewportRequestsForTest();
            int commands = graph.membershipCommandCountForTest();
            graph.selectAllFilteredForTest();
            assertEquals(commands + 1, graph.membershipCommandCountForTest());
            assertTrue(graph.viewportRequestsForTest() - beforeSelect < graph.explorerRowsForTest().size()
                    || graph.explorerRowsForTest().size() <= 1);
            assertTrue(graph.viewportRequestsForTest() - beforeSelect <= 2);
            assertEquals(3, graph.selectedMembershipForTest().size());
            assertTrue(graph.selectedMembershipForTest().stream().allMatch(key -> key.signalName().startsWith("Alpha")));
            assertTrue(graph.selectedMembershipForTest().stream().noneMatch(key -> "Beta".equals(key.signalName())));

            graph.deselectAllFilteredForTest();
            assertTrue(graph.selectedMembershipForTest().isEmpty());
        });
        if (graphRef.get() != null) {
            run(graphRef.get()::dispose);
        }
        } finally {
            service.shutdown();
        }
    }

    private static void run(FxAction action) throws Exception {
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
        assertTrue(done.await(40, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @FunctionalInterface
    private interface FxAction {
        void run() throws Exception;
    }
}
