package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcByteOrder;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.model.DbcValueTable;
import com.stellar.platform.dbc.model.DbcValueType;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.InventoryEnrichmentMatchState;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.MatchFilter;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.FileKind;
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
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceInventoryMasterDetailUiTest {

    @BeforeAll
    static void initJavaFx() throws Exception {
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

    @Test
    @Timeout(60)
    void tableHeightStableWhenSelectingMatchedRowWithManySignals() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();
            workspace.layout();
            workspace.tableForTest().layout();

            double before = workspace.tableHeightForTest();
            assertTrue(before > 200, "table should be tall before selection: " + before);

            // Select matched 0x7E8 (149 signals) — inspector-only update; avoid extra root layout pulses.
            workspace.tableForTest().getSelectionModel().select(
                    workspace.tableForTest().getItems().stream()
                            .filter(r -> r.canId() == 0x7E8L)
                            .findFirst().orElseThrow());
            double after = workspace.tableHeightForTest();
            assertEquals(before, after, 2.0, "table height must not collapse on selection");
            assertTrue(workspace.detailsInspectorForTest().isShowingContentForTest());
            assertEquals("0x7E8", workspace.detailsInspectorForTest().canIdForTest());
            assertEquals("MATCHED", workspace.detailsInspectorForTest().matchForTest());
            assertEquals("OBD2", workspace.detailsInspectorForTest().messageForTest());
            assertEquals(149, workspace.detailsInspectorForTest().visibleSignalCountForTest());
            assertFalse(workspace.hasBottomDetailPaneForTest());
            workspace.detailsInspectorForTest().clearSelection();
            workspace.tableForTest().getSelectionModel().clearSelection();
            dispose(stage);
        });
    }

    @Test
    @Timeout(60)
    void rowToRowNavigationKeepsSourceChannelAndUpdatesInspector() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();

            var table = workspace.tableForTest();
            assertEquals(3, table.getItems().size());

            workspace.resetStructuralRebuildCountForTest();
            selectCan(workspace, 0x7E8);
            assertEquals("MATCHED", workspace.detailsInspectorForTest().matchForTest());
            assertEquals(fx.file.projectSourceId(), workspace.modelForTest().selectedSourceId());
            assertEquals(1, workspace.modelForTest().selectedChannelId().orElse(-1));

            selectCan(workspace, 0x316);
            assertEquals("UNMATCHED", workspace.detailsInspectorForTest().matchForTest());
            assertEquals("—", workspace.detailsInspectorForTest().messageForTest());
            assertEquals(0, workspace.detailsInspectorForTest().visibleSignalCountForTest());
            assertEquals(1, workspace.modelForTest().selectedChannelId().orElse(-1));

            selectCan(workspace, 0x329);
            assertEquals("UNMATCHED", workspace.detailsInspectorForTest().matchForTest());

            selectCan(workspace, 0x7E8);
            assertEquals("MATCHED", workspace.detailsInspectorForTest().matchForTest());
            assertEquals("OBD2", workspace.detailsInspectorForTest().messageForTest());
            assertEquals(1, workspace.modelForTest().selectedChannelId().orElse(-1));
            assertEquals(3, table.getItems().size());
            assertEquals(0, workspace.structuralRebuildCountForTest(),
                    "CAN ID selection must not rebuild the navigator");
            workspace.detailsInspectorForTest().clearSelection();
            workspace.tableForTest().getSelectionModel().clearSelection();
            dispose(stage);
        });
    }

    @Test
    @Timeout(60)
    void quickFilterChipsShareMatchFilterState() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();

            assertTrue(workspace.chipAllForTest().getText().contains("3"));
            assertTrue(workspace.chipMatchedForTest().getText().contains("1"));
            assertTrue(workspace.chipUnmatchedForTest().getText().contains("2"));

            workspace.chipMatchedForTest().fire();
            assertEquals(MatchFilter.MATCHED, workspace.modelForTest().matchFilter());
            assertEquals("Matched", workspace.matchComboForTest().getValue());
            assertEquals(1, workspace.tableForTest().getItems().size());
            assertEquals(0x7E8L, workspace.tableForTest().getItems().getFirst().canId());

            workspace.chipUnmatchedForTest().fire();
            assertEquals(MatchFilter.UNMATCHED, workspace.modelForTest().matchFilter());
            assertEquals("Unmatched", workspace.matchComboForTest().getValue());
            assertEquals(2, workspace.tableForTest().getItems().size());

            workspace.matchComboForTest().getSelectionModel().select("All");
            assertEquals(MatchFilter.ALL, workspace.modelForTest().matchFilter());
            assertTrue(workspace.chipAllForTest().isSelected());
            assertEquals(3, workspace.tableForTest().getItems().size());
            workspace.detailsInspectorForTest().clearSelection();
            workspace.tableForTest().getSelectionModel().clearSelection();
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void twoPaneDefaultWithOnDemandInspectorAndNoBottomDetail() throws Exception {
        runOnFx(() -> {
            SourceInventoryWorkspace workspace = new SourceInventoryWorkspace();
            assertEquals(2, workspace.splitForTest().getItems().size());
            assertTrue(workspace.isInspectorCollapsedForTest());
            assertFalse(workspace.hasBottomDetailPaneForTest());
            workspace.expandInspectorForTest();
            assertEquals(3, workspace.splitForTest().getItems().size());
            assertFalse(workspace.isInspectorCollapsedForTest());
        });
    }

    @Test
    @Timeout(60)
    void signalTableReceivesRemainingInspectorHeightForManySignals() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();
            workspace.layout();

            double tableBefore = workspace.tableHeightForTest();
            selectCan(workspace, 0x7E8);
            workspace.layout();
            workspace.detailsInspectorForTest().layout();
            workspace.detailsInspectorForTest().signalTableForTest().layout();

            CanInventoryDetailsInspector inspector = workspace.detailsInspectorForTest();
            assertTrue(inspector.isShowingSignalTableForTest());
            assertEquals(149, inspector.visibleSignalCountForTest());
            assertTrue(inspector.summaryHeightForTest() > 30 && inspector.summaryHeightForTest() < 160,
                    "compact summary expected, was " + inspector.summaryHeightForTest());
            double signalH = inspector.signalTableHeightForTest();
            // ~8 rows × 28px + header ≈ 256; require materially taller than the old ~2–3 row cap.
            assertTrue(signalH >= 240,
                    "signal table should fill remaining inspector height, was " + signalH);
            double approxVisibleRows = (signalH - 32) / 28.0;
            assertTrue(approxVisibleRows >= 7.5,
                    "expected ~8+ visible signal rows, approx " + approxVisibleRows);
            assertEquals(tableBefore, workspace.tableHeightForTest(), 2.0,
                    "main CAN table height must stay stable");
            dispose(stage);
        });
    }

    @Test
    @Timeout(60)
    void unmatchedSelectionShowsHintWithoutSignalTableAndKeepsCenterHeight() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();
            workspace.layout();
            double tableBefore = workspace.tableHeightForTest();

            selectCan(workspace, 0x316);
            workspace.layout();
            CanInventoryDetailsInspector inspector = workspace.detailsInspectorForTest();
            assertTrue(inspector.isShowingUnmatchedSignalsHintForTest());
            assertFalse(inspector.isShowingSignalTableForTest());
            assertEquals(0, inspector.visibleSignalCountForTest());
            assertEquals(tableBefore, workspace.tableHeightForTest(), 2.0);
            dispose(stage);
        });
    }

    @Test
    @Timeout(60)
    void collapseAndReopenKeepsSelectedCanContext() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();
            selectCan(workspace, 0x7E8);
            workspace.detailsInspectorForTest().filterSignalsForTest("SIG_1");
            assertEquals("0x7E8", workspace.detailsInspectorForTest().canIdForTest());

            workspace.collapseInspectorForTest();
            assertTrue(workspace.isInspectorCollapsedForTest());
            assertEquals(0x7E8L, workspace.tableForTest().getSelectionModel().getSelectedItem().canId());

            workspace.expandInspectorForTest();
            assertFalse(workspace.isInspectorCollapsedForTest());
            assertEquals("0x7E8", workspace.detailsInspectorForTest().canIdForTest());
            assertEquals("MATCHED", workspace.detailsInspectorForTest().matchForTest());
            assertTrue(workspace.detailsInspectorForTest().isShowingSignalTableForTest());
            dispose(stage);
        });
    }

    @Test
    @Timeout(60)
    void inspectorUsableAt1366AndFitsAt1600() throws Exception {
        runOnFx(() -> {
            for (double[] size : new double[][]{{1600, 900}, {1366, 768}}) {
                Fixture fx = Fixture.create();
                SourceInventoryWorkspace workspace = fx.bindWorkspace();
                Stage stage = show(workspace, size[0], size[1]);
                workspace.refreshFromModel();
                workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
                workspace.refreshFromModel();
                selectCan(workspace, 0x7E8);
                workspace.layout();
                workspace.detailsInspectorForTest().layout();

                CanInventoryDetailsInspector inspector = workspace.detailsInspectorForTest();
                assertTrue(inspector.getWidth() >= 280 && inspector.getWidth() <= 440);
                assertTrue(inspector.signalTableHeightForTest() >= 160,
                        "signal table usable at " + size[0] + ", height=" + inspector.signalTableHeightForTest());
                assertFalse(workspace.hasBottomDetailPaneForTest());
                dispose(stage);
            }
        });
    }

    private static void selectCan(SourceInventoryWorkspace workspace, long canId) {
        var row = workspace.tableForTest().getItems().stream()
                .filter(r -> r.canId() == canId)
                .findFirst().orElseThrow();
        workspace.tableForTest().getSelectionModel().select(row);
        workspace.inspectorDrawerForTest().showInspect();
        workspace.expandInspectorForTest();
        workspace.detailsInspectorForTest().showInspectMode();
        workspace.detailsInspectorForTest().setSelection(row);
    }

    private static Stage show(SourceInventoryWorkspace workspace, double w, double h) {
        StackPane host = new StackPane(workspace);
        Scene scene = new Scene(host, w, h);
        var css = SourceInventoryWorkspace.class.getResource("/css/stellar-desktop.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        host.resize(w, h);
        host.layout();
        workspace.resize(w - 40, h - 80);
        workspace.layout();
        return stage;
    }

    private static void dispose(Stage stage) {
        if (stage == null) {
            return;
        }
        Scene scene = stage.getScene();
        if (scene != null && scene.getRoot() instanceof javafx.scene.layout.Pane pane) {
            pane.getChildren().clear();
        }
        stage.close();
        stage.setScene(null);
    }

    private static void runOnFx(FxBody body) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(50, TimeUnit.SECONDS), "JavaFX task timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @FunctionalInterface
    private interface FxBody {
        void run() throws Exception;
    }

    private static final class Fixture {
        final DesktopAppService service;
        final WorkspaceFile file;
        final DefinitionSourceId defId;

        private Fixture(DesktopAppService service, WorkspaceFile file, DefinitionSourceId defId) {
            this.service = service;
            this.file = file;
            this.defId = defId;
        }

        static Fixture create() {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile file = service.workspace().addFile(
                    new WorkspaceFile(Path.of("OBD2_Data_Lavasa_102230.mf4").toAbsolutePath(),
                            FileKind.MEASUREMENT_MF4, 10));
            List<CanMessageInventory> messages = List.of(
                    new CanMessageInventory(new CanMessageKey(1, 0x316, false), 180_091, 8, 8, false),
                    new CanMessageInventory(new CanMessageKey(1, 0x329, false), 180_090, 8, 8, false),
                    new CanMessageInventory(new CanMessageKey(1, 0x7E8, false), 12_928, 8, 8, false)
            );
            CanChannelInventory ch = new CanChannelInventory(
                    1, "Channel 1", 373_109, 3, 3, 0, 0, messages);
            CanSourceInventory inv = new CanSourceInventory(
                    file.path(), file.fileName(), MeasurementSourceFormat.MF4,
                    CanInventoryStatus.INVENTORY_READY, 1, 373_109, 3, true, List.of(ch), List.of());
            service.sourceInventoryModel().put(file.id(), inv);

            DefinitionSourceId defId = DefinitionSourceId.generate();
            DbcDatabase dbc = lavasaDbc();
            service.definitionMatchingCoordinator().registerDefinition(defId, "11_bit_OBD2 2.dbc", dbc);
            SourceChannelKey key = SourceChannelKey.of(file.projectSourceId(), 1);
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    key, ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    defId, "11_bit_OBD2 2.dbc", null, "", 3, 1, 1.0 / 3.0));
            return new Fixture(service, file, defId);
        }

        SourceInventoryWorkspace bindWorkspace() {
            SourceInventoryWorkspace workspace = new SourceInventoryWorkspace();
            workspace.bind(service, () -> { });
            workspace.showDetailedMapping();
            return workspace;
        }

        private static DbcDatabase lavasaDbc() {
            List<DbcSignal> signals = new ArrayList<>();
            for (int i = 0; i < 149; i++) {
                signals.add(new DbcSignal(
                        "SIG_" + i, Math.min(i, 56), 8, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,
                        1, 0, 0, 255, i == 0 ? "rpm" : "", List.of(),
                        DbcMultiplexRole.NONE, null, "", DbcValueTable.empty()));
            }
            DbcMessage msg = new DbcMessage(CanIdKey.fromFrame(0x7E8, false), "OBD2", 8, "ECU", signals, "");
            return new DbcDatabase("", List.of(), List.of(msg), List.of());
        }
    }
}
