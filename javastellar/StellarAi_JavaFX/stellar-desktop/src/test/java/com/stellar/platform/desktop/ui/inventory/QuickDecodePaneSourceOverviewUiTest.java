package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionCandidateKind;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickDecodePaneSourceOverviewUiTest {

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
    void collapsedSourceShowsChannelCountRecommendationDistributionAndHealth() throws Exception {
        runOnFx(() -> {
            Fixture fixture = Fixture.create();
            QuickDecodePane pane = new QuickDecodePane();
            pane.bind(fixture.service, key -> { }, () -> { }, () -> { });
            pane.setModeActive(true);
            pane.refresh();

            assertFalse(pane.sourceExpandedForTest(fixture.source.projectSourceId()));
            List<String> columns = pane.columnTitlesForTest();
            assertEquals("CAN IDs", columns.get(2));
            assertEquals("Channels", columns.get(3));
            assertEquals("Recommended DBC(s)", columns.get(4));

            String recommendations = pane.sourceRecommendationSummaryForTest(fixture.source.projectSourceId());
            assertTrue(recommendations.contains("body.dbc · 2 Ch"), recommendations);
            assertTrue(recommendations.contains("powertrain.dbc · 1 Ch"), recommendations);
            assertEquals("3 Ready", pane.sourceStatusSummaryForTest(fixture.source.projectSourceId()));
            assertEquals("Best per Channel", pane.sourceStrategyTextForTest(fixture.source.projectSourceId()));
        });
    }

    @Test
    void planOverviewSeparatesDbcNoMatchFromMeasurementWithNoCanChannels() throws Exception {
        runOnFx(() -> {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile noData = service.workspace().addFile(new WorkspaceFile(
                    Path.of("no-can-data.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 100));
            WorkspaceFile noMatch = service.workspace().addFile(new WorkspaceFile(
                    Path.of("no-dbc-match.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 100));
            service.workspace().addFile(new WorkspaceFile(
                    Path.of("available.dbc").toAbsolutePath(), FileKind.DEFINITION_DBC, 100));

            service.sourceInventoryModel().put(noData.id(), new CanSourceInventory(
                    noData.path(), noData.fileName(), MeasurementSourceFormat.MF4,
                    CanInventoryStatus.INVENTORY_READY, 0, 0, 0, true, List.of(), List.of()));
            service.sourceInventoryModel().put(noMatch.id(), new CanSourceInventory(
                    noMatch.path(), noMatch.fileName(), MeasurementSourceFormat.MF4,
                    CanInventoryStatus.INVENTORY_READY, 1, 500, 3, true,
                    List.of(Fixture.channel(1, 3, 500)), List.of()));

            SourceChannelKey key = SourceChannelKey.of(noMatch.projectSourceId(), 1);
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    key, ChannelMappingStatus.NO_MATCH, ChannelRecommendationKind.NO_MATCH,
                    null, "", null, "", 3, 0, 0.0));

            QuickDecodePane pane = new QuickDecodePane();
            pane.bind(service, ignored -> { }, () -> { }, () -> { });
            pane.setModeActive(true);
            pane.refresh();

            assertEquals("1 Ready with warning", pane.noMatchMetricTextForTest());
            assertEquals("1 No CAN Data", pane.noCanDataMetricTextForTest());
            assertEquals("No Match · 1 Ch", pane.sourceRecommendationSummaryForTest(noMatch.projectSourceId()));
            assertEquals("1 No Match", pane.sourceStatusSummaryForTest(noMatch.projectSourceId()));
            assertEquals("No CAN channels discovered", pane.sourceRecommendationSummaryForTest(noData.projectSourceId()));
            assertEquals("No CAN Data", pane.sourceStatusSummaryForTest(noData.projectSourceId()));
        });
    }

    @Test
    @Timeout(45)
    void autoDecodeNoDbcEnablesReviewAndProcessWithWarning() throws Exception {
        runOnFx(() -> {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile source = service.workspace().addFile(new WorkspaceFile(
                    Path.of("native-only.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 100));
            service.sourceInventoryModel().put(source.id(), new CanSourceInventory(
                    source.path(), source.fileName(), MeasurementSourceFormat.MF4,
                    CanInventoryStatus.INVENTORY_READY, 1, 128_788, 3, true,
                    List.of(Fixture.channel(1, 3, 128_788)), List.of()));
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    SourceChannelKey.of(source.projectSourceId(), 1),
                    ChannelMappingStatus.UNMAPPED, ChannelRecommendationKind.NO_MATCH,
                    null, "", null, "", 3, 0, 0.0));

            QuickDecodePane pane = new QuickDecodePane();
            pane.bind(service, ignored -> { }, () -> { }, () -> { });
            pane.setModeActive(true);
            pane.refresh();

            assertTrue(service.canProceedFromDecisionWorkspace());
            assertFalse(pane.decodeButtonForTest().isDisable());
            assertEquals("0 Configured", pane.readyMetricTextForTest());
            assertEquals("1 Ready with warning", pane.noMatchMetricTextForTest());
        });
    }

    @Test
    @Timeout(45)
    void autoDecodeDeselectedChannelDisablesReviewAndProcess() throws Exception {
        runOnFx(() -> {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile source = service.workspace().addFile(new WorkspaceFile(
                    Path.of("deselected.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 100));
            service.sourceInventoryModel().put(source.id(), new CanSourceInventory(
                    source.path(), source.fileName(), MeasurementSourceFormat.MF4,
                    CanInventoryStatus.INVENTORY_READY, 1, 500, 3, true,
                    List.of(Fixture.channel(1, 3, 500)), List.of()));
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    SourceChannelKey.of(source.projectSourceId(), 1),
                    ChannelMappingStatus.UNMAPPED, ChannelRecommendationKind.NO_MATCH,
                    null, "", null, "", 3, 0, 0.0));
            service.quickDecodeSelectionModel().setIncluded(
                    SourceChannelKey.of(source.projectSourceId(), 1), false);

            QuickDecodePane pane = new QuickDecodePane();
            pane.bind(service, ignored -> { }, () -> { }, () -> { });
            pane.setModeActive(true);
            pane.refresh();

            assertFalse(service.canProceedFromDecisionWorkspace());
            assertTrue(pane.decodeButtonForTest().isDisable());
        });
    }

    @Test
    @Timeout(45)
    void tiedDbcChannelsAreAutoConfiguredAndReviewAndProcessIsEnabled() throws Exception {
        runOnFx(() -> {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            List<WorkspaceFile> dbcs = List.of(
                    service.workspace().addFile(new WorkspaceFile(
                            Path.of("11_bit_OBD2 2.dbc").toAbsolutePath(), FileKind.DEFINITION_DBC, 100)),
                    service.workspace().addFile(new WorkspaceFile(
                            Path.of("CSS-Electronics-OBD2-v1_4.dbc").toAbsolutePath(), FileKind.DEFINITION_DBC, 100)),
                    service.workspace().addFile(new WorkspaceFile(
                            Path.of("can1-11-bit-OBD2-isotp-DTCs-header-v4.0.dbc").toAbsolutePath(),
                            FileKind.DEFINITION_DBC, 100)));
            List<WorkspaceFile> sources = new ArrayList<>();
            List<SourceChannelDefinitionCandidate> candidates = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                WorkspaceFile source = service.workspace().addFile(new WorkspaceFile(
                        Path.of("drive-" + i + ".mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 100));
                service.sourceInventoryModel().put(source.id(), new CanSourceInventory(
                        source.path(), source.fileName(), MeasurementSourceFormat.MF4,
                        CanInventoryStatus.INVENTORY_READY, 2, 2000, 2, true,
                        List.of(Fixture.channel(1, 3, 1000), Fixture.channel(2, 1, 1000)), List.of()));
                for (WorkspaceFile dbc : dbcs) {
                    candidates.add(Fixture.candidate(source, 1, dbc, 1, 3, 400, 1000));
                    candidates.add(Fixture.candidate(source, 2, dbc, 0, 1, 0, 1000));
                }
                service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                        SourceChannelKey.of(source.projectSourceId(), 1),
                        ChannelMappingStatus.AMBIGUOUS, ChannelRecommendationKind.AMBIGUOUS,
                        null, "", null, "", 3, 1, 1 / 3.0));
                service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                        SourceChannelKey.of(source.projectSourceId(), 2),
                        ChannelMappingStatus.NO_MATCH, ChannelRecommendationKind.NO_MATCH,
                        null, "", null, "", 1, 0, 0.0));
                sources.add(source);
            }
            service.definitionMatchingModel().replaceCandidates(candidates);

            QuickDecodePane pane = new QuickDecodePane();
            pane.bind(service, ignored -> { }, () -> { }, () -> { });
            pane.setModeActive(true);
            pane.refresh();

            DesktopAppService.DecisionProcessingReadiness readiness = service.decisionProcessingReadiness();
            assertEquals(8, readiness.dbcConfiguredChannels());
            assertEquals(8, readiness.nativeOnlyChannels());
            assertEquals(0, readiness.unresolvedChannels());
            assertTrue(service.canProceedFromDecisionWorkspace());
            assertFalse(pane.decodeButtonForTest().isDisable());
            assertFalse(pane.compactProceedButtonForTest().isDisable());
            String summary = pane.bottomSummaryTextForTest();
            assertTrue(summary.contains("16/16 Channels Selected"), summary);
            assertTrue(summary.contains("16 Processable"), summary);
            assertFalse(summary.contains("Needs Setup"), summary);
            String rollup = pane.sourceRecommendationSummaryForTest(sources.getFirst().projectSourceId());
            assertTrue(rollup.contains("11_bit_OBD2 2.dbc · 1 Ch"), rollup);
            assertTrue(rollup.contains("No Match · 1 Ch"), rollup);

            for (WorkspaceFile source : sources) {
                service.quickDecodeSelectionModel().setOverride(
                        SourceChannelKey.of(source.projectSourceId(), 1), dbcs.get(1).definitionSourceId());
            }
            pane.refresh();

            assertFalse(pane.decodeButtonForTest().isDisable());
            assertFalse(pane.bottomSummaryTextForTest().contains("Needs Setup"));
            assertTrue(pane.bottomSummaryTextForTest().contains("16 Processable"), pane.bottomSummaryTextForTest());
        });
    }

    @Test
    void sourceStrategyReflectsBulkOverrideWithoutChangingRecommendationSummary() throws Exception {
        runOnFx(() -> {
            Fixture fixture = Fixture.create();
            QuickDecodePane pane = new QuickDecodePane();
            pane.bind(fixture.service, key -> { }, () -> { }, () -> { });
            pane.setModeActive(true);
            pane.refresh();
            String before = pane.sourceRecommendationSummaryForTest(fixture.source.projectSourceId());

            fixture.service.quickDecodeSelectionModel().setOverrides(
                    List.of(
                            SourceChannelKey.of(fixture.source.projectSourceId(), 1),
                            SourceChannelKey.of(fixture.source.projectSourceId(), 2),
                            SourceChannelKey.of(fixture.source.projectSourceId(), 3)),
                    fixture.bodyDbc.definitionSourceId());
            pane.refresh();

            assertEquals("Override All · body.dbc",
                    pane.sourceStrategyTextForTest(fixture.source.projectSourceId()));
            assertEquals(before, pane.sourceRecommendationSummaryForTest(fixture.source.projectSourceId()),
                    "manual override must not rewrite system recommendations");
        });
    }

    @Test
    void quickDecodeSelectedRowCssKeepsEngineeringTextAndDisclosureArrowReadable() throws Exception {
        try (InputStream in = QuickDecodePane.class.getResourceAsStream("/css/pages/sources.css")) {
            assertTrue(in != null, "sources stylesheet must be available");
            String css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(css.contains(".quick-decode-table .tree-table-row-cell:selected"));
            assertTrue(css.contains(".quick-decode-table .tree-table-row-cell:selected .tree-table-cell"));
            assertTrue(css.contains(".quick-decode-table .tree-table-row-cell:selected .tree-disclosure-node > .arrow"));
            int selectedCell = css.indexOf(".quick-decode-table .tree-table-row-cell:selected .tree-table-cell");
            assertTrue(selectedCell >= 0);
            String selectedBlock = css.substring(selectedCell, selectedCell + 280);
            assertTrue(selectedBlock.contains("-fx-text-fill:"), "selected engineering text must stay readable");
            assertFalse(selectedBlock.toLowerCase().contains("#ffffff"), "selected rows must not invert to white text");
            assertFalse(selectedBlock.contains("-stellar-surface"), "selected text must not use the surface token");
            int arrow = css.indexOf(".quick-decode-table .tree-table-row-cell:selected .tree-disclosure-node > .arrow");
            assertTrue(arrow >= 0);
            assertTrue(css.substring(arrow, arrow + 220).contains("-stellar-text-muted"),
                    "disclosure arrow uses the muted text token");
        }
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

    private record Fixture(
            DesktopAppService service,
            WorkspaceFile source,
            WorkspaceFile bodyDbc,
            WorkspaceFile powertrainDbc
    ) {
        static Fixture create() {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile source = service.workspace().addFile(new WorkspaceFile(
                    Path.of("quick-overview.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 100));
            WorkspaceFile body = service.workspace().addFile(new WorkspaceFile(
                    Path.of("body.dbc").toAbsolutePath(), FileKind.DEFINITION_DBC, 100));
            WorkspaceFile powertrain = service.workspace().addFile(new WorkspaceFile(
                    Path.of("powertrain.dbc").toAbsolutePath(), FileKind.DEFINITION_DBC, 100));

            List<CanChannelInventory> channels = List.of(
                    channel(1, 67, 1000),
                    channel(2, 55, 1000),
                    channel(3, 31, 1000));
            service.sourceInventoryModel().put(source.id(), new CanSourceInventory(
                    source.path(), source.fileName(), MeasurementSourceFormat.MF4,
                    CanInventoryStatus.INVENTORY_READY, 3, 3000, 121, true, channels, List.of()));

            installChannel(service, source, 1, body, 54, 67, 920, 1000,
                    candidate(source, 1, powertrain, 10, 67, 150, 1000));
            installChannel(service, source, 2, powertrain, 44, 55, 810, 1000,
                    candidate(source, 2, body, 5, 55, 100, 1000));
            installChannel(service, source, 3, body, 31, 31, 1000, 1000,
                    candidate(source, 3, powertrain, 3, 31, 80, 1000));
            return new Fixture(service, source, body, powertrain);
        }

        static CanChannelInventory channel(int id, long uniqueIds, long frames) {
            return new CanChannelInventory(
                    id, "Channel " + id, frames, uniqueIds, uniqueIds, 0, 0,
                    List.of(new CanMessageInventory(new CanMessageKey(id, 0x100L + id, false),
                            frames, 8, 8, false)));
        }

        private static void installChannel(
                DesktopAppService service,
                WorkspaceFile source,
                int channelId,
                WorkspaceFile recommendedDbc,
                long matchedIds,
                long observedIds,
                long matchedFrames,
                long observedFrames,
                SourceChannelDefinitionCandidate... alternatives
        ) {
            SourceChannelDefinitionCandidate best = candidate(
                    source, channelId, recommendedDbc, matchedIds, observedIds, matchedFrames, observedFrames);
            List<SourceChannelDefinitionCandidate> all = new ArrayList<>(service.definitionMatchingModel().allCandidates());
            all.add(best);
            all.addAll(List.of(alternatives));
            service.definitionMatchingModel().replaceCandidates(all);
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    best.sourceChannelKey(), ChannelMappingStatus.RECOMMENDED,
                    ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    best.definitionSourceId(), best.definitionDisplayName(), null, "",
                    best.observedUniqueMessageCount(), best.matchedUniqueMessageCount(), best.coverageRatio()));
        }

        private static SourceChannelDefinitionCandidate candidate(
                WorkspaceFile source,
                int channelId,
                WorkspaceFile dbc,
                long matchedIds,
                long observedIds,
                long matchedFrames,
                long observedFrames
        ) {
            return new SourceChannelDefinitionCandidate(
                    SourceChannelKey.of(source.projectSourceId(), channelId),
                    dbc.definitionSourceId(), dbc.fileName(), 100,
                    matchedIds > 0 ? DefinitionCandidateKind.HAS_OVERLAP : DefinitionCandidateKind.NO_OVERLAP,
                    observedIds, matchedIds, Math.max(0L, observedIds - matchedIds),
                    observedIds == 0 ? 0.0 : matchedIds / (double) observedIds,
                    observedFrames, matchedFrames,
                    observedFrames == 0 ? 0.0 : matchedFrames / (double) observedFrames);
        }
    }
}
