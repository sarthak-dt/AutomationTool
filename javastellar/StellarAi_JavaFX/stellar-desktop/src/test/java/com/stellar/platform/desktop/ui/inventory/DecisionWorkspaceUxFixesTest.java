package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcByteOrder;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.model.DbcValueTable;
import com.stellar.platform.dbc.model.DbcValueType;
import com.stellar.platform.desktop.presentation.CanMessageInventoryRow;
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
import javafx.scene.control.CheckBox;
import javafx.scene.control.ScrollPane;
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

class DecisionWorkspaceUxFixesTest {

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
    @Timeout(45)
    void channelRowShowsDbcMatchedCountAndDashWhenMissing() throws Exception {
        runOnFx(() -> {
            NavFixture fx = NavFixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.layout();

            assertEquals("1", workspace.navSignalsTextForTest(fx.file.projectSourceId(), 1));
            assertEquals("\u2014", workspace.navSignalsTextForTest(fx.file.projectSourceId(), 2));
            assertEquals("\u2014", workspace.navAllChannelsSignalsTextForTest(fx.file.projectSourceId()));
            assertEquals("3", workspace.navIdsTextForTest(fx.file.projectSourceId(), 1));
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void leftNavigatorSupportsHorizontalOverflowWithoutScrollingCenterTable() throws Exception {
        runOnFx(() -> {
            NavFixture fx = NavFixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1366, 768);
            workspace.refreshFromModel();
            workspace.layout();
            workspace.leftNavPaneForTest().layout();
            workspace.navScrollForTest().layout();

            assertTrue(workspace.leftNavPaneForTest().getWidth() <= 280);
            assertEquals(ScrollPane.ScrollBarPolicy.NEVER, workspace.navScrollForTest().getHbarPolicy());
            assertEquals(0, workspace.navigatorHorizontalScrollBarCountForTest());
            assertTrue(workspace.navScrollForTest().getContent().minWidth(-1)
                    <= workspace.leftNavPaneForTest().getWidth() + 8);
            javafx.scene.Node n = workspace.tableForTest();
            while (n != null) {
                assertFalse(n == workspace.navScrollForTest());
                n = n.getParent();
            }
            dispose(stage);
        });
    }

    @Test
    @Timeout(60)
    void headerCheckboxSelectsClearsAndRespectsFilteredEligibleView() throws Exception {
        runOnFx(() -> {
            StudyFixture fx = StudyFixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.fileA.projectSourceId(), 1);
            workspace.refreshFromModel();

            workspace.chipMatchedForTest().fire();
            assertEquals(MatchFilter.MATCHED, workspace.modelForTest().matchFilter());
            assertEquals(31, workspace.tableForTest().getItems().size());
            assertEquals(31, eligibleCount(workspace));

            CheckBox header = workspace.studyHeaderCheckForTest();
            assertFalse(header.isSelected());
            assertFalse(header.isIndeterminate());

            header.fire();
            assertEquals(31, workspace.studySetForTest().size());
            assertTrue(header.isSelected());
            assertFalse(header.isIndeterminate());
            assertTrue(workspace.openStudySetButtonForTest().getText().contains("31"));
            assertFalse(workspace.studySetForTest().contains(
                    StudyMessageKey.of(SourceChannelKey.of(fx.fileA.projectSourceId(), 2), 0x50, false)));
            assertFalse(workspace.studySetForTest().contains(
                    StudyMessageKey.of(SourceChannelKey.of(fx.fileB.projectSourceId(), 1), 0x500, false)));

            header.fire();
            assertEquals(0, workspace.studySetForTest().size());
            assertFalse(header.isSelected());
            assertFalse(header.isIndeterminate());

            workspace.studySetForTest().add(workspace.tableForTest().getItems().getFirst());
            assertEquals(1, workspace.studySetForTest().size());
            assertTrue(header.isIndeterminate());
            assertFalse(header.isSelected());

            workspace.searchFieldForTest().setText("0x30");
            assertEquals(6, workspace.tableForTest().getItems().size());
            assertEquals(6, eligibleCount(workspace));

            header.fire();
            assertEquals(6, countSelectedInView(workspace));
            assertEquals(6, workspace.studySetForTest().size());
            for (CanMessageInventoryRow row : workspace.tableForTest().getItems()) {
                assertTrue(workspace.studySetForTest().contains(row));
            }
            assertTrue(header.isSelected());
            assertFalse(header.isIndeterminate());

            workspace.searchFieldForTest().clear();
            workspace.chipAllForTest().fire();
            workspace.modelForTest().selectChannel(fx.fileA.projectSourceId(), 2);
            workspace.refreshFromModel();
            assertEquals(1, workspace.tableForTest().getItems().size());
            assertFalse(workspace.studySetForTest().contains(
                    StudyMessageKey.of(SourceChannelKey.of(fx.fileA.projectSourceId(), 2), 0x50, false)));
            workspace.studySetForTest().add(workspace.tableForTest().getItems().getFirst());
            assertTrue(workspace.studySetForTest().contains(
                    StudyMessageKey.of(SourceChannelKey.of(fx.fileA.projectSourceId(), 2), 0x50, false)));

            workspace.modelForTest().selectChannel(fx.fileA.projectSourceId(), 1);
            workspace.refreshFromModel();
            workspace.chipMatchedForTest().fire();
            header.fire();
            assertEquals(31, countSelectedInView(workspace));
            assertTrue(workspace.studySetForTest().contains(
                    StudyMessageKey.of(SourceChannelKey.of(fx.fileA.projectSourceId(), 2), 0x50, false)));
            assertFalse(workspace.studySetForTest().contains(
                    StudyMessageKey.of(SourceChannelKey.of(fx.fileB.projectSourceId(), 1), 0x500, false)));

            header.fire();
            assertEquals(0, countSelectedInView(workspace));
            assertTrue(workspace.studySetForTest().contains(
                    StudyMessageKey.of(SourceChannelKey.of(fx.fileA.projectSourceId(), 2), 0x50, false)));
            assertEquals(1, workspace.studySetForTest().size());

            workspace.chipAllForTest().fire();
            boolean unmatchedInView = workspace.tableForTest().getItems().stream()
                    .anyMatch(r -> !InventoryStudySet.isEligible(r));
            assertTrue(unmatchedInView);
            header.fire();
            for (CanMessageInventoryRow row : workspace.tableForTest().getItems()) {
                if (!InventoryStudySet.isEligible(row)) {
                    assertFalse(workspace.studySetForTest().contains(row));
                }
            }
            dispose(stage);
        });
    }

    private static int eligibleCount(SourceInventoryWorkspace workspace) {
        int n = 0;
        for (CanMessageInventoryRow row : workspace.tableForTest().getItems()) {
            if (InventoryStudySet.isEligible(row)) {
                n++;
            }
        }
        return n;
    }

    private static int countSelectedInView(SourceInventoryWorkspace workspace) {
        int n = 0;
        for (CanMessageInventoryRow row : workspace.tableForTest().getItems()) {
            if (InventoryStudySet.isEligible(row) && workspace.studySetForTest().contains(row)) {
                n++;
            }
        }
        return n;
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

    private static DbcSignal sig(String name, int start) {
        return new DbcSignal(
                name, start, 8, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,
                1, 0, 0, 255, "", List.of(), DbcMultiplexRole.NONE, null, "", DbcValueTable.empty());
    }

    private static final class NavFixture {
        final DesktopAppService service;
        final WorkspaceFile file;

        private NavFixture(DesktopAppService service, WorkspaceFile file) {
            this.service = service;
            this.file = file;
        }

        static NavFixture create() {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile file = service.workspace().addFile(
                    new WorkspaceFile(Path.of("nav_signals.mf4").toAbsolutePath(),
                            FileKind.MEASUREMENT_MF4, 10));
            List<CanMessageInventory> ch1Messages = List.of(
                    new CanMessageInventory(new CanMessageKey(1, 0x316, false), 10, 8, 8, false),
                    new CanMessageInventory(new CanMessageKey(1, 0x329, false), 10, 8, 8, false),
                    new CanMessageInventory(new CanMessageKey(1, 0x7E8, false), 10, 8, 8, false)
            );
            List<CanMessageInventory> ch2Messages = List.of(
                    new CanMessageInventory(new CanMessageKey(2, 0x100, false), 10, 8, 8, false)
            );
            CanChannelInventory ch1 = new CanChannelInventory(1, "Channel 1", 30, 3, 3, 0, 0, ch1Messages);
            CanChannelInventory ch2 = new CanChannelInventory(2, "Channel 2", 10, 1, 1, 0, 0, ch2Messages);
            CanSourceInventory inv = new CanSourceInventory(
                    file.path(), file.fileName(), MeasurementSourceFormat.MF4,
                    CanInventoryStatus.INVENTORY_READY, 2, 40, 4, true, List.of(ch1, ch2), List.of());
            service.sourceInventoryModel().put(file.id(), inv);

            DefinitionSourceId defId = DefinitionSourceId.generate();
            List<DbcSignal> signals = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                signals.add(sig("SIG_" + i, Math.min(i * 8, 56)));
            }
            DbcMessage msg = new DbcMessage(CanIdKey.fromFrame(0x7E8, false), "OBD2", 8, "ECU", signals, "");
            service.definitionMatchingCoordinator().registerDefinition(defId, "nav.dbc",
                    new DbcDatabase("", List.of(), List.of(msg), List.of()));
            SourceChannelKey key1 = SourceChannelKey.of(file.projectSourceId(), 1);
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    key1, ChannelMappingStatus.CONFIRMED, ChannelRecommendationKind.NOT_APPLICABLE,
                    null, "", defId, "nav.dbc", 3, 1, 1.0 / 3.0));
            return new NavFixture(service, file);
        }

        SourceInventoryWorkspace bindWorkspace() {
            SourceInventoryWorkspace workspace = new SourceInventoryWorkspace();
            workspace.bind(service, () -> { });
            return workspace;
        }
    }

    private static final class StudyFixture {
        final DesktopAppService service;
        final WorkspaceFile fileA;
        final WorkspaceFile fileB;

        private StudyFixture(DesktopAppService service, WorkspaceFile fileA, WorkspaceFile fileB) {
            this.service = service;
            this.fileA = fileA;
            this.fileB = fileB;
        }

        static StudyFixture create() {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile fileA = service.workspace().addFile(
                    new WorkspaceFile(Path.of("study_a.mf4").toAbsolutePath(),
                            FileKind.MEASUREMENT_MF4, 10));
            WorkspaceFile fileB = service.workspace().addFile(
                    new WorkspaceFile(Path.of("study_b.mf4").toAbsolutePath(),
                            FileKind.MEASUREMENT_MF4, 10));

            List<Long> matchedIds = new ArrayList<>();
            for (long id = 0x301; id <= 0x306; id++) {
                matchedIds.add(id);
            }
            for (long id = 0x400; id <= 0x418; id++) {
                matchedIds.add(id);
            }
            assertEquals(31, matchedIds.size());

            List<CanMessageInventory> ch1 = new ArrayList<>();
            List<DbcMessage> dbcMessages = new ArrayList<>();
            for (long id : matchedIds) {
                ch1.add(new CanMessageInventory(new CanMessageKey(1, id, false), 4, 8, 8, false));
                dbcMessages.add(new DbcMessage(
                        CanIdKey.fromFrame(id, false), "M" + Long.toHexString(id), 8, "ECU",
                        List.of(sig("S" + Long.toHexString(id), 0)), ""));
            }
            ch1.add(new CanMessageInventory(new CanMessageKey(1, 0x7FF, false), 2, 8, 8, false));
            CanChannelInventory channel1 = new CanChannelInventory(
                    1, "Channel 1", 31 * 4 + 2, 32, 32, 0, 0, ch1);
            CanChannelInventory channel2 = new CanChannelInventory(
                    2, "Channel 2", 8, 1, 1, 0, 0,
                    List.of(new CanMessageInventory(new CanMessageKey(2, 0x50, false), 8, 8, 8, false)));
            CanSourceInventory invA = new CanSourceInventory(
                    fileA.path(), fileA.fileName(), MeasurementSourceFormat.MF4,
                    CanInventoryStatus.INVENTORY_READY, 2, 31 * 4 + 10, 33, true,
                    List.of(channel1, channel2), List.of());
            service.sourceInventoryModel().put(fileA.id(), invA);

            DefinitionSourceId defA = DefinitionSourceId.generate();
            dbcMessages.add(new DbcMessage(
                    CanIdKey.fromFrame(0x50, false), "CH2", 8, "ECU", List.of(sig("CH2S", 0)), ""));
            service.definitionMatchingCoordinator().registerDefinition(defA, "study_a.dbc",
                    new DbcDatabase("", List.of(), dbcMessages, List.of()));

            CanChannelInventory bCh = new CanChannelInventory(
                    1, "Channel 1", 5, 1, 1, 0, 0,
                    List.of(new CanMessageInventory(new CanMessageKey(1, 0x500, false), 5, 8, 8, false)));
            CanSourceInventory invB = new CanSourceInventory(
                    fileB.path(), fileB.fileName(), MeasurementSourceFormat.MF4,
                    CanInventoryStatus.INVENTORY_READY, 1, 5, 1, true, List.of(bCh), List.of());
            service.sourceInventoryModel().put(fileB.id(), invB);
            DefinitionSourceId defB = DefinitionSourceId.generate();
            service.definitionMatchingCoordinator().registerDefinition(defB, "study_b.dbc",
                    new DbcDatabase("", List.of(), List.of(new DbcMessage(
                            CanIdKey.fromFrame(0x500, false), "BMSG", 8, "ECU",
                            List.of(sig("BS", 0)), "")), List.of()));
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    SourceChannelKey.of(fileA.projectSourceId(), 1),
                    ChannelMappingStatus.CONFIRMED, ChannelRecommendationKind.NOT_APPLICABLE,
                    null, "", defA, "study_a.dbc", 32, 31, 31.0 / 32.0));
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    SourceChannelKey.of(fileA.projectSourceId(), 2),
                    ChannelMappingStatus.CONFIRMED, ChannelRecommendationKind.NOT_APPLICABLE,
                    null, "", defA, "study_a.dbc", 1, 1, 1.0));
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    SourceChannelKey.of(fileB.projectSourceId(), 1),
                    ChannelMappingStatus.CONFIRMED, ChannelRecommendationKind.NOT_APPLICABLE,
                    null, "", defB, "study_b.dbc", 1, 1, 1.0));
            return new StudyFixture(service, fileA, fileB);
        }

        SourceInventoryWorkspace bindWorkspace() {
            SourceInventoryWorkspace workspace = new SourceInventoryWorkspace();
            workspace.bind(service, () -> { });
            return workspace;
        }
    }
}
