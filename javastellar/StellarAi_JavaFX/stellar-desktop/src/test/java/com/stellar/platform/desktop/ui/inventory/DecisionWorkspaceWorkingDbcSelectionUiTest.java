package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionCandidateKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate;
import com.stellar.platform.desktop.project.SourceChannelDefinitionComparison;
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

class DecisionWorkspaceWorkingDbcSelectionUiTest {

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
    void workingSelectionSurvivesChannelSwitchAndKeepsTableAligned() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.fileA.projectSourceId(), 1);
            workspace.refreshFromModel();

            assertEquals("rec.dbc", workspace.candidateStripForTest().selectedDisplayNameForTest());
            assertEquals("RECOMMENDED", workspace.candidateStripForTest().mappingStateForTest());
            assertTrue(workspace.centerMatchTextForTest().startsWith("25 / 67"),
                    workspace.centerMatchTextForTest());
            assertEquals("25", workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 1));

            workspace.candidateStripForTest().selectIndexForTest(1);
            workspace.refreshEnrichmentOnly();
            assertEquals("alt.dbc", workspace.candidateStripForTest().selectedDisplayNameForTest());
            assertEquals("SELECTED", workspace.candidateStripForTest().mappingStateForTest());
            assertEquals("Confirm Selected DBC", workspace.actionBarForTest().confirmLabelForTest());
            assertTrue(workspace.centerMatchTextForTest().startsWith("18 / 67"),
                    workspace.centerMatchTextForTest());
            assertEquals("18", workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 1));
            assertTrue(workspace.candidateStripForTest().optionNamesForTest().contains("rec.dbc"));
            assertTrue(workspace.candidateStripForTest().useRecommendedVisibleForTest());
            assertEquals(ChannelMappingStatus.RECOMMENDED,
                    fx.service.channelMapping(fx.keyA1).orElseThrow().status());

            workspace.modelForTest().selectChannel(fx.fileA.projectSourceId(), 2);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.fileA.projectSourceId(), 3);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.fileA.projectSourceId(), 1);
            workspace.refreshFromModel();

            assertEquals("alt.dbc", workspace.candidateStripForTest().selectedDisplayNameForTest());
            assertEquals("SELECTED", workspace.candidateStripForTest().mappingStateForTest());
            assertTrue(workspace.centerMatchTextForTest().startsWith("18 / 67"),
                    workspace.centerMatchTextForTest());
            assertEquals("18", workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 1));
            assertEquals(fx.altId, fx.service.workingDbcSelection(fx.keyA1).orElseThrow());
            assertEquals(fx.recId, fx.service.channelMapping(fx.keyA1).orElseThrow()
                    .recommendedDefinitionId().orElseThrow());
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void useRecommendedRestoresRecommendationWithoutConfirming() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.fileA.projectSourceId(), 1);
            workspace.refreshFromModel();
            workspace.candidateStripForTest().selectIndexForTest(1);
            workspace.refreshEnrichmentOnly();
            workspace.candidateStripForTest().fireUseRecommendedForTest();
            workspace.refreshEnrichmentOnly();

            assertEquals("rec.dbc", workspace.candidateStripForTest().selectedDisplayNameForTest());
            assertEquals("RECOMMENDED", workspace.candidateStripForTest().mappingStateForTest());
            assertEquals("Confirm Recommended", workspace.actionBarForTest().confirmLabelForTest());
            assertTrue(workspace.centerMatchTextForTest().startsWith("25 / 67"),
                    workspace.centerMatchTextForTest());
            assertEquals("25", workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 1));
            assertEquals(ChannelMappingStatus.RECOMMENDED,
                    fx.service.channelMapping(fx.keyA1).orElseThrow().status());
            assertFalse(workspace.actionBarForTest().acceptedVisibleForTest());
            assertFalse(workspace.candidateStripForTest().useRecommendedVisibleForTest());
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void confirmSelectedPersistsUsingExistingConfirmationSemantics() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.fileA.projectSourceId(), 1);
            workspace.refreshFromModel();
            workspace.candidateStripForTest().selectIndexForTest(1);
            workspace.refreshEnrichmentOnly();
            workspace.actionBarForTest().confirmButtonForTest().fire();
            workspace.refreshEnrichmentOnly();

            assertEquals(ChannelMappingStatus.MANUAL,
                    fx.service.channelMapping(fx.keyA1).orElseThrow().status());
            assertEquals(fx.altId, fx.service.channelMapping(fx.keyA1).orElseThrow()
                    .mappedDefinitionId().orElseThrow());
            assertEquals("CONFIRMED", workspace.candidateStripForTest().mappingStateForTest());
            assertTrue(workspace.actionBarForTest().acceptedVisibleForTest());
            assertEquals("alt.dbc", workspace.candidateStripForTest().selectedDisplayNameForTest());

            workspace.modelForTest().selectChannel(fx.fileA.projectSourceId(), 2);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.fileA.projectSourceId(), 1);
            workspace.refreshFromModel();
            assertEquals("alt.dbc", workspace.candidateStripForTest().selectedDisplayNameForTest());
            assertEquals("CONFIRMED", workspace.candidateStripForTest().mappingStateForTest());
            assertEquals(ChannelMappingStatus.MANUAL,
                    fx.service.channelMapping(fx.keyA1).orElseThrow().status());
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void twoSourcesWithSameChannelNumberKeepIndependentWorkingChoices() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.fileA.projectSourceId(), 1);
            workspace.refreshFromModel();
            workspace.candidateStripForTest().selectIndexForTest(1);
            workspace.refreshEnrichmentOnly();
            assertEquals("alt.dbc", workspace.candidateStripForTest().selectedDisplayNameForTest());
            assertEquals("18", workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 1));

            workspace.modelForTest().selectChannel(fx.fileB.projectSourceId(), 1);
            workspace.refreshFromModel();
            assertEquals("srcb-rec.dbc", workspace.candidateStripForTest().selectedDisplayNameForTest());
            assertEquals("RECOMMENDED", workspace.candidateStripForTest().mappingStateForTest());
            assertEquals("6", workspace.navDbcMatchedTextForTest(fx.fileB.projectSourceId(), 1));
            workspace.candidateStripForTest().selectIndexForTest(1);
            workspace.refreshEnrichmentOnly();
            assertEquals("srcb-alt.dbc", workspace.candidateStripForTest().selectedDisplayNameForTest());
            assertEquals("2", workspace.navDbcMatchedTextForTest(fx.fileB.projectSourceId(), 1));

            workspace.modelForTest().selectChannel(fx.fileA.projectSourceId(), 1);
            workspace.refreshFromModel();
            assertEquals("alt.dbc", workspace.candidateStripForTest().selectedDisplayNameForTest());
            assertEquals("18", workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 1));
            assertEquals("2", workspace.navDbcMatchedTextForTest(fx.fileB.projectSourceId(), 1));
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void confirmedPreviewDoesNotOverwriteMappingUntilExplicitConfirm() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.fileA.projectSourceId(), 1);
            workspace.refreshFromModel();
            workspace.candidateStripForTest().selectIndexForTest(1);
            workspace.refreshEnrichmentOnly();
            workspace.actionBarForTest().confirmButtonForTest().fire();
            workspace.refreshEnrichmentOnly();
            assertEquals(fx.altId, fx.service.channelMapping(fx.keyA1).orElseThrow()
                    .mappedDefinitionId().orElseThrow());

            workspace.candidateStripForTest().selectIndexForTest(2);
            workspace.refreshEnrichmentOnly();
            assertEquals("other.dbc", workspace.candidateStripForTest().selectedDisplayNameForTest());
            assertEquals("SELECTED", workspace.candidateStripForTest().mappingStateForTest());
            assertEquals(ChannelMappingStatus.MANUAL,
                    fx.service.channelMapping(fx.keyA1).orElseThrow().status());
            assertEquals(fx.altId, fx.service.channelMapping(fx.keyA1).orElseThrow()
                    .mappedDefinitionId().orElseThrow());
            assertEquals("6", workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 1));
            assertTrue(workspace.centerMatchTextForTest().startsWith("6 / 67"),
                    workspace.centerMatchTextForTest());
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void stateFilterRecommendedSelectedConfirmedAndNeedsDecision() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            assertEquals("All", workspace.stateFilterForTest().getValue());
            assertTrue(workspace.stateFilterForTest().getItems().containsAll(List.of(
                    "All", "Needs Decision", "Recommended", "Selected", "Confirmed", "Needs Attention")));

            workspace.modelForTest().selectChannel(fx.fileA.projectSourceId(), 1);
            workspace.refreshFromModel();
            workspace.candidateStripForTest().selectIndexForTest(1);
            workspace.refreshEnrichmentOnly();
            workspace.refreshFromModel();

            workspace.stateFilterForTest().getSelectionModel().select("Selected");
            assertEquals("18", workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 1));
            assertEquals(1, countSourceChannels(workspace, fx.fileA.projectSourceId()));

            workspace.stateFilterForTest().getSelectionModel().select("Recommended");
            assertTrue(countSourceChannels(workspace, fx.fileA.projectSourceId()) >= 2);
            assertEquals(null, workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 1));

            workspace.stateFilterForTest().getSelectionModel().select("Needs Decision");
            assertTrue(workspace.visibleNavChannelCountForTest() >= 3);

            workspace.stateFilterForTest().getSelectionModel().select("All");
            workspace.modelForTest().selectChannel(fx.fileA.projectSourceId(), 1);
            workspace.refreshFromModel();
            workspace.actionBarForTest().confirmButtonForTest().fire();
            workspace.refreshEnrichmentOnly();
            workspace.refreshFromModel();
            workspace.stateFilterForTest().getSelectionModel().select("Confirmed");
            assertEquals("18", workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 1));
            assertEquals(1, countSourceChannels(workspace, fx.fileA.projectSourceId()));

            workspace.stateFilterForTest().getSelectionModel().select("Needs Attention");
            assertEquals("\u2014", workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 4));
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void newProjectClearsTransientWorkingSelections() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.create();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.fileA.projectSourceId(), 1);
            workspace.refreshFromModel();
            workspace.candidateStripForTest().selectIndexForTest(1);
            workspace.refreshEnrichmentOnly();
            assertEquals(fx.altId, fx.service.workingDbcSelection(fx.keyA1).orElseThrow());

            fx.service.newProject("Other");
            assertTrue(fx.service.workingDbcSelection(fx.keyA1).isEmpty());
            dispose(stage);
        });
    }

    private static int countSourceChannels(
            SourceInventoryWorkspace workspace,
            com.stellar.platform.desktop.project.ProjectSourceId sourceId
    ) {
        int count = 0;
        for (int ch = 1; ch <= 4; ch++) {
            if (workspace.navDbcMatchedTextForTest(sourceId, ch) != null) {
                count++;
            }
        }
        return count;
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

    private static DbcDatabase emptyDbc() {
        return new DbcDatabase("", List.of(), List.of(), List.of());
    }

    private static CanSourceInventory inventory(
            WorkspaceFile file,
            List<CanChannelInventory> channels,
            long frames,
            long unique
    ) {
        return new CanSourceInventory(
                file.path(), file.fileName(), MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, channels.size(), frames, unique, true, channels, List.of());
    }

    private static CanChannelInventory channel(int id, int uniqueIds) {
        List<CanMessageInventory> messages = new ArrayList<>();
        for (int i = 0; i < uniqueIds; i++) {
            messages.add(new CanMessageInventory(new CanMessageKey(id, 0x100 + i, false), 1, 8, 8, false));
        }
        return new CanChannelInventory(id, "Channel " + id, uniqueIds, uniqueIds, uniqueIds, 0, 0, messages);
    }

    private static SourceChannelDefinitionComparison comparison(
            SourceChannelKey key,
            DefinitionSourceId defId,
            String name,
            long observed,
            long matched
    ) {
        double coverage = observed == 0 ? 0.0 : (double) matched / observed;
        return new SourceChannelDefinitionComparison(
                key, defId, name, 4, DefinitionCandidateKind.HAS_OVERLAP,
                observed, matched, coverage, List.of());
    }

    private static SourceChannelDefinitionCandidate candidate(
            SourceChannelKey key,
            DefinitionSourceId defId,
            String name,
            long observed,
            long matched
    ) {
        double coverage = observed == 0 ? 0.0 : (double) matched / observed;
        return new SourceChannelDefinitionCandidate(
                key, defId, name, 4, DefinitionCandidateKind.HAS_OVERLAP,
                observed, matched, Math.max(0, observed - matched), coverage);
    }

    private static final class Fixture {
        final DesktopAppService service;
        final WorkspaceFile fileA;
        final WorkspaceFile fileB;
        final DefinitionSourceId recId;
        final DefinitionSourceId altId;
        final DefinitionSourceId otherId;
        final SourceChannelKey keyA1;

        private Fixture(
                DesktopAppService service,
                WorkspaceFile fileA,
                WorkspaceFile fileB,
                DefinitionSourceId recId,
                DefinitionSourceId altId,
                DefinitionSourceId otherId
        ) {
            this.service = service;
            this.fileA = fileA;
            this.fileB = fileB;
            this.recId = recId;
            this.altId = altId;
            this.otherId = otherId;
            this.keyA1 = SourceChannelKey.of(fileA.projectSourceId(), 1);
        }

        SourceInventoryWorkspace bindWorkspace() {
            SourceInventoryWorkspace workspace = new SourceInventoryWorkspace();
            workspace.bind(service, () -> { });
            return workspace;
        }

        static Fixture create() {
            try {
                System.setProperty("stellarai.home",
                        java.nio.file.Files.createTempDirectory("stellar-home-").toAbsolutePath().toString());
            } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile fileA = service.workspace().addFile(
                    new WorkspaceFile(Path.of("src_a.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 10));
            WorkspaceFile fileB = service.workspace().addFile(
                    new WorkspaceFile(Path.of("src_b.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 10));
            service.sourceInventoryModel().put(fileA.id(), inventory(fileA, List.of(
                    channel(1, 67), channel(2, 10), channel(3, 5), channel(4, 4)), 86, 86));
            service.sourceInventoryModel().put(fileB.id(), inventory(fileB, List.of(channel(1, 10)), 10, 10));

            DefinitionSourceId recId = DefinitionSourceId.generate();
            DefinitionSourceId altId = DefinitionSourceId.generate();
            DefinitionSourceId otherId = DefinitionSourceId.generate();
            DefinitionSourceId srcBRec = DefinitionSourceId.generate();
            DefinitionSourceId srcBAlt = DefinitionSourceId.generate();
            service.definitionMatchingCoordinator().registerDefinition(recId, "rec.dbc", emptyDbc());
            service.definitionMatchingCoordinator().registerDefinition(altId, "alt.dbc", emptyDbc());
            service.definitionMatchingCoordinator().registerDefinition(otherId, "other.dbc", emptyDbc());
            service.definitionMatchingCoordinator().registerDefinition(srcBRec, "srcb-rec.dbc", emptyDbc());
            service.definitionMatchingCoordinator().registerDefinition(srcBAlt, "srcb-alt.dbc", emptyDbc());

            SourceChannelKey a1 = SourceChannelKey.of(fileA.projectSourceId(), 1);
            SourceChannelKey a2 = SourceChannelKey.of(fileA.projectSourceId(), 2);
            SourceChannelKey a3 = SourceChannelKey.of(fileA.projectSourceId(), 3);
            SourceChannelKey a4 = SourceChannelKey.of(fileA.projectSourceId(), 4);
            SourceChannelKey b1 = SourceChannelKey.of(fileB.projectSourceId(), 1);
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    a1, ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    recId, "rec.dbc", null, "", 67, 25, 25.0 / 67.0));
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    a2, ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    recId, "rec.dbc", null, "", 10, 6, 0.6));
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    a3, ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    recId, "rec.dbc", null, "", 5, 2, 0.4));
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    a4, ChannelMappingStatus.AMBIGUOUS, ChannelRecommendationKind.AMBIGUOUS,
                    null, "", null, "", 4, 0, 0.0));
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    b1, ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    srcBRec, "srcb-rec.dbc", null, "", 10, 6, 0.6));
            service.definitionMatchingModel().replaceCandidates(List.of(
                    candidate(a1, recId, "rec.dbc", 67, 25),
                    candidate(a1, altId, "alt.dbc", 67, 18),
                    candidate(a1, otherId, "other.dbc", 67, 6),
                    candidate(a2, recId, "rec.dbc", 10, 6),
                    candidate(a3, recId, "rec.dbc", 5, 2),
                    candidate(b1, srcBRec, "srcb-rec.dbc", 10, 6),
                    candidate(b1, srcBAlt, "srcb-alt.dbc", 10, 2)));
            service.definitionMatchingModel().replaceComparisons(List.of(
                    comparison(a1, recId, "rec.dbc", 67, 25),
                    comparison(a1, altId, "alt.dbc", 67, 18),
                    comparison(a1, otherId, "other.dbc", 67, 6),
                    comparison(a2, recId, "rec.dbc", 10, 6),
                    comparison(a3, recId, "rec.dbc", 5, 2),
                    comparison(b1, srcBRec, "srcb-rec.dbc", 10, 6),
                    comparison(b1, srcBAlt, "srcb-alt.dbc", 10, 2)));
            return new Fixture(service, fileA, fileB, recId, altId, otherId);
        }
    }
}
