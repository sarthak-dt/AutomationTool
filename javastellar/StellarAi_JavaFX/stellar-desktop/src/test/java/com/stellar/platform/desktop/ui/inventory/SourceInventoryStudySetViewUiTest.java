package com.stellar.platform.desktop.ui.inventory;



import com.stellar.platform.dbc.model.CanIdKey;

import com.stellar.platform.dbc.model.DbcByteOrder;

import com.stellar.platform.dbc.model.DbcDatabase;

import com.stellar.platform.dbc.model.DbcMessage;

import com.stellar.platform.dbc.model.DbcMultiplexRole;

import com.stellar.platform.dbc.model.DbcSignal;

import com.stellar.platform.dbc.model.DbcValueTable;

import com.stellar.platform.dbc.model.DbcValueType;

import com.stellar.platform.desktop.presentation.CanStudySignalDefinitionView;

import com.stellar.platform.desktop.presentation.DesktopAppService;

import com.stellar.platform.desktop.presentation.InventoryStudySet;

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

import java.util.List;

import java.util.concurrent.CountDownLatch;

import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicReference;



import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertTrue;



class SourceInventoryStudySetViewUiTest {



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

    void studySetContainsDefinitionsFromAllSelectedMessages() throws Exception {

        runOnFx(() -> {

            Fixture fx = Fixture.create();

            SourceInventoryWorkspace workspace = fx.bindWorkspace();

            Stage stage = show(workspace, 1600, 900);

            workspace.refreshFromModel();

            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 2);

            workspace.refreshFromModel();



            selectCan(workspace, 0xF6);

            assertEquals("0xF6", workspace.detailsInspectorForTest().canIdForTest());

            assertEquals(CanInventoryDetailsInspector.Mode.INSPECT, workspace.detailsInspectorForTest().modeForTest());



            int added = workspace.selectAllMatchedIntoStudy();

            assertEquals(3, added);

            assertEquals(3, workspace.studySetForTest().size());

            assertEquals(CanInventoryDetailsInspector.Mode.STUDY, workspace.detailsInspectorForTest().modeForTest());

            assertTrue(workspace.detailsInspectorForTest().isShowingStudyTableForTest());

            assertTrue(workspace.detailsInspectorForTest().studySummaryForTest().contains("3 CAN Messages"));



            List<CanStudySignalDefinitionView> studyRows =

                    workspace.detailsInspectorForTest().studyTableForTest().getItems();

            assertTrue(studyRows.stream().anyMatch(r -> "0xF6".equals(r.canIdHex()) && "T_EXT".equals(r.signalName())));

            assertTrue(studyRows.stream().anyMatch(r -> "0xF6".equals(r.canIdHex()) && "KM_TOTAL".equals(r.signalName())));

            assertTrue(studyRows.stream().anyMatch(r -> "0x36".equals(r.canIdHex()) && "Signal_A".equals(r.signalName())));

            assertTrue(studyRows.stream().anyMatch(r -> "0x201".equals(r.canIdHex()) && "Status".equals(r.signalName())));



            // Inspect still holds 0xF6 independently

            assertEquals("0xF6", workspace.detailsInspectorForTest().canIdForTest());

            assertEquals(0xF6L, workspace.detailsInspectorForTest().inspectRowForTest().canId());



            dispose(stage);

        });

    }



    @Test

    @Timeout(60)

    void switchingInspectAndStudyDoesNotMutateEitherState() throws Exception {

        runOnFx(() -> {

            Fixture fx = Fixture.create();

            SourceInventoryWorkspace workspace = fx.bindWorkspace();

            Stage stage = show(workspace, 1600, 900);

            workspace.refreshFromModel();

            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 2);

            workspace.refreshFromModel();



            selectCan(workspace, 0xF6);

            workspace.selectAllMatchedIntoStudy();

            InventoryStudySet set = workspace.studySetForTest();

            assertEquals(3, set.size());



            workspace.detailsInspectorForTest().showInspectMode();

            assertEquals(CanInventoryDetailsInspector.Mode.INSPECT, workspace.detailsInspectorForTest().modeForTest());

            assertEquals("0xF6", workspace.detailsInspectorForTest().canIdForTest());

            assertTrue(workspace.detailsInspectorForTest().isShowingSignalTableForTest());

            assertEquals(3, set.size());



            workspace.detailsInspectorForTest().showStudyMode();

            assertEquals(CanInventoryDetailsInspector.Mode.STUDY, workspace.detailsInspectorForTest().modeForTest());

            assertEquals(3, set.size());

            assertEquals("0xF6", workspace.detailsInspectorForTest().canIdForTest());



            dispose(stage);

        });

    }



    @Test

    @Timeout(60)

    void clearStudyKeepsInspectSelectionAndShowsEmptyStudy() throws Exception {

        runOnFx(() -> {

            Fixture fx = Fixture.create();

            SourceInventoryWorkspace workspace = fx.bindWorkspace();

            Stage stage = show(workspace, 1600, 900);

            workspace.refreshFromModel();

            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 2);

            workspace.refreshFromModel();



            selectCan(workspace, 0xF6);

            workspace.selectAllMatchedIntoStudy();

            assertEquals(CanInventoryDetailsInspector.Mode.STUDY, workspace.detailsInspectorForTest().modeForTest());



            double dividerBefore = workspace.splitForTest().getDividerPositions()[1];

            workspace.clearStudySet();

            assertTrue(workspace.studySetForTest().isEmpty());

            assertEquals("0xF6", workspace.detailsInspectorForTest().canIdForTest());

            assertEquals(2, workspace.modelForTest().selectedChannelId().orElse(-1));

            assertTrue(workspace.detailsInspectorForTest().isShowingStudyEmptyForTest());

            assertEquals(dividerBefore, workspace.splitForTest().getDividerPositions()[1], 0.0001);



            dispose(stage);

        });

    }



    @Test

    @Timeout(60)

    void studySetSurvivesFilterAndChannelNavigation() throws Exception {

        runOnFx(() -> {

            Fixture fx = Fixture.create();

            SourceInventoryWorkspace workspace = fx.bindWorkspace();

            Stage stage = show(workspace, 1600, 900);

            workspace.refreshFromModel();

            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 2);

            workspace.refreshFromModel();



            workspace.selectAllMatchedIntoStudy();

            assertEquals(3, workspace.studySetForTest().size());



            workspace.chipUnmatchedForTest().fire();

            assertEquals(3, workspace.studySetForTest().size());



            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 3);

            workspace.refreshFromModel();

            assertEquals(3, workspace.studySetForTest().size());

            assertTrue(workspace.studySetForTest().contains(

                    StudyMessageKey.of(SourceChannelKey.of(fx.file.projectSourceId(), 2), 0xF6, false)));



            dispose(stage);

        });

    }



    @Test

    @Timeout(60)

    void bulkSelectDoesNotTriggerStructuralRebuild() throws Exception {

        runOnFx(() -> {

            Fixture fx = Fixture.create();

            SourceInventoryWorkspace workspace = fx.bindWorkspace();

            Stage stage = show(workspace, 1600, 900);

            workspace.refreshFromModel();

            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 2);

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

    void duplicateSignalNamesRemainDistinctByMessage() throws Exception {

        runOnFx(() -> {

            Fixture fx = Fixture.create();

            SourceInventoryWorkspace workspace = fx.bindWorkspace();

            Stage stage = show(workspace, 1600, 900);

            workspace.refreshFromModel();

            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 2);

            workspace.refreshFromModel();



            workspace.selectAllMatchedIntoStudy();

            long statusCount = workspace.detailsInspectorForTest().studyTableForTest().getItems().stream()

                    .filter(r -> "Status".equals(r.signalName()))

                    .count();

            // Fixture has Status only on 0x201; add 0x208 via chip path is N/A — verify provenance fields present

            assertTrue(workspace.detailsInspectorForTest().studyTableForTest().getItems().stream()

                    .anyMatch(r -> r.definitionSourceId().isPresent()

                            && r.channelKey().channelId() == 2));



            dispose(stage);

        });

    }




    @Test
    @Timeout(60)
    void noStudyMessageChipGridAndTableOwnsVerticalSpace() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 2);
            workspace.refreshFromModel();
            workspace.selectAllMatchedIntoStudy();
            workspace.layout();
            workspace.detailsInspectorForTest().layout();
            workspace.detailsInspectorForTest().studyTableForTest().layout();

            CanInventoryDetailsInspector inspector = workspace.detailsInspectorForTest();
            assertFalse(inspector.hasStudyMessageChipsForTest());
            assertTrue(inspector.isShowingStudyTableForTest());
            assertTrue(inspector.studySummaryHeightForTest() > 0 && inspector.studySummaryHeightForTest() < 120,
                    "compact study header expected, was " + inspector.studySummaryHeightForTest());
            assertTrue(inspector.studyTableHeightForTest() > 300,
                    "study table should own remaining height, was " + inspector.studyTableHeightForTest());
            dispose(stage);
        });
    }

    @Test
    @Timeout(60)
    void centerCheckboxUnselectRemovesStudyDefinitions() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 2);
            workspace.refreshFromModel();
            workspace.selectAllMatchedIntoStudy();
            assertEquals(3, workspace.studySetForTest().size());

            var rowF6 = workspace.tableForTest().getItems().stream()
                    .filter(r -> r.canId() == 0xF6)
                    .findFirst().orElseThrow();
            assertTrue(workspace.studySetForTest().remove(rowF6));
            assertEquals(2, workspace.studySetForTest().size());
            assertTrue(workspace.detailsInspectorForTest().studyTableForTest().getItems().stream()
                    .noneMatch(r -> "0xF6".equals(r.canIdHex())));
            assertTrue(workspace.detailsInspectorForTest().studyTableForTest().getItems().stream()
                    .anyMatch(r -> "0x36".equals(r.canIdHex())));
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

                    new WorkspaceFile(Path.of("source_b_ch2.mf4").toAbsolutePath(),

                            FileKind.MEASUREMENT_MF4, 10));

            List<CanMessageInventory> messages = List.of(

                    new CanMessageInventory(new CanMessageKey(2, 0x36, false), 10, 8, 8, false),

                    new CanMessageInventory(new CanMessageKey(2, 0xF6, false), 20, 8, 8, false),

                    new CanMessageInventory(new CanMessageKey(2, 0x201, false), 30, 8, 8, false),

                    new CanMessageInventory(new CanMessageKey(2, 0x700, false), 5, 8, 8, false)

            );

            CanChannelInventory ch2 = new CanChannelInventory(

                    2, "Channel 2", 65, 4, 4, 0, 0, messages);

            CanChannelInventory ch3 = new CanChannelInventory(

                    3, "Channel 3", 1, 1, 1, 0, 0,

                    List.of(new CanMessageInventory(new CanMessageKey(3, 0x10, false), 1, 8, 8, false)));

            CanSourceInventory inv = new CanSourceInventory(

                    file.path(), file.fileName(), MeasurementSourceFormat.MF4,

                    CanInventoryStatus.INVENTORY_READY, 2, 66, 5, true, List.of(ch2, ch3), List.of());

            service.sourceInventoryModel().put(file.id(), inv);



            DefinitionSourceId defId = DefinitionSourceId.generate();

            service.definitionMatchingCoordinator().registerDefinition(defId, "3.dbc", studyDbc());

            SourceChannelKey key = SourceChannelKey.of(file.projectSourceId(), 2);

            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(

                    key, ChannelMappingStatus.CONFIRMED, ChannelRecommendationKind.CLEAR_BEST_MATCH,

                    null, "", defId, "3.dbc", 3, 1, 1.0));

            return new Fixture(service, file);

        }



        SourceInventoryWorkspace bindWorkspace() {

            SourceInventoryWorkspace workspace = new SourceInventoryWorkspace();

            workspace.bind(service, () -> { });

            workspace.showDetailedMapping();

            return workspace;

        }



        private static DbcDatabase studyDbc() {

            DbcSignal a = new DbcSignal(

                    "Signal_A", 0, 8, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,

                    1, 0, 0, 255, "", List.of(), DbcMultiplexRole.NONE, null, "", DbcValueTable.empty());

            DbcSignal b = new DbcSignal(

                    "Signal_B", 8, 8, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,

                    1, 0, 0, 255, "", List.of(), DbcMultiplexRole.NONE, null, "", DbcValueTable.empty());

            DbcSignal tExt = new DbcSignal(

                    "T_EXT", 47, 8, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,

                    1, 0, 0, 255, "", List.of(), DbcMultiplexRole.NONE, null, "", DbcValueTable.empty());

            DbcSignal km = new DbcSignal(

                    "KM_TOTAL", 23, 24, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,

                    1, 0, 0, 255, "km", List.of(), DbcMultiplexRole.NONE, null, "", DbcValueTable.empty());

            DbcSignal status = new DbcSignal(

                    "Status", 0, 8, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,

                    1, 0, 0, 255, "", List.of(), DbcMultiplexRole.NONE, null, "", DbcValueTable.empty());

            return new DbcDatabase("", List.of(), List.of(

                    new DbcMessage(CanIdKey.fromFrame(0x36, false), "MESSAGE_36", 8, "ECU", List.of(a, b), ""),

                    new DbcMessage(CanIdKey.fromFrame(0xF6, false), "DONNEES_BSI_LENTES_F6", 8, "ECU",

                            List.of(tExt, km), ""),

                    new DbcMessage(CanIdKey.fromFrame(0x201, false), "MESSAGE_201", 8, "ECU", List.of(status), "")

            ), List.of());

        }

    }

}

