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
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.processing.ParserRegistry;
import javafx.application.Platform;
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
 * The release dataset: 8 MF4 sources, Ch1 tied across three DBCs that each define only
 * CAN ID 0x7E8, Ch2 matching none of them.
 */
class EightSourceObd2TieScenarioTest {

    private static final String CHOSEN_DBC = "11_bit_OBD2 2.dbc";

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
    @Timeout(60)
    void autoDecodeTieSelectsListingOrderWinnerWithoutManualChoice() throws Exception {
        runOnFx(() -> {
            Scenario s = Scenario.create();
            QuickDecodePane pane = bindAuto(s);

            DesktopAppService.DecisionProcessingReadiness initial = s.service.decisionProcessingReadiness();
            assertEquals(8, initial.dbcConfiguredChannels());
            assertEquals(8, initial.nativeOnlyChannels());
            assertEquals(0, initial.unresolvedChannels());
            assertTrue(initial.canProceed());
            assertEquals("8 Sources  ·  16/16 Channels Selected  ·  40 CAN IDs  ·  400,000 Frames  ·  "
                    + "16 Processable", pane.bottomSummaryTextForTest());
            assertFalse(pane.decodeButtonForTest().isDisable());
            assertFalse(pane.applyReviewButtonForTest().isVisible());
            for (WorkspaceFile source : s.sources) {
                QuickDecodeDecisionResolverStatus ch1 = s.decision(source, 1);
                assertEquals("READY", ch1.status());
                assertEquals(CHOSEN_DBC, ch1.dbcName());
                assertEquals(s.chosenId.value(), ch1.definitionId());
                assertNoMatchUntouched(s, source);
                assertEquals("1 Ready · 1 No Match",
                        pane.sourceStatusSummaryForTest(source.projectSourceId()));
            }

            s.service.quickDecodeSelectionModel().setOverride(s.channel(s.sources.getFirst(), 1), s.chosenId);
            pane.refresh();
            QuickDecodeDecisionResolverStatus overridden = s.decision(s.sources.getFirst(), 1);
            assertEquals("READY", overridden.status());
            assertEquals(CHOSEN_DBC, overridden.dbcName());
            assertTrue(s.service.quickDecodeDecisionResolver()
                    .resolve(s.channel(s.sources.getFirst(), 1)).manualOverride());

            s.service.enterProcessingWorkspaceFromDecision();
            ProjectProcessingPlan plan = s.service.processingSelectionModel().reviewedPlan();
            assertEquals(ProcessingPlanReadiness.READY, plan.readiness());
            assertEquals(16, plan.jobs().size());
            for (ProcessingJob job : plan.jobs()) {
                assertTrue(job.executable());
                if (job.channelId() == 1) {
                    assertEquals(s.chosenId.value(), job.definitionSourceId().orElseThrow());
                    assertEquals(CHOSEN_DBC, job.definitionDisplayName());
                    if (job.projectSourceId().equals(s.sources.getFirst().id())) {
                        assertEquals(MappingLifecycle.MANUAL, job.mappingLifecycle());
                    } else {
                        assertEquals(MappingLifecycle.RECOMMENDED_FALLBACK, job.mappingLifecycle());
                    }
                } else {
                    assertEquals(MappingLifecycle.NO_MATCH, job.mappingLifecycle());
                    assertTrue(job.definitionSourceId().isEmpty());
                }
            }
        });
    }

    @Test
    @Timeout(90)
    void customDecodeConfirmOneThenApplyRemainingReviewChannels() throws Exception {
        runOnFx(() -> {
            Scenario s = Scenario.create();
            s.service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);

            DesktopAppService.DecisionProcessingReadiness initial = s.service.decisionProcessingReadiness();
            assertEquals(0, initial.dbcConfiguredChannels());
            assertEquals(8, initial.nativeOnlyChannels());
            assertEquals(8, initial.unresolvedChannels());
            assertFalse(initial.canProceed());
            assertTrue(initial.hint().contains("8 channels still need a DBC choice"), initial.hint());
            assertTrue(initial.hint().contains("8 No Match channels stay native-only"), initial.hint());

            s.service.chooseChannelDefinition(s.channel(s.sources.getFirst(), 1), s.chosenId);
            SourceChannelMappingEntry confirmed = s.service.channelMapping(s.channel(s.sources.getFirst(), 1)).orElseThrow();
            assertEquals(ChannelMappingStatus.MANUAL, confirmed.status());
            assertEquals(s.chosenId, confirmed.mappedDefinitionId().orElseThrow());
            assertEquals(CHOSEN_DBC, confirmed.mappedDisplayName());
            assertEquals(1, confirmed.matchedUniqueMessageCount());
            assertEquals(4, confirmed.observedUniqueMessageCount());
            assertEquals(0.25, confirmed.coverageRatio(), 0.0001);

            DesktopAppService.DecisionProcessingReadiness one = s.service.decisionProcessingReadiness();
            assertEquals(1, one.dbcConfiguredChannels());
            assertEquals(8, one.nativeOnlyChannels());
            assertEquals(7, one.unresolvedChannels());
            assertFalse(one.canProceed());
            assertEquals("7 channels still need a DBC choice before processing. "
                    + "7 have several equally matching DBCs. Choose one, then Confirm Selected DBC "
                    + "or Apply it to every compatible Review channel. "
                    + "8 No Match channels stay native-only and do not need a DBC.", one.hint());
            for (int i = 1; i < s.sources.size(); i++) {
                assertReview(s, s.sources.get(i));
            }
            for (WorkspaceFile source : s.sources) {
                assertNoMatchUntouched(s, source);
            }

            SourceInventoryWorkspace workspace = new SourceInventoryWorkspace();
            workspace.bind(s.service, () -> { });
            workspace.showDetailedMapping();
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(s.sources.getFirst().projectSourceId(), 1);
            workspace.refreshFromModel();
            assertEquals("CONFIRMED", workspace.candidateStripForTest().mappingStateForTest());
            assertTrue(workspace.candidateStripForTest().actionHintForTest().startsWith("Confirmed with medium coverage"),
                    workspace.candidateStripForTest().actionHintForTest());
            assertTrue(workspace.candidateStripForTest().actionHintForTest().contains("processable"));
            assertTrue(workspace.actionBarForTest().proceedButtonForTest().isDisable());
            assertTrue(workspace.actionBarForTest().savedHintForTest().contains("7 channels still need a DBC choice"),
                    workspace.actionBarForTest().savedHintForTest());
            assertEquals("Apply to 7 Review channels", workspace.actionBarForTest().applyReviewButtonForTest().getText());

            workspace.actionBarForTest().applyReviewButtonForTest().fire();
            workspace.refreshFromModel();

            DesktopAppService.DecisionProcessingReadiness done = s.service.decisionProcessingReadiness();
            assertEquals(8, done.dbcConfiguredChannels());
            assertEquals(8, done.nativeOnlyChannels());
            assertEquals(0, done.unresolvedChannels());
            assertTrue(done.canProceed());
            assertFalse(workspace.actionBarForTest().proceedButtonForTest().isDisable());
            for (WorkspaceFile source : s.sources) {
                SourceChannelMappingEntry ch1 = s.service.channelMapping(s.channel(source, 1)).orElseThrow();
                assertEquals(ChannelMappingStatus.MANUAL, ch1.status());
                assertEquals(s.chosenId, ch1.mappedDefinitionId().orElseThrow());
                assertEquals(CHOSEN_DBC, ch1.mappedDisplayName());
                assertNoMatchUntouched(s, source);
            }

            s.service.enterProcessingWorkspaceFromDecision();
            assertPlan(s.service.processingSelectionModel().reviewedPlan(), s.chosenId);
        });
    }

    private static void assertPlan(ProjectProcessingPlan plan, DefinitionSourceId chosenId) {
        assertEquals(ProcessingPlanReadiness.READY, plan.readiness());
        assertEquals(16, plan.jobs().size());
        assertEquals(8, plan.jobs().stream().filter(job -> job.channelId() == 1).count());
        assertEquals(8, plan.jobs().stream().filter(job -> job.channelId() == 2).count());
        for (ProcessingJob job : plan.jobs()) {
            assertTrue(job.executable());
            if (job.channelId() == 1) {
                assertEquals(MappingLifecycle.MANUAL, job.mappingLifecycle());
                assertEquals(chosenId.value(), job.definitionSourceId().orElseThrow());
                assertEquals(CHOSEN_DBC, job.definitionDisplayName());
            } else {
                assertEquals(MappingLifecycle.NO_MATCH, job.mappingLifecycle());
                assertTrue(job.definitionSourceId().isEmpty());
                assertTrue(job.definitionDisplayName().isBlank());
            }
        }
    }

    private static void assertReview(Scenario s, WorkspaceFile source) {
        SourceChannelMappingEntry mapping = s.service.channelMapping(s.channel(source, 1)).orElseThrow();
        assertEquals(ChannelMappingStatus.AMBIGUOUS, mapping.status());
        assertEquals(ChannelRecommendationKind.AMBIGUOUS, mapping.recommendationKind());
        assertTrue(mapping.mappedDefinitionId().isEmpty());
        QuickDecodeDecisionResolverStatus decision = s.decision(source, 1);
        if (s.service.decisionWorkspaceMode() == DecisionWorkspaceMode.QUICK_DECODE
                && s.service.quickDecodeSelectionModel().overrideFor(s.channel(source, 1)).isEmpty()) {
            assertEquals("REVIEW", decision.status());
            assertTrue(decision.definitionId() == null || decision.definitionId().isBlank());
        }
    }

    private static void assertNoMatchUntouched(Scenario s, WorkspaceFile source) {
        SourceChannelMappingEntry mapping = s.service.channelMapping(s.channel(source, 2)).orElseThrow();
        assertEquals(ChannelMappingStatus.NO_MATCH, mapping.status());
        assertEquals(ChannelRecommendationKind.NO_MATCH, mapping.recommendationKind());
        assertTrue(mapping.mappedDefinitionId().isEmpty());
        assertTrue(mapping.recommendedDefinitionId().isEmpty());
        assertTrue(s.service.quickDecodeSelectionModel().overrideFor(s.channel(source, 2)).isEmpty());
        QuickDecodeDecisionResolverStatus decision = s.decision(source, 2);
        assertEquals("NO_MATCH", decision.status());
        assertTrue(decision.definitionId() == null || decision.definitionId().isBlank());
    }

    private static QuickDecodePane bindAuto(Scenario s) {
        QuickDecodePane pane = new QuickDecodePane();
        pane.bind(s.service, ignored -> { }, () -> { }, () -> { });
        pane.setModeActive(true);
        pane.refresh();
        return pane;
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
        assertTrue(done.await(80, TimeUnit.SECONDS), "JavaFX task timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @FunctionalInterface
    private interface FxBody {
        void run() throws Exception;
    }

    private record QuickDecodeDecisionResolverStatus(String status, String dbcName, String definitionId) {
    }

    private record Scenario(DesktopAppService service, List<WorkspaceFile> sources, DefinitionSourceId chosenId) {

        static Scenario create() {
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
            List<WorkspaceFile> sources = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                WorkspaceFile file = service.workspace().addFile(new WorkspaceFile(
                        Path.of("source-" + i + ".mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 10));
                CanChannelInventory ch1 = new CanChannelInventory(1, "Channel 1", 40_000, 4, 4, 0, 0, List.of(
                        message(1, 0x7E8), message(1, 0x316), message(1, 0x329), message(1, 0x100)));
                CanChannelInventory ch2 = new CanChannelInventory(2, "Channel 2", 10_000, 1, 1, 0, 0, List.of(
                        message(2, 0x200)));
                CanSourceInventory inventory = new CanSourceInventory(
                        file.path(), file.fileName(), MeasurementSourceFormat.MF4,
                        CanInventoryStatus.INVENTORY_READY, 2, 50_000, 5, true, List.of(ch1, ch2), List.of());
                service.sourceInventoryModel().put(file.id(), inventory);
                service.definitionMatchingCoordinator().ensureReadyInventory(file.projectSourceId(), inventory);
                sources.add(file);
            }
            DefinitionSourceId chosenId = DefinitionSourceId.generate();
            service.definitionMatchingCoordinator().registerDefinition(chosenId, CHOSEN_DBC, obd2ResponseOnlyDbc());
            service.definitionMatchingCoordinator().registerDefinition(
                    DefinitionSourceId.generate(), "CSS-Electronics-OBD2-v1_4.dbc", obd2ResponseOnlyDbc());
            service.definitionMatchingCoordinator().registerDefinition(
                    DefinitionSourceId.generate(), "can1-11-bit-OBD2-isotp-DTCs-header-v4.0.dbc", obd2ResponseOnlyDbc());
            return new Scenario(service, List.copyOf(sources), chosenId);
        }

        SourceChannelKey channel(WorkspaceFile source, int channelId) {
            return SourceChannelKey.of(source.projectSourceId(), channelId);
        }

        QuickDecodeDecisionResolverStatus decision(WorkspaceFile source, int channelId) {
            var decision = service.quickDecodeDecisionResolver().resolve(channel(source, channelId));
            String id = decision.selected() == null ? null : decision.selected().definitionSourceId().value();
            String name = decision.selected() == null ? "" : decision.selected().definitionDisplayName();
            return new QuickDecodeDecisionResolverStatus(decision.status().name(), name, id);
        }

        private static CanMessageInventory message(int channel, int id) {
            return new CanMessageInventory(new CanMessageKey(channel, id, false), 10_000, 8, 8, false);
        }

        private static DbcDatabase obd2ResponseOnlyDbc() {
            DbcSignal signal = new DbcSignal("S1_PID_0C_EngineRPM", 24, 16, DbcByteOrder.INTEL,
                    DbcValueType.UNSIGNED, 0.25, 0, 0, 16383, "rpm", List.of(),
                    DbcMultiplexRole.NONE, null, "", DbcValueTable.empty());
            DbcMessage message = new DbcMessage(CanIdKey.fromFrame(0x7E8, false), "OBD2", 8, "ECU",
                    List.of(signal), "");
            return new DbcDatabase("", List.of(), List.of(message), List.of());
        }
    }
}
