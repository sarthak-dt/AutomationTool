package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.desktop.presentation.SignalsProcessingContext;
import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Screenshot defect regression: chips / title / status / columns must share one membership SoT.
 * Never allow chip count = 1 with signal column count = 0.
 */
class ActiveSignalWorkspaceMembershipSyncTest {

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
    void catalogSwapFocusRefreshAndAsyncSnapshotKeepChipTitleStatusAndColumnsInSync() throws Exception {
        InMemorySignalCatalogStore physical = catalogWith(
                can("S", "OBD2", 0x7E8, 1_000L, 42.0, 1L),
                can("S", "OBD2", 0x7E8, 2_000L, 43.0, 2L)
        );
        model = new SignalsScreenModel();
        model.replaceCatalog(physical, "View", SignalsProcessingContext.of("A.mf4", "veh.dbc", "Ch1"));
        SignalCatalogKey physicalKey = key("S", "OBD2", 0x7E8);
        assertTrue(model.addToWorkspace(physicalKey));

        AtomicReference<ActiveSignalWorkspacePane> paneRef = new AtomicReference<>();
        runOnFx(() -> {
            ActiveSignalWorkspacePane pane = new ActiveSignalWorkspacePane();
            new Scene(pane, 1200, 800);
            Stage stage = new Stage();
            stage.setScene(pane.getScene());
            stage.show();
            pane.setModel(model);
            paneRef.set(pane);
        });

        waitForMembership(paneRef.get(), 1);
        assertMembershipChrome(paneRef.get(), 1);

        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(physical, "srcA", "dbcA", "A.mf4", "veh.dbc")
        ), "project", "dbc");
        model.replaceCatalog(merged, "Explore",
                SignalsProcessingContext.quickDecode("A", "veh.dbc", "1 Channel"), true);

        runOnFx(() -> {
            paneRef.get().reload(true);
            // Focus refresh must not become a chips-only membership update path.
            paneRef.get().refreshFocusPresentation();
        });

        waitForMembership(paneRef.get(), 1);
        waitForRows(paneRef.get(), 1);
        assertMembershipChrome(paneRef.get(), 1);
        assertEquals(1, model.workspaceKeys().size());
        assertTrue(paneRef.get().titleLabel().getText().contains("(1 signal)"));
        assertTrue(paneRef.get().statusSelectedLabelForTest().getText().startsWith("1 "));
        assertTrue(paneRef.get().tableColumnCountForTest() >= 4,
                "Timestamp + signal column required when membership is non-empty");
        assertEquals(1, paneRef.get().signalColumnCountForTest());
        assertFalse(paneRef.get().table().getItems().isEmpty(), "sample rows must load without reprocess");
    }

    private static void assertMembershipChrome(ActiveSignalWorkspacePane pane, int expected) {
        assertEquals(expected, pane.chipCountForTest(), "chips");
        assertEquals(expected, pane.signalColumnCountForTest(), "signal columns");
        assertTrue(pane.titleLabel().getText().contains("(" + expected + " signal"),
                pane.titleLabel().getText());
        assertTrue(pane.statusSelectedLabelForTest().getText().startsWith(expected + " "),
                pane.statusSelectedLabelForTest().getText());
    }

    private static void waitForMembership(ActiveSignalWorkspacePane pane, int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 8_000L;
        while (System.currentTimeMillis() < deadline) {
            AtomicReference<Boolean> ok = new AtomicReference<>(false);
            runOnFx(() -> ok.set(
                    pane.chipCountForTest() == expected
                            && pane.signalColumnCountForTest() == expected
                            && !pane.table().getColumns().isEmpty()));
            if (Boolean.TRUE.equals(ok.get())) {
                return;
            }
            Thread.sleep(50);
        }
        runOnFx(() -> assertMembershipChrome(pane, expected));
    }

    private static void waitForRows(ActiveSignalWorkspacePane pane, int min) throws Exception {
        long deadline = System.currentTimeMillis() + 8_000L;
        while (System.currentTimeMillis() < deadline) {
            AtomicReference<Integer> count = new AtomicReference<>(0);
            runOnFx(() -> count.set(pane.table().getItems().size()));
            if (count.get() >= min) {
                return;
            }
            Thread.sleep(50);
        }
        runOnFx(() -> assertFalse(pane.table().getItems().isEmpty(),
                "timed out waiting for workspace sample rows"));
    }

    private static void runOnFx(Runnable action) throws Exception {
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
        assertTrue(done.await(15, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static InMemorySignalCatalogStore catalogWith(CanonicalSignal... signals) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("demo.mf4", "demo.dbc", 1_000);
        store.accept(List.of(signals));
        return store;
    }

    private static CanonicalSignal can(
            String name, String message, long canId, long ts, double value, long index
    ) {
        return new CanonicalSignal(name, message, ts, canId, 0, value, index, "u", null, SourceReference.ofLine(1));
    }

    private static SignalCatalogKey key(String name, String message, long canId) {
        return new SignalCatalogKey(name, SignalSourceType.DBC_CAN, message, canId, "demo.mf4", "demo.dbc");
    }
}
