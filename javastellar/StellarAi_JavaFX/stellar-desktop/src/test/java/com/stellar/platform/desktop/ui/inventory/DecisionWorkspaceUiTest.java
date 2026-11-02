package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcByteOrder;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.model.DbcValueTable;
import com.stellar.platform.dbc.model.DbcValueType;
import com.stellar.platform.desktop.presentation.DecisionWorkspaceMode;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionCandidateKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionWorkspaceUiTest {

    @BeforeAll
    static void initJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        // JUnit closes the Stage at the end of every case. Keep the shared JavaFX toolkit alive
        // for the remaining cases in this class; otherwise closing the first/last Stage can exit
        // the FX runtime and every later Platform.runLater call waits until its timeout.
        CountDownLatch configured = new CountDownLatch(1);
        Platform.runLater(() -> {
            Platform.setImplicitExit(false);
            configured.countDown();
        });
        assertTrue(configured.await(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(45)
    void sourceSelectionActivatesMappingChannelWithoutExtraClick() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectSource(fx.file.projectSourceId());
            workspace.refreshFromModel();
            workspace.layout();

            assertTrue(workspace.isSourceContextForTest());
            assertTrue(workspace.activeMappingChannelForTest().isPresent());
            assertEquals(1, workspace.activeMappingChannelForTest().getAsInt());
            assertTrue(workspace.candidateStripForTest().hasCandidatesForTest());
            assertFalse(workspace.candidateStripForTest().emptyTextForTest()
                    .contains("Select a channel"));
            assertTrue(workspace.candidateStripForTest().comboForTest().isVisible());
            assertFalse(workspace.actionBarForTest().chooseDbcForAllButtonForTest().isManaged());
            assertTrue(workspace.channelSelectorForTest().chipCountForTest() >= 2);
            assertEquals(1, workspace.channelSelectorForTest().activeChannelForTest());
            assertTrue(workspace.signalExplorerForTest().nativeCountForTest() > 0);
            assertTrue(workspace.signalExplorerForTest().titleForTest().contains(fx.file.fileName())
                    || workspace.signalExplorerForTest().titleForTest().contains("Source")
                    || workspace.signalExplorerForTest().visibleCountForTest() > 0);
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void channelSwitchUpdatesCandidatesTableAndSignals() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();
            int firstCandidates = workspace.candidateStripForTest().candidateCountForTest();
            int firstSignals = workspace.signalExplorerForTest().visibleCountForTest();
            assertTrue(firstCandidates > 0);
            assertTrue(firstSignals > 0);

            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 2);
            workspace.refreshFromModel();
            assertEquals(2, workspace.activeMappingChannelForTest().getAsInt());
            assertTrue(workspace.actionBarForTest().selectionTextForTest().contains("Ch2"));
            assertEquals(1, workspace.candidateStripForTest().candidateCountForTest());
            assertTrue(workspace.signalExplorerForTest().titleForTest().contains("Ch2"));
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void catalogNativeSignalsRemainVisibleInSourceContext() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectAllChannels(fx.file.projectSourceId());
            workspace.refreshFromModel();

            assertTrue(workspace.signalExplorerForTest().nativeCountForTest() >= 1);
            assertTrue(workspace.signalExplorerForTest().visibleNamesForTest().contains("VehicleSpeed"));
            assertTrue(workspace.signalExplorerForTest().nativeScopeVisibleForTest());
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void canTableIsCenterGrowNodeAndKeepsHeightWithSignalExplorer() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();
            workspace.layout();
            workspace.tableForTest().layout();

            assertTrue(workspace.isTableBorderPaneCenterForTest());
            assertTrue(workspace.usesUnconstrainedTableResizeForTest());
            assertTrue(workspace.tableHeightForTest() > 280,
                    "table-first layout should allocate substantial height, was "
                            + workspace.tableHeightForTest());
            double before = workspace.tableHeightForTest();
            assertTrue(workspace.isInspectorCollapsedForTest());
            assertTrue(workspace.inspectorConsumesNoWidthForTest());
            workspace.headerSignalsButtonForTest().fire();
            workspace.layout();
            assertFalse(workspace.isInspectorCollapsedForTest());
            assertEquals(DecisionInspectorDrawer.Tab.SIGNALS, workspace.inspectorDrawerForTest().tab());
            assertEquals(before, workspace.tableHeightForTest(), 2.0);
            ComboBox<String> match = workspace.matchComboForTest();
            assertTrue(match.getButtonCell().getText().contains("Match"));
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void dbcDropdownListsExistingCandidatesAndManualChoiceStaysUnconfirmed() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();

            SourceChannelKey key = SourceChannelKey.of(fx.file.projectSourceId(), 1);
            assertEquals(2, workspace.candidateStripForTest().candidateCountForTest());
            assertEquals(2, workspace.candidateStripForTest().optionNamesForTest().size());
            assertEquals(
                    fx.service.candidatesFor(key).stream()
                            .map(c -> c.definitionDisplayName())
                            .toList()
                            .containsAll(workspace.candidateStripForTest().optionNamesForTest()),
                    true);
            assertEquals("11_bit_OBD2 2.dbc", workspace.candidateStripForTest().selectedDisplayNameForTest());
            assertEquals(ChannelMappingStatus.RECOMMENDED,
                    fx.service.channelMapping(key).orElseThrow().status());

            workspace.candidateStripForTest().selectIndexForTest(1);
            workspace.refreshEnrichmentOnly();
            assertEquals("alt.dbc", workspace.candidateStripForTest().selectedDisplayNameForTest());
            assertEquals(ChannelMappingStatus.RECOMMENDED,
                    fx.service.channelMapping(key).orElseThrow().status());
            assertEquals("Confirm Selected DBC", workspace.actionBarForTest().confirmLabelForTest());
            assertFalse(workspace.actionBarForTest().confirmButtonForTest().isDisable());
            workspace.actionBarForTest().confirmButtonForTest().fire();
            workspace.refreshEnrichmentOnly();
            assertEquals(ChannelMappingStatus.MANUAL,
                    fx.service.channelMapping(key).orElseThrow().status());
            assertTrue(workspace.actionBarForTest().acceptedVisibleForTest());
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void dbcDecisionKeepsRecommendationUntilExplicitConfirm() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();

            SourceChannelKey key = SourceChannelKey.of(fx.file.projectSourceId(), 1);
            assertEquals(2, workspace.candidateStripForTest().candidateCountForTest());
            assertEquals(ChannelMappingStatus.RECOMMENDED,
                    fx.service.channelMapping(key).orElseThrow().status());
            assertEquals(1.0, fx.service.channelMapping(key).orElseThrow().coverageRatio(), 0.0001);

            workspace.actionBarForTest().confirmButtonForTest().fire();
            workspace.refreshEnrichmentOnly();
            assertEquals(ChannelMappingStatus.CONFIRMED,
                    fx.service.channelMapping(key).orElseThrow().status());

            SourceChannelKey other = SourceChannelKey.of(fx.file.projectSourceId(), 2);
            assertEquals(ChannelMappingStatus.RECOMMENDED,
                    fx.service.channelMapping(other).orElseThrow().status());
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void proceedUsesExistingReadinessAndDoesNotSilentConfirm() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            AtomicBoolean proceeded = new AtomicBoolean();
            workspace.setOnProceedToProcessing(() -> proceeded.set(true));
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();

            assertEquals("Review & Process", workspace.actionBarForTest().proceedButtonForTest().getText());
            assertEquals(fx.service.canProceedFromDecisionWorkspace(),
                    !workspace.actionBarForTest().proceedButtonForTest().isDisable());
            assertTrue(fx.service.canProceedFromDecisionWorkspace());
            assertEquals(ChannelMappingStatus.RECOMMENDED,
                    fx.service.channelMapping(SourceChannelKey.of(fx.file.projectSourceId(), 1))
                            .orElseThrow().status());
            // Fires the Review & Process route; it must not silently confirm mappings on the way.
            workspace.actionBarForTest().fireProceedForTest();
            assertTrue(proceeded.get());
            assertEquals(ChannelMappingStatus.RECOMMENDED,
                    fx.service.channelMapping(SourceChannelKey.of(fx.file.projectSourceId(), 1))
                            .orElseThrow().status());
            assertEquals(ChannelMappingStatus.RECOMMENDED,
                    fx.service.channelMapping(SourceChannelKey.of(fx.file.projectSourceId(), 2))
                            .orElseThrow().status());
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void channelSelectionShowsCandidatesSignalsAndProceedAction() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            AtomicBoolean proceeded = new AtomicBoolean();
            workspace.setOnProceedToProcessing(() -> proceeded.set(true));
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();
            workspace.layout();

            assertTrue(workspace.getStyleClass().contains("decision-workspace-root"));
            assertTrue(workspace.candidateStripForTest().hasCandidatesForTest());
            assertEquals(2, workspace.candidateStripForTest().candidateCountForTest());
            assertTrue(workspace.signalExplorerForTest().visibleCountForTest() > 0);
            assertTrue(workspace.actionBarForTest().selectionTextForTest().contains("Ch1"));
            assertFalse(workspace.actionBarForTest().confirmButtonForTest().isDisable());
            assertEquals("Confirm Recommended", workspace.actionBarForTest().confirmLabelForTest());
            assertEquals("Review & Process", workspace.actionBarForTest().proceedButtonForTest().getText());

            workspace.actionBarForTest().fireProceedForTest();
            assertTrue(proceeded.get());
            assertEquals(ChannelMappingStatus.RECOMMENDED,
                    fx.service.channelMapping(SourceChannelKey.of(fx.file.projectSourceId(), 1))
                            .orElseThrow().status());

            workspace.actionBarForTest().confirmButtonForTest().fire();
            workspace.refreshEnrichmentOnly();
            assertEquals(ChannelMappingStatus.CONFIRMED,
                    fx.service.channelMapping(SourceChannelKey.of(fx.file.projectSourceId(), 1))
                            .orElseThrow().status());
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void recommendedFallbackEnablesProceedWithoutConfirming() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();

            SourceChannelKey key = SourceChannelKey.of(fx.file.projectSourceId(), 1);
            assertEquals(1.0, fx.service.channelMapping(key).orElseThrow().coverageRatio(), 0.0001);
            assertTrue(fx.service.canProceedFromDecisionWorkspace());
            assertFalse(workspace.actionBarForTest().proceedButtonForTest().isDisable());
            workspace.actionBarForTest().fireProceedForTest();
            assertEquals(ChannelMappingStatus.RECOMMENDED, fx.service.channelMapping(key).orElseThrow().status());
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void ambiguousAndNoMatchBlockProceed() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceChannelKey key2 = SourceChannelKey.of(fx.file.projectSourceId(), 2);
            fx.service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    key2, ChannelMappingStatus.AMBIGUOUS, ChannelRecommendationKind.AMBIGUOUS,
                    null, "", null, "", 1, 0, 0.0));
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            assertFalse(fx.service.canProceedFromDecisionWorkspace());
            assertTrue(fx.service.decisionProcessingHint().contains("still needs a DBC choice"),
                    fx.service.decisionProcessingHint());
            fx.service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    key2, ChannelMappingStatus.NO_MATCH, ChannelRecommendationKind.NO_MATCH,
                    null, "", null, "", 1, 0, 0.0));
            workspace.refreshFromModel();
            assertTrue(fx.service.canProceedFromDecisionWorkspace(),
                    "a No Match channel is native-only and must not block a channel that already has a DBC");
            assertEquals(1, fx.service.decisionProcessingReadiness().nativeOnlyChannels());
            assertTrue(fx.service.channelMapping(key2).orElseThrow().mappedDefinitionId().isEmpty());
            fx.service.setDecisionWorkspaceMode(DecisionWorkspaceMode.QUICK_DECODE);
            assertTrue(fx.service.canProceedFromDecisionWorkspace(),
                    "Auto Decode NO_MATCH is a warning, not a blocker");
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void decisionWorkspaceUsesIndustrialSemanticClasses() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();
            assertTrue(workspace.getStyleClass().contains("decision-workspace-root"));
            assertTrue(workspace.actionBarForTest().proceedButtonForTest().getStyleClass()
                    .contains("primary-action"));
            assertTrue(workspace.actionBarForTest().compareButtonForTest().getStyleClass()
                    .contains("secondary-action"));
            assertTrue(workspace.actionBarForTest().confirmButtonForTest().getStyleClass()
                    .contains("secondary-action"));
            assertTrue(workspace.candidateStripForTest().comboForTest().getStyleClass()
                    .contains("dbc-definition"));
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void mappingSummaryRowIsVisibleAndDoesNotOverlap() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();
            workspace.layout();

            DbcCandidateStrip strip = workspace.candidateStripForTest();
            assertEquals("RECOMMENDED", strip.mappingStateForTest());
            assertTrue(strip.mappingStateLabelForTest().isVisible());
            assertTrue(strip.coverageSummaryForTest().contains("3")
                    && strip.coverageSummaryForTest().contains("matched"));
            assertTrue(strip.coverageSummaryForTest().contains("100")
                    || strip.coverageSummaryForTest().contains("1"));
            assertTrue(strip.coverageSummaryLabelForTest().isVisible());
            assertTrue(strip.qualityBadgeForTest().isVisible());
            assertFalse(strip.qualityForTest().isBlank());
            assertTrue(workspace.decisionHeaderRowsDoNotOverlapForTest());
            assertTrue(strip.comboForTest().isVisible());
            assertFalse(strip.comboForTest().isDisable());

            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 2);
            workspace.refreshFromModel();
            workspace.layout();
            assertEquals(2, workspace.activeMappingChannelForTest().getAsInt());
            assertTrue(workspace.actionBarForTest().selectionTextForTest().contains("Ch2"));
            assertTrue(strip.comboForTest().isVisible());
            assertTrue(workspace.decisionHeaderRowsDoNotOverlapForTest());

            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();
            workspace.candidateStripForTest().selectIndexForTest(1);
            workspace.refreshEnrichmentOnly();
            workspace.layout();
            assertEquals("SELECTED", workspace.candidateStripForTest().mappingStateForTest());
            assertTrue(workspace.candidateStripForTest().mappingStateLabelForTest().isVisible());
            assertTrue(workspace.candidateStripForTest().coverageSummaryLabelForTest().isVisible());
            assertTrue(workspace.decisionHeaderRowsDoNotOverlapForTest());
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void inspectorIsClosedByDefaultAndOpensFromHeaderActions() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();
            workspace.layout();

            assertTrue(workspace.isInspectorCollapsedForTest());
            assertTrue(workspace.inspectorConsumesNoWidthForTest());
            assertEquals(0, workspace.inspectorDrawerWidthForTest(), 0.5);
            assertEquals(2, workspace.splitForTest().getItems().size());
            double tableHeight = workspace.tableHeightForTest();
            double tableWidth = workspace.tableForTest().getWidth();

            workspace.headerSignalsButtonForTest().fire();
            workspace.layout();
            assertFalse(workspace.isInspectorCollapsedForTest());
            assertEquals(DecisionInspectorDrawer.Tab.SIGNALS, workspace.inspectorDrawerForTest().tab());
            assertTrue(workspace.signalExplorerForTest().isVisible());
            assertTrue(workspace.signalExplorerForTest().visibleCountForTest() > 0);
            assertEquals(tableHeight, workspace.tableHeightForTest(), 2.0);
            assertTrue(workspace.tableForTest().getWidth() < tableWidth - 40);

            workspace.headerStudyButtonForTest().fire();
            workspace.layout();
            assertEquals(DecisionInspectorDrawer.Tab.STUDY, workspace.inspectorDrawerForTest().tab());
            assertTrue(workspace.detailsInspectorForTest().isStudyMode());
            assertEquals(tableHeight, workspace.tableHeightForTest(), 2.0);

            workspace.inspectorDrawerForTest().closeButtonForTest().fire();
            workspace.layout();
            assertTrue(workspace.isInspectorCollapsedForTest());
            assertTrue(workspace.inspectorConsumesNoWidthForTest());
            assertEquals(2, workspace.splitForTest().getItems().size());
            assertEquals(tableHeight, workspace.tableHeightForTest(), 2.0);
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void localActionBarKeepsFullLabelsAndEffectiveDbcReadiness() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.file.projectSourceId(), 1);
            workspace.refreshFromModel();
            workspace.layout();

            DecisionActionBar bar = workspace.actionBarForTest();
            assertEquals("Confirm Recommended", bar.confirmLabelForTest());
            assertEquals("Compare", bar.compareButtonForTest().getText());
            assertEquals("Mark for Review", bar.markNeedsAttentionForTest().getText());
            assertEquals("Review & Process", bar.proceedButtonForTest().getText());
            assertFalse(bar.confirmButtonForTest().getText().contains("\u2026"));
            assertFalse(bar.proceedButtonForTest().getText().contains("\u2026"));
            assertEquals(fx.service.canProceedFromDecisionWorkspace(),
                    !bar.proceedButtonForTest().isDisable());
            dispose(stage);
        });
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
        assertTrue(done.await(40, TimeUnit.SECONDS), "JavaFX task timed out");
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
            List<CanMessageInventory> ch1Messages = List.of(
                    new CanMessageInventory(new CanMessageKey(1, 0x316, false), 180_091, 8, 8, false),
                    new CanMessageInventory(new CanMessageKey(1, 0x329, false), 180_090, 8, 8, false),
                    new CanMessageInventory(new CanMessageKey(1, 0x7E8, false), 12_928, 8, 8, false)
            );
            List<CanMessageInventory> ch2Messages = List.of(
                    new CanMessageInventory(new CanMessageKey(2, 0x100, false), 10_000, 8, 8, false)
            );
            CanChannelInventory ch1 = new CanChannelInventory(
                    1, "Channel 1", 373_109, 3, 3, 0, 0, ch1Messages);
            CanChannelInventory ch2 = new CanChannelInventory(
                    2, "Channel 2", 10_000, 1, 1, 0, 0, ch2Messages);
            CanSourceInventory inv = new CanSourceInventory(
                    file.path(), file.fileName(), MeasurementSourceFormat.MF4,
                    CanInventoryStatus.INVENTORY_READY, 2, 383_109, 4, true, List.of(ch1, ch2), List.of());
            service.sourceInventoryModel().put(file.id(), inv);

            DefinitionSourceId defId = DefinitionSourceId.generate();
            DefinitionSourceId altId = DefinitionSourceId.generate();
            DbcDatabase dbc = lavasaDbc();
            service.definitionMatchingCoordinator().registerDefinition(defId, "11_bit_OBD2 2.dbc", dbc);
            service.definitionMatchingCoordinator().registerDefinition(altId, "alt.dbc", dbc);
            SourceChannelKey key1 = SourceChannelKey.of(file.projectSourceId(), 1);
            SourceChannelKey key2 = SourceChannelKey.of(file.projectSourceId(), 2);
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    key1, ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    defId, "11_bit_OBD2 2.dbc", null, "", 3, 3, 1.0));
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    key2, ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    altId, "alt.dbc", null, "", 1, 0, 0.0));
            service.definitionMatchingModel().replaceCandidates(List.of(
                    new SourceChannelDefinitionCandidate(
                            key1, defId, "11_bit_OBD2 2.dbc", 1,
                            DefinitionCandidateKind.HAS_OVERLAP, 3, 3, 0, 1.0),
                    new SourceChannelDefinitionCandidate(
                            key1, altId, "alt.dbc", 2,
                            DefinitionCandidateKind.HAS_OVERLAP, 3, 1, 2, 1.0 / 3.0),
                    new SourceChannelDefinitionCandidate(
                            key2, altId, "alt.dbc", 1,
                            DefinitionCandidateKind.HAS_OVERLAP, 1, 0, 1, 0.0)));

            InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(file.fileName(), "11_bit_OBD2 2.dbc", 64);
            store.accept(List.of(new CanonicalSignal(
                    "VehicleSpeed",
                    InMemorySignalCatalogStore.MF4_MEASUREMENT_MESSAGE,
                    1L, 0L, 0, 12.0, null, "km/h", null, new SourceReference(1, 0)
            )));
            service.signalsModel().replaceCatalog(store);
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
            for (int i = 0; i < 8; i++) {
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
