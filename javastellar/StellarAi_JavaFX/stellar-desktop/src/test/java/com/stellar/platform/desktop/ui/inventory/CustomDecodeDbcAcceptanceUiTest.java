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
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.ui.MainShellController;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Custom Decode with the release dataset shape: three DBCs that each define only the OBD2
 * response ID, so Ch1 (4 IDs) is an exact 1-of-4 tie and Ch2 matches nothing.
 */
class CustomDecodeDbcAcceptanceUiTest {

    private static final String LONG_SOURCE_NAME =
            "00000001-64F0A3B2_2026-07-13_Lavasa_Hill_Climb_Session_Long_Recording_Name.mf4";

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
        assertTrue(configured.await(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(45)
    void tiedDbcIsNotRecommendedNotAutoAcceptedAndExplainsWhy() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceChannelMappingEntry ch1 = fx.service.channelMapping(fx.key1).orElseThrow();
            assertEquals(ChannelMappingStatus.AMBIGUOUS, ch1.status());
            assertEquals(ChannelRecommendationKind.AMBIGUOUS, ch1.recommendationKind());

            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            fx.selectChannel(workspace, 1);
            DbcCandidateStrip strip = workspace.candidateStripForTest();
            DecisionActionBar bar = workspace.actionBarForTest();
            assertNavigatorChannelLabel(workspace, "Ch1");
            assertNavigatorChannelLabel(workspace, "Ch2");

            assertNull(strip.comboForTest().getValue(), "a tie-break winner must not look chosen");
            assertFalse(strip.optionStatusLabelsForTest().stream().anyMatch(s -> s.contains("RECOMMENDED")));
            assertEquals(3, strip.optionStatusLabelsForTest().stream().filter("TIED MATCH"::equals).count());
            assertFalse(strip.useRecommendedVisibleForTest());
            assertEquals("NEEDS ATTENTION", strip.mappingStateForTest());
            String hint = strip.actionHintForTest();
            assertTrue(hint.contains("3 DBCs match this channel equally"), hint);
            assertTrue(hint.contains("1 of 4 CAN IDs (25%)"), hint);
            assertTrue(hint.contains("Confirm Selected DBC"), hint);
            assertFalse(bar.confirmButtonForTest().isVisible());

            SourceChannelMappingEntry after = fx.service.channelMapping(fx.key1).orElseThrow();
            assertEquals(ChannelMappingStatus.AMBIGUOUS, after.status());
            assertTrue(after.mappedDefinitionId().isEmpty());
            assertFalse(fx.service.canProceedFromDecisionWorkspace());
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void choosingTiedDbcShowsSelectedDbcCoverageAndConfirmAction() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            fx.selectChannel(workspace, 1);
            DbcCandidateStrip strip = workspace.candidateStripForTest();
            DecisionActionBar bar = workspace.actionBarForTest();

            strip.selectIndexForTest(1);
            workspace.layout();

            String chosen = strip.selectedDisplayNameForTest();
            assertFalse(chosen.isBlank());
            assertEquals("SELECTED", strip.mappingStateForTest());
            assertTrue(bar.dbcTextForTest().contains(chosen), bar.dbcTextForTest());
            assertEquals("Selected DBC matches 1 of 4 CAN IDs (25%). Review the unmatched IDs with Compare,"
                    + " or use Confirm Selected DBC to accept this DBC for Ch1.", strip.actionHintForTest());
            Button confirm = bar.confirmButtonForTest();
            assertTrue(confirm.isVisible());
            assertFalse(confirm.isDisable());
            assertEquals("Confirm Selected DBC", confirm.getText());
            assertEquals(ChannelMappingStatus.AMBIGUOUS,
                    fx.service.channelMapping(fx.key1).orElseThrow().status(),
                    "picking a DBC is a working choice until Confirm");
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void confirmMakesChannelConfiguredWhileNoMatchChannelStaysWithoutDbc() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            fx.selectChannel(workspace, 1);
            DbcCandidateStrip strip = workspace.candidateStripForTest();
            DecisionActionBar bar = workspace.actionBarForTest();

            strip.selectIndexForTest(2);
            DefinitionSourceId chosen = strip.selectedDefinitionId().orElseThrow();
            bar.confirmButtonForTest().fire();
            workspace.refreshFromModel();

            SourceChannelMappingEntry ch1 = fx.service.channelMapping(fx.key1).orElseThrow();
            assertEquals(ChannelMappingStatus.MANUAL, ch1.status());
            assertEquals(chosen, ch1.mappedDefinitionId().orElseThrow());
            assertTrue(bar.acceptedVisibleForTest());
            assertFalse(bar.confirmButtonForTest().isVisible());
            assertEquals("CONFIRMED", strip.mappingStateForTest());
            assertTrue(strip.actionHintForTest().startsWith("Confirmed with medium coverage"),
                    strip.actionHintForTest());
            assertTrue(strip.actionHintForTest().contains("1 of 4 CAN IDs (25%)"), strip.actionHintForTest());
            assertTrue(strip.actionHintForTest().contains("processable"), strip.actionHintForTest());
            assertFalse(strip.mappingStateForTest().contains("NEEDS ATTENTION"));

            SourceChannelMappingEntry ch2 = fx.service.channelMapping(fx.key2).orElseThrow();
            assertEquals(ChannelMappingStatus.NO_MATCH, ch2.status());
            assertTrue(ch2.mappedDefinitionId().isEmpty());
            assertTrue(ch2.recommendedDefinitionId().isEmpty());
            DesktopAppService.DecisionProcessingReadiness readiness = fx.service.decisionProcessingReadiness();
            assertEquals(1, readiness.dbcConfiguredChannels());
            assertEquals(1, readiness.nativeOnlyChannels());
            assertEquals(0, readiness.unresolvedChannels());
            assertTrue(readiness.canProceed());
            assertFalse(bar.proceedButtonForTest().isDisable());
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void noMatchChannelIsNeverAssignedADbcAndDoesNotBlock() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            fx.selectChannel(workspace, 2);
            DbcCandidateStrip strip = workspace.candidateStripForTest();

            assertTrue(strip.selectedDisplayNameForTest().isEmpty());
            assertEquals("NEEDS ATTENTION", strip.mappingStateForTest());
            assertTrue(strip.actionHintForTest().startsWith("No uploaded DBC matches any CAN ID"),
                    strip.actionHintForTest());
            assertTrue(strip.actionHintForTest().contains("does not block"), strip.actionHintForTest());
            assertTrue(strip.actionHintForTest().contains("No DBC is assigned"), strip.actionHintForTest());
            assertFalse(workspace.actionBarForTest().confirmButtonForTest().isVisible());
            SourceChannelMappingEntry ch2 = fx.service.channelMapping(fx.key2).orElseThrow();
            assertEquals(ChannelMappingStatus.NO_MATCH, ch2.status());
            assertTrue(ch2.mappedDefinitionId().isEmpty());
            dispose(stage);
        });
    }

    @Test
    @Timeout(60)
    void primaryActionStaysFullyVisibleAtReleaseResolutions() throws Exception {
        for (double[] screen : new double[][] {{1280, 720}, {1366, 768}, {1536, 864}, {1920, 1080}}) {
            runOnFx(() -> {
                Fixture fx = Fixture.create();
                SourceInventoryWorkspace workspace = fx.bindWorkspace();
                double contentWidth = screen[0] - MainShellController.SIDEBAR_EXPANDED_WIDTH;
                Stage stage = show(workspace, contentWidth, screen[1]);
                fx.selectChannel(workspace, 1);
                workspace.candidateStripForTest().selectIndexForTest(0);
                workspace.refreshFromModel();
                workspace.applyCss();
                workspace.layout();

                DecisionActionBar bar = workspace.actionBarForTest();
                assertTrue(bar.selectionTextForTest().contains(LONG_SOURCE_NAME));
                workspace.headerStudyButtonForTest().fire();
                workspace.applyCss();
                workspace.layout();
                assertFalse(workspace.inspectorOverlaysForTest(),
                        "Study Set stays in the split at host width " + workspace.getWidth());
                assertFullyVisible(bar.proceedButtonForTest(), workspace, screen[0]);
                assertFullyVisible(bar.confirmButtonForTest(), workspace, screen[0]);
                assertTrue(bar.getWidth() <= workspace.getWidth() + 0.5,
                        "action bar wider than its workspace at " + screen[0]);
                dispose(stage);
            });
        }
    }

    @Test
    @Timeout(45)
    void lowCoverageRecommendationShowsTwelveOfSeventyNineWithoutChangingReadiness() throws Exception {
        runOnFx(() -> {
            LowCoverageFixture fx = LowCoverageFixture.create();
            SourceChannelMappingEntry before = fx.service.channelMapping(fx.key).orElseThrow();
            assertEquals(ChannelMappingStatus.RECOMMENDED, before.status());
            assertEquals(ChannelRecommendationKind.CLEAR_BEST_MATCH, before.recommendationKind());
            assertEquals("3.dbc", before.recommendedDisplayName());
            assertEquals(12L, before.matchedUniqueMessageCount());
            assertEquals(79L, before.observedUniqueMessageCount());
            assertTrue(before.mappedDefinitionId().isEmpty(), "a recommendation is not a confirmed mapping");
            var ranked = fx.service.candidatesFor(fx.key);
            assertEquals(12L, ranked.stream()
                    .filter(c -> "3.dbc".equals(c.definitionDisplayName()))
                    .mapToLong(c -> c.matchedUniqueMessageCount())
                    .findFirst().orElseThrow());
            assertEquals(4L, ranked.stream()
                    .filter(c -> "partial.dbc".equals(c.definitionDisplayName()))
                    .mapToLong(c -> c.matchedUniqueMessageCount())
                    .findFirst().orElseThrow());
            assertEquals("3.dbc", ranked.stream()
                    .max(java.util.Comparator.comparingLong(c -> c.matchedUniqueMessageCount()))
                    .orElseThrow()
                    .definitionDisplayName());

            DesktopAppService.DecisionProcessingReadiness readiness = fx.service.decisionProcessingReadiness();
            assertTrue(readiness.canProceed(), "low coverage must not become a processing blocker");
            assertEquals(0, readiness.unresolvedChannels());
            assertEquals(1, readiness.recommendedFallbackChannels());
            assertTrue(fx.service.acceptedDecisionProcessingChannels().isEmpty());

            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1706, 900);
            fx.selectChannel(workspace);
            DbcCandidateStrip strip = workspace.candidateStripForTest();

            assertEquals("3.dbc", strip.selectedDisplayNameForTest());
            assertNavigatorChannelLabel(workspace, "Ch1");
            assertEquals("RECOMMENDED", strip.mappingStateForTest());
            assertEquals("12 of 79 CAN IDs matched (15.19%)", strip.coverageSummaryForTest());
            assertEquals("LOW COVERAGE", strip.qualityBadgeForTest().getText());
            assertTrue(strip.qualityBadgeForTest().isVisible());
            assertTrue(strip.qualityBadgeForTest().getStyleClass().contains("coverage-low"));
            String hint = strip.actionHintForTest();
            assertTrue(hint.contains("Best available match: 3.dbc"), hint);
            assertTrue(hint.contains("12 of 79 CAN IDs matched (15.19%)"), hint);
            assertTrue(hint.contains("Low coverage — review unmatched IDs before confirming."), hint);

            SourceChannelMappingEntry after = fx.service.channelMapping(fx.key).orElseThrow();
            assertEquals(before.status(), after.status());
            assertEquals(before.recommendationKind(), after.recommendationKind());
            assertEquals(before.recommendedDefinitionId(), after.recommendedDefinitionId());
            assertEquals(before.matchedUniqueMessageCount(), after.matchedUniqueMessageCount());
            assertEquals(before.observedUniqueMessageCount(), after.observedUniqueMessageCount());
            assertTrue(after.mappedDefinitionId().isEmpty());
            DesktopAppService.DecisionProcessingReadiness afterReady = fx.service.decisionProcessingReadiness();
            assertEquals(readiness.canProceed(), afterReady.canProceed());
            assertEquals(readiness.unresolvedChannels(), afterReady.unresolvedChannels());
            assertEquals(readiness.recommendedFallbackChannels(), afterReady.recommendedFallbackChannels());
            assertTrue(fx.service.acceptedDecisionProcessingChannels().isEmpty());
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void longSummaryTextShrinksBeforeButtonsAreSqueezed() throws Exception {
        runOnFx(() -> {
            DecisionActionBar bar = new DecisionActionBar();
            String longText = "Selected: " + LONG_SOURCE_NAME + " / All Channels \u00b7 Ch1";
            bar.refresh(new DecisionActionBar.ActionState(
                    longText, "can1-11-bit-OBD2-isotp-DTCs-header-v4.0.dbc", "SELECTED",
                    "Confirm Selected DBC", true, false, true, false,
                    "16 channels do not have a valid DBC recommendation or selection. 8 match no uploaded DBC;"
                            + " Custom Decode needs a DBC for every channel, so use Auto Decode.",
                    ""));
            StackPane host = new StackPane(bar);
            Scene scene = new Scene(host, 820, 80);
            addCss(scene);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            host.applyCss();
            host.layout();

            assertTrue(bar.minWidth(-1) <= 820, "bar min width " + bar.minWidth(-1));
            for (Button b : List.of(bar.confirmButtonForTest(), bar.compareButtonForTest(), bar.proceedButtonForTest())) {
                assertTrue(b.getWidth() + 0.5 >= b.prefWidth(-1), b.getText() + " was squeezed");
                assertFullyVisible(b, host, 820);
            }
            Bounds proceed = bar.proceedButtonForTest().localToScene(bar.proceedButtonForTest().getBoundsInLocal());
            Bounds confirm = bar.confirmButtonForTest().localToScene(bar.confirmButtonForTest().getBoundsInLocal());
            assertTrue(confirm.getMaxX() <= proceed.getMinX(), "buttons overlap");
            assertEquals(longText, bar.selectionTextForTest(), "full text is kept; only rendering is ellipsized");
            dispose(stage);
        });
    }

    private static void assertNavigatorChannelLabel(SourceInventoryWorkspace workspace, String expected) {
        boolean found = false;
        for (Node node : workspace.navigatorForTest().lookupAll(".source-inventory-channel-label")) {
            if (node instanceof Label label && expected.equals(label.getText()) && label.isVisible()
                    && label.getWidth() >= 16) {
                found = true;
            }
        }
        assertTrue(found, "navigator missing " + expected);
    }

    private static void assertFullyVisible(Node node, Node container, double screenWidth) {
        assertTrue(node.isVisible(), node + " hidden");
        Bounds b = node.localToScene(node.getBoundsInLocal());
        Bounds c = container.localToScene(container.getBoundsInLocal());
        assertTrue(b.getWidth() > 20, node + " collapsed: " + b);
        assertTrue(b.getMinX() >= c.getMinX() - 0.5 && b.getMaxX() <= c.getMaxX() + 0.5,
                node + " clipped at " + screenWidth + ": " + b + " outside " + c);
        assertTrue(b.getMaxX() <= node.getScene().getWidth() + 0.5,
                node + " beyond window at " + screenWidth + ": " + b);
    }

    private static Stage show(SourceInventoryWorkspace workspace, double w, double h) {
        StackPane host = new StackPane(workspace);
        Scene scene = new Scene(host, w, h);
        addCss(scene);
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        host.resize(w, h);
        host.layout();
        workspace.resize(w - 40, h - 80);
        workspace.layout();
        return stage;
    }

    private static void addCss(Scene scene) {
        var css = SourceInventoryWorkspace.class.getResource("/css/stellar-desktop.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
    }

    private static void dispose(Stage stage) {
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

    private record LowCoverageFixture(DesktopAppService service, WorkspaceFile file, SourceChannelKey key) {

        static LowCoverageFixture create() {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile file = service.workspace().addFile(
                    new WorkspaceFile(Path.of("MF4_KA04NC7760_Bengaluru.mf4").toAbsolutePath(),
                            FileKind.MEASUREMENT_MF4, 10));
            List<CanMessageInventory> observed = new ArrayList<>();
            for (int i = 0; i < 79; i++) {
                observed.add(new CanMessageInventory(
                        new CanMessageKey(1, 0x100 + i, false), 100, 8, 8, false));
            }
            CanChannelInventory channel = new CanChannelInventory(
                    1, "Channel 1", 7_900, 79, 79, 0, 0, observed);
            CanSourceInventory inventory = new CanSourceInventory(
                    file.path(), file.fileName(), MeasurementSourceFormat.MF4,
                    CanInventoryStatus.INVENTORY_READY, 1, 7_900, 79, true, List.of(channel), List.of());
            service.sourceInventoryModel().put(file.id(), inventory);
            service.definitionMatchingCoordinator().ensureReadyInventory(file.projectSourceId(), inventory);
            service.definitionMatchingCoordinator().registerDefinition(
                    DefinitionSourceId.generate(), "partial.dbc", dbcCovering(4));
            service.definitionMatchingCoordinator().registerDefinition(
                    DefinitionSourceId.generate(), "3.dbc", dbcCovering(12));
            return new LowCoverageFixture(service, file, SourceChannelKey.of(file.projectSourceId(), 1));
        }

        SourceInventoryWorkspace bindWorkspace() {
            SourceInventoryWorkspace workspace = new SourceInventoryWorkspace();
            workspace.bind(service, () -> { });
            workspace.showDetailedMapping();
            return workspace;
        }

        void selectChannel(SourceInventoryWorkspace workspace) {
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(file.projectSourceId(), 1);
            workspace.refreshFromModel();
            workspace.layout();
        }

        private static DbcDatabase dbcCovering(int matchedIds) {
            List<DbcMessage> messages = new ArrayList<>();
            for (int i = 0; i < matchedIds; i++) {
                messages.add(new DbcMessage(
                        CanIdKey.fromFrame(0x100 + i, false),
                        "Msg_" + Integer.toHexString(0x100 + i),
                        8, "ECU", List.of(), ""));
            }
            return new DbcDatabase("", List.of(), messages, List.of());
        }
    }

    private record Fixture(DesktopAppService service, WorkspaceFile file, SourceChannelKey key1, SourceChannelKey key2) {

        static Fixture create() {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile file = service.workspace().addFile(
                    new WorkspaceFile(Path.of(LONG_SOURCE_NAME).toAbsolutePath(), FileKind.MEASUREMENT_MF4, 10));
            CanChannelInventory ch1 = new CanChannelInventory(1, "Channel 1", 40_000, 4, 4, 0, 0, List.of(
                    message(1, 0x7E8), message(1, 0x316), message(1, 0x329), message(1, 0x100)));
            CanChannelInventory ch2 = new CanChannelInventory(2, "Channel 2", 10_000, 1, 1, 0, 0, List.of(
                    message(2, 0x200)));
            CanSourceInventory inv = new CanSourceInventory(
                    file.path(), file.fileName(), MeasurementSourceFormat.MF4,
                    CanInventoryStatus.INVENTORY_READY, 2, 50_000, 5, true, List.of(ch1, ch2), List.of());
            service.sourceInventoryModel().put(file.id(), inv);
            service.definitionMatchingCoordinator().ensureReadyInventory(file.projectSourceId(), inv);
            for (String name : List.of("11_bit_OBD2 2.dbc", "CSS-Electronics-OBD2-v1_4.dbc",
                    "can1-11-bit-OBD2-isotp-DTCs-header-v4.0.dbc")) {
                service.definitionMatchingCoordinator().registerDefinition(
                        DefinitionSourceId.generate(), name, obd2ResponseOnlyDbc());
            }
            return new Fixture(service, file,
                    SourceChannelKey.of(file.projectSourceId(), 1),
                    SourceChannelKey.of(file.projectSourceId(), 2));
        }

        SourceInventoryWorkspace bindWorkspace() {
            SourceInventoryWorkspace workspace = new SourceInventoryWorkspace();
            workspace.bind(service, () -> { });
            workspace.showDetailedMapping();
            return workspace;
        }

        void selectChannel(SourceInventoryWorkspace workspace, int channel) {
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(file.projectSourceId(), channel);
            workspace.refreshFromModel();
            workspace.layout();
        }

        private static CanMessageInventory message(int channel, int id) {
            return new CanMessageInventory(new CanMessageKey(channel, id, false), 10_000, 8, 8, false);
        }

        private static DbcDatabase obd2ResponseOnlyDbc() {
            List<DbcSignal> signals = new ArrayList<>();
            signals.add(new DbcSignal("S1_PID_0C_EngineRPM", 24, 16, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,
                    0.25, 0, 0, 16383, "rpm", List.of(), DbcMultiplexRole.NONE, null, "", DbcValueTable.empty()));
            DbcMessage msg = new DbcMessage(CanIdKey.fromFrame(0x7E8, false), "OBD2", 8, "ECU", signals, "");
            return new DbcDatabase("", List.of(), List.of(msg), List.of());
        }
    }
}
