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
import com.stellar.platform.desktop.presentation.InventoryStudySet;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.MatchFilter;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.StudyMessageKey;
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

class SourceInventoryStudyBulkUiTest {

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
    void selectAllMatchedAddsEligibleOnlyAndIsIdempotent() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();

            selectCan(workspace, 0x7E8);
            assertEquals("0x7E8", workspace.detailsInspectorForTest().canIdForTest());
            SourceChannelKey channelBefore = SourceChannelKey.of(fx.file.projectSourceId(), 1);

            int added = workspace.selectAllMatchedIntoStudy();
            assertEquals(1, added);
            assertEquals(1, workspace.studySetForTest().size());
            assertTrue(workspace.hasCenterStudySetCountLabelForTest());
                assertTrue(workspace.detailsInspectorForTest().studyModeButtonForTest().getText().contains("1"));
            assertTrue(workspace.studySetForTest().contains(
                    StudyMessageKey.of(channelBefore, 0x7E8, false)));

            assertEquals(0, workspace.selectAllMatchedIntoStudy());
            assertEquals(1, workspace.studySetForTest().size());

            assertEquals("0x7E8", workspace.detailsInspectorForTest().canIdForTest());
            assertEquals(fx.file.projectSourceId(), workspace.modelForTest().selectedSourceId());
            assertEquals(1, workspace.modelForTest().selectedChannelId().orElse(-1));
            assertTrue(workspace.selectAllMatchedButtonForTest().isDisable());
            dispose(stage);
        });
    }

    @Test
    @Timeout(60)
    void selectVisibleRespectsActiveFilters() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();

            workspace.chipMatchedForTest().fire();
            assertEquals(MatchFilter.MATCHED, workspace.modelForTest().matchFilter());
            assertEquals(1, workspace.tableForTest().getItems().size());

            assertEquals(1, workspace.selectVisibleIntoStudy());
            assertEquals(1, workspace.studySetForTest().size());

            workspace.chipUnmatchedForTest().fire();
            assertEquals(0, workspace.selectVisibleIntoStudy());
            assertEquals(1, workspace.studySetForTest().size());
            dispose(stage);
        });
    }

    @Test
    @Timeout(60)
    void clearStudyDoesNotChangeScopeOrInspectedRow() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();
            selectCan(workspace, 0x7E8);
            workspace.selectAllMatchedIntoStudy();
            assertFalse(workspace.studySetForTest().isEmpty());

            workspace.clearStudySet();
            assertTrue(workspace.studySetForTest().isEmpty());
            assertTrue(workspace.clearStudyButtonForTest().isDisable());
            assertEquals("0x7E8", workspace.detailsInspectorForTest().canIdForTest());
            assertEquals(1, workspace.modelForTest().selectedChannelId().orElse(-1));
            dispose(stage);
        });
    }

    @Test
    @Timeout(60)
    void allChannelsPreservesChannelProvenance() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.multiChannel();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectAllChannels(fx.file.projectSourceId());
            workspace.refreshFromModel();

            assertEquals(2, workspace.selectAllMatchedIntoStudy());
            InventoryStudySet set = workspace.studySetForTest();
            assertTrue(set.contains(StudyMessageKey.of(
                    SourceChannelKey.of(fx.file.projectSourceId(), 1), 0x453, false)));
            assertTrue(set.contains(StudyMessageKey.of(
                    SourceChannelKey.of(fx.file.projectSourceId(), 3), 0x453, false)));
            dispose(stage);
        });
    }

    @Test
    @Timeout(60)
    void bulkSelectionDoesNotTriggerInventoryRebuild() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();
            workspace.resetStructuralRebuildCountForTest();
            workspace.selectAllMatchedIntoStudy();
            workspace.selectVisibleIntoStudy();
            workspace.clearStudySet();
            assertEquals(0, workspace.structuralRebuildCountForTest());
            dispose(stage);
        });
    }

    @Test
    @Timeout(60)
    void actionBarStaysReadableWhenStudyDrawerOpensAndCloses() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();
            workspace.layout();

            assertTrue(workspace.isInspectorCollapsedForTest());
            assertTrue(workspace.scopeRowForTest().isVisible());
            assertTrue(workspace.selectAllMatchedButtonForTest().getText().startsWith("Add All Matched ("));
            assertTrue(workspace.selectVisibleButtonForTest().getText().startsWith("Add Filtered ("));
            assertEquals("Clear Study Set", workspace.clearStudyButtonForTest().getText());
            assertTrue(workspace.openStudySetButtonForTest().getText().startsWith("Study Set ("));
            assertTrue(workspace.actionLabelsFullyVisibleForTest());
            assertTrue(workspace.usesUnconstrainedTableResizeForTest());
            assertTrue(workspace.isTableBorderPaneCenterForTest());
            double tableBefore = workspace.tableHeightForTest();
            assertTrue(tableBefore > 280, "table should dominate vertical space, was " + tableBefore);

            // Distinct actions: labels differ; methods remain separate entry points.
            assertFalse(workspace.selectAllMatchedButtonForTest().getText()
                    .equals(workspace.selectVisibleButtonForTest().getText()));

            workspace.openStudySetButtonForTest().fire();
            workspace.layout();
            assertFalse(workspace.isInspectorCollapsedForTest());
            assertTrue(workspace.detailsInspectorForTest().isStudyMode());
            assertTrue(workspace.openStudySetButtonForTest().getText().startsWith("Study Set ("));
            assertTrue(workspace.scopeRowForTest().isManaged());
            assertTrue(workspace.scopeRowForTest().isVisible());
            assertTrue(workspace.chipAllForTest().isVisible());
            assertTrue(workspace.actionLabelsFullyVisibleForTest());
            assertEquals(tableBefore, workspace.tableHeightForTest(), 8.0,
                    "opening Study Set must not collapse table height");

            workspace.openStudySetButtonForTest().fire();
            workspace.layout();
            assertTrue(workspace.isInspectorCollapsedForTest());
            assertTrue(workspace.openStudySetButtonForTest().getText().startsWith("Study Set ("));
            assertTrue(workspace.actionLabelsFullyVisibleForTest());
            assertTrue(workspace.usesUnconstrainedTableResizeForTest());
            dispose(stage);
        });
    }

    private static void selectCan(SourceInventoryWorkspace workspace, long canId) {
        var row = workspace.tableForTest().getItems().stream()
                .filter(r -> r.canId() == canId)
                .findFirst().orElseThrow();
        workspace.tableForTest().getSelectionModel().select(row);
        workspace.expandInspectorForTest();
        workspace.detailsInspectorForTest().showInspectMode();
        workspace.detailsInspectorForTest().setSelection(row);
    }

    private static Stage show(SourceInventoryWorkspace workspace, double w, double h) {
        StackPane host = new StackPane(workspace);
        Scene scene = new Scene(host, w, h);
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

        private Fixture(DesktopAppService service, WorkspaceFile file) {
            this.service = service;
            this.file = file;
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
            service.definitionMatchingCoordinator().registerDefinition(defId, "11_bit_OBD2 2.dbc", lavasaDbc());
            SourceChannelKey key = SourceChannelKey.of(file.projectSourceId(), 1);
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    key, ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    defId, "11_bit_OBD2 2.dbc", null, "", 3, 1, 1.0 / 3.0));
            return new Fixture(service, file);
        }

        static Fixture multiChannel() {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile file = service.workspace().addFile(
                    new WorkspaceFile(Path.of("multi_ch.mf4").toAbsolutePath(),
                            FileKind.MEASUREMENT_MF4, 10));
            CanMessageInventory m1 = new CanMessageInventory(new CanMessageKey(1, 0x453, false), 100, 8, 8, false);
            CanMessageInventory m3 = new CanMessageInventory(new CanMessageKey(3, 0x453, false), 200, 8, 8, false);
            CanChannelInventory ch1 = new CanChannelInventory(1, "Channel 1", 100, 1, 1, 0, 0, List.of(m1));
            CanChannelInventory ch3 = new CanChannelInventory(3, "Channel 3", 200, 1, 1, 0, 0, List.of(m3));
            CanSourceInventory inv = new CanSourceInventory(
                    file.path(), file.fileName(), MeasurementSourceFormat.MF4,
                    CanInventoryStatus.INVENTORY_READY, 2, 300, 2, true, List.of(ch1, ch3), List.of());
            service.sourceInventoryModel().put(file.id(), inv);
            DefinitionSourceId defId = DefinitionSourceId.generate();
            DbcSignal sig = new DbcSignal(
                    "SIG", 0, 8, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,
                    1, 0, 0, 255, "", List.of(), DbcMultiplexRole.NONE, null, "", DbcValueTable.empty());
            DbcMessage msg = new DbcMessage(CanIdKey.fromFrame(0x453, false), "IS_DAT", 8, "ECU", List.of(sig), "");
            DbcDatabase dbc = new DbcDatabase("", List.of(), List.of(msg), List.of());
            service.definitionMatchingCoordinator().registerDefinition(defId, "3.dbc", dbc);
            for (int ch : new int[]{1, 3}) {
                SourceChannelKey key = SourceChannelKey.of(file.projectSourceId(), ch);
                service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                        key, ChannelMappingStatus.CONFIRMED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                        null, "", defId, "3.dbc", 1, 1, 1.0));
            }
            return new Fixture(service, file);
        }

        SourceInventoryWorkspace bindWorkspace() {
            SourceInventoryWorkspace workspace = new SourceInventoryWorkspace();
            workspace.bind(service, () -> { });
            workspace.showDetailedMapping();
            return workspace;
        }

        private static DbcDatabase lavasaDbc() {
            List<DbcSignal> signals = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                signals.add(new DbcSignal(
                        "SIG_" + i, Math.min(i, 56), 8, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,
                        1, 0, 0, 255, "", List.of(),
                        DbcMultiplexRole.NONE, null, "", DbcValueTable.empty()));
            }
            DbcMessage msg = new DbcMessage(CanIdKey.fromFrame(0x7E8, false), "OBD2", 8, "ECU", signals, "");
            return new DbcDatabase("", List.of(), List.of(msg), List.of());
        }
    }
}
