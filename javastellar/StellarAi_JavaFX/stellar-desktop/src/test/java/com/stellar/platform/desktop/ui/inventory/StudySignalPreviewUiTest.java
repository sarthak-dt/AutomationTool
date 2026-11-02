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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Study answers "what signals could this channel and DBC expose?". It is a read-only preview built
 * from stored DBC definitions: it must never run processing, never invent metadata the backend does
 * not hold, and never keep presenting itself as current after the configuration behind it changes.
 */
class StudySignalPreviewUiTest {

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
    void bulkStudyActionIsReachableAlongsideTheChannelWorkflow() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 2);
            workspace.refreshFromModel();
            workspace.layout();

            var bulk = workspace.selectAllMatchedButtonForTest();
            assertTrue(bulk.isVisible() && bulk.isManaged(),
                    "the existing bulk Study capability must remain reachable, not hidden");
            assertTrue(workspace.scopeRowForTest().getParent() == bulk.getParent(),
                    "bulk Study belongs on the visible table command row");
            assertFalse(bulk.isDisable(), "matched messages are available, so bulk Study is offered");

            dispose(stage);
        });
    }

    @Test
    @Timeout(60)
    void studyPreviewShowsOnlyMetadataTheBackendActuallyHolds() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 2);
            workspace.refreshFromModel();
            workspace.selectAllMatchedIntoStudy();

            List<CanStudySignalDefinitionView> rows =
                    workspace.detailsInspectorForTest().studyTableForTest().getItems();
            assertFalse(rows.isEmpty());

            CanStudySignalDefinitionView km = rows.stream()
                    .filter(r -> "KM_TOTAL".equals(r.signalName()))
                    .findFirst().orElseThrow();
            // Every field below is stored DBC definition data, not a decoded measurement.
            assertEquals("0xF6", km.canIdHex());
            assertEquals("km", km.unitDisplay());
            assertEquals("BSI_LENTES_F6", km.dbcMessageName());
            assertEquals("chassis.dbc", km.definitionDisplayName());
            assertEquals(2, km.channelKey().channelId());
            assertTrue(km.definitionSourceId().isPresent());
            assertTrue(km.signal().isPresent(), "the preview projects the stored signal definition");

            dispose(stage);
        });
    }

    @Test
    @Timeout(60)
    void studyDoesNotTriggerProcessingOrProduceSignalData() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 2);
            workspace.refreshFromModel();

            workspace.selectAllMatchedIntoStudy();

            assertTrue(fx.service.processingResultRegistry().all().isEmpty(),
                    "Study must never execute processing");
            assertTrue(fx.service.signalsModel().catalog().entries().isEmpty(),
                    "Study previews definitions, it does not produce processed signals");

            dispose(stage);
        });
    }

    @Test
    @Timeout(60)
    void reassigningTheDbcMarksThePreviewStaleAndOffersARestudy() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 2);
            workspace.refreshFromModel();
            workspace.selectAllMatchedIntoStudy();
            assertFalse(workspace.detailsInspectorForTest().studyStaleBarForTest().isVisible(),
                    "a freshly built preview is current");

            fx.reassignChannelToAlternateDbc();
            workspace.refreshFromModel();

            assertTrue(workspace.studySetForTest().staleCount() > 0,
                    "changing the DBC behind a Study identity must invalidate its preview");
            assertTrue(workspace.detailsInspectorForTest().studyStaleBarForTest().isVisible(),
                    "the engineer must be told the preview is no longer current");

            workspace.detailsInspectorForTest().studyStaleAcknowledgeButtonForTest().fire();

            assertEquals(0, workspace.studySetForTest().staleCount());
            assertFalse(workspace.detailsInspectorForTest().studyStaleBarForTest().isVisible());
            assertFalse(workspace.studySetForTest().isEmpty(),
                    "acknowledging a refreshed preview must not drop the Study identities");

            dispose(stage);
        });
    }

    @Test
    @Timeout(60)
    void aChannelWithNoMatchingDefinitionsIsAnEmptyResultNotAFailure() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace);
            workspace.refreshFromModel();
            // Channel 3 has no DBC mapping at all, so nothing is eligible for Study.
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 3);
            workspace.refreshFromModel();

            assertEquals(0, workspace.selectAllMatchedIntoStudy());
            assertTrue(workspace.studySetForTest().isEmpty());
            assertTrue(workspace.detailsInspectorForTest().studyStaleBarForTest() != null,
                    "the empty case renders normally instead of throwing");

            dispose(stage);
        });
    }

    private static Stage show(SourceInventoryWorkspace workspace) {
        StackPane host = new StackPane(workspace);
        Scene scene = new Scene(host, 1600, 900);
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        host.resize(1600, 900);
        host.layout();
        workspace.resize(1560, 820);
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
                    new WorkspaceFile(Path.of("study_preview.mf4").toAbsolutePath(),
                            FileKind.MEASUREMENT_MF4, 10));
            List<CanMessageInventory> messages = List.of(
                    new CanMessageInventory(new CanMessageKey(2, 0x36, false), 10, 8, 8, false),
                    new CanMessageInventory(new CanMessageKey(2, 0xF6, false), 20, 8, 8, false));
            CanChannelInventory ch2 = new CanChannelInventory(2, "Channel 2", 30, 2, 2, 0, 0, messages);
            CanChannelInventory ch3 = new CanChannelInventory(3, "Channel 3", 1, 1, 1, 0, 0,
                    List.of(new CanMessageInventory(new CanMessageKey(3, 0x10, false), 1, 8, 8, false)));
            service.sourceInventoryModel().put(file.id(), new CanSourceInventory(
                    file.path(), file.fileName(), MeasurementSourceFormat.MF4,
                    CanInventoryStatus.INVENTORY_READY, 2, 31, 3, true, List.of(ch2, ch3), List.of()));

            DefinitionSourceId chassis = DefinitionSourceId.generate();
            service.definitionMatchingCoordinator().registerDefinition(chassis, "chassis.dbc", chassisDbc());
            DefinitionSourceId powertrain = DefinitionSourceId.generate();
            service.definitionMatchingCoordinator()
                    .registerDefinition(powertrain, "powertrain.dbc", powertrainDbc());

            SourceChannelKey key = SourceChannelKey.of(file.projectSourceId(), 2);
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    key, ChannelMappingStatus.CONFIRMED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    null, "", chassis, "chassis.dbc", 2, 2, 1.0));

            Fixture fixture = new Fixture(service, file);
            fixture.alternateDefinitionId = powertrain;
            return fixture;
        }

        private DefinitionSourceId alternateDefinitionId;

        /** Uses the existing confirm-mapping semantics; no new decoding rules are introduced. */
        void reassignChannelToAlternateDbc() {
            SourceChannelKey key = SourceChannelKey.of(file.projectSourceId(), 2);
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    key, ChannelMappingStatus.CONFIRMED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    null, "", alternateDefinitionId, "powertrain.dbc", 2, 2, 1.0));
        }

        SourceInventoryWorkspace bindWorkspace() {
            SourceInventoryWorkspace workspace = new SourceInventoryWorkspace();
            workspace.bind(service, () -> { });
            return workspace;
        }

        private static DbcDatabase chassisDbc() {
            DbcSignal a = signal("Signal_A", 0, 8, "");
            DbcSignal tExt = signal("T_EXT", 47, 8, "");
            DbcSignal km = signal("KM_TOTAL", 23, 24, "km");
            return new DbcDatabase("", List.of(), List.of(
                    new DbcMessage(CanIdKey.fromFrame(0x36, false), "MESSAGE_36", 8, "ECU", List.of(a), ""),
                    new DbcMessage(CanIdKey.fromFrame(0xF6, false), "BSI_LENTES_F6", 8, "ECU",
                            List.of(tExt, km), "")
            ), List.of());
        }

        private static DbcDatabase powertrainDbc() {
            DbcSignal rpm = signal("EngineSpeed", 0, 16, "rpm");
            DbcSignal torque = signal("EngineTorque", 16, 16, "Nm");
            return new DbcDatabase("", List.of(), List.of(
                    new DbcMessage(CanIdKey.fromFrame(0x36, false), "ENGINE_36", 8, "ECU", List.of(rpm), ""),
                    new DbcMessage(CanIdKey.fromFrame(0xF6, false), "ENGINE_F6", 8, "ECU",
                            List.of(rpm, torque), "")
            ), List.of());
        }

        private static DbcSignal signal(String name, int startBit, int length, String unit) {
            return new DbcSignal(name, startBit, length, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,
                    1, 0, 0, 255, unit, List.of(), DbcMultiplexRole.NONE, null, "", DbcValueTable.empty());
        }
    }
}
