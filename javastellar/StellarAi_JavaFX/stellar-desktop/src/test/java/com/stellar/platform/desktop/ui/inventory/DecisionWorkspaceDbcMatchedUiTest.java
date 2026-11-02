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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionWorkspaceDbcMatchedUiTest {

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
    void ch3Observed31Matched31Displays31AndAllChannelsIsDash() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.ch3FullMatch();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.layout();
            workspace.navigatorForTest().applyCss();
            workspace.navigatorForTest().layout();

            assertEquals("31", workspace.navIdsTextForTest(fx.fileA.projectSourceId(), 3));
            assertEquals("31", workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 3));
            Label matched = workspace.navRenderedMatchedLabelForTest(fx.fileA.projectSourceId(), 3);
            assertTrue(matched != null && matched.isVisible() && matched.isManaged(),
                    "DBC Matched must be a rendered row cell, not tooltip-only");
            assertEquals("31", matched.getText());
            assertTrue(matched.getWidth() > 8 || matched.prefWidth(-1) >= 80,
                    "DBC Matched column must occupy layout width");
            Label idsHead = workspace.navIdsHeadForTest();
            Label matchedHead = workspace.navMatchedHeadForTest();
            assertEquals("CAN IDs", idsHead.getText());
            assertEquals("DBC Matched", matchedHead.getText());
            assertEquals(idsHead.getPrefWidth(), matched.getParent() instanceof javafx.scene.layout.HBox row
                    ? ((javafx.scene.control.Label) row.getChildren().get(1)).getPrefWidth()
                    : idsHead.getPrefWidth(), 0.1);
            assertEquals(matchedHead.getPrefWidth(), matched.getPrefWidth(), 0.1);
            assertEquals("\u2014", workspace.navAllChannelsMatchedTextForTest(fx.fileA.projectSourceId()));
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void sameChannelNumberOnTwoSourcesShowsIndependentMatchedCounts() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.twoSourcesSameChannel();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();

            assertEquals("6", workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 1));
            assertEquals("2", workspace.navDbcMatchedTextForTest(fx.fileB.projectSourceId(), 1));
            assertNotEquals(
                    workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 1),
                    workspace.navDbcMatchedTextForTest(fx.fileB.projectSourceId(), 1));
            assertEquals("\u2014", workspace.navAllChannelsMatchedTextForTest(fx.fileA.projectSourceId()));
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void recommendedCountShowsBeforeConfirmAndDropdownUpdatesOnlyThatChannel() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.recommendedWithAlternate();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.fileA.projectSourceId(), 2);
            workspace.refreshFromModel();

            assertEquals("9", workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 2));
            assertEquals("1", workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 1));
            assertEquals(ChannelMappingStatus.RECOMMENDED,
                    fx.service.channelMapping(SourceChannelKey.of(fx.fileA.projectSourceId(), 2))
                            .orElseThrow().status());
            assertTrue(workspace.centerMatchTextForTest().startsWith("9 / 56"),
                    workspace.centerMatchTextForTest());
            assertTrue(workspace.centerMatchTextForTest().contains("9 / 56"),
                    "center matched/observed must use the same comparison as the left column");

            workspace.candidateStripForTest().selectIndexForTest(1);
            workspace.refreshEnrichmentOnly();
            assertEquals("22", workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 2));
            assertEquals("1", workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 1),
                    "DBC preview must not rewrite another channel's matched count");
            assertEquals(ChannelMappingStatus.RECOMMENDED,
                    fx.service.channelMapping(SourceChannelKey.of(fx.fileA.projectSourceId(), 2))
                            .orElseThrow().status());
            assertTrue(workspace.centerMatchTextForTest().startsWith("22 / 56"),
                    workspace.centerMatchTextForTest());

            workspace.actionBarForTest().confirmButtonForTest().fire();
            workspace.refreshEnrichmentOnly();
            assertEquals(ChannelMappingStatus.MANUAL,
                    fx.service.channelMapping(SourceChannelKey.of(fx.fileA.projectSourceId(), 2))
                            .orElseThrow().status());
            assertEquals("22", workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 2));
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void confirmedUsesOwnComparisonZeroIsZeroAndMissingIsDash() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.confirmedZeroAndMissing();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();

            assertEquals("3", workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 1));
            assertEquals("0", workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 2));
            assertEquals("\u2014", workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 3));
            assertEquals("\u2014", workspace.navAllChannelsMatchedTextForTest(fx.fileA.projectSourceId()));
            dispose(stage);
        });
    }

    @Test
    @Timeout(45)
    void centerAndLeftReadTheSameComparisonNotMappingCounts() throws Exception {
        runOnFx(() -> {
            Fixture fx = Fixture.comparisonOverridesStaleMapping();
            SourceInventoryWorkspace workspace = fx.bindWorkspace();
            Stage stage = show(workspace, 1600, 900);
            workspace.refreshFromModel();
            workspace.modelForTest().selectChannel(fx.fileA.projectSourceId(), 2);
            workspace.refreshFromModel();

            assertEquals("9", workspace.navDbcMatchedTextForTest(fx.fileA.projectSourceId(), 2));
            assertTrue(workspace.centerMatchTextForTest().startsWith("9 / 56"),
                    workspace.centerMatchTextForTest());
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

        private Fixture(DesktopAppService service, WorkspaceFile fileA, WorkspaceFile fileB) {
            this.service = service;
            this.fileA = fileA;
            this.fileB = fileB;
        }

        SourceInventoryWorkspace bindWorkspace() {
            SourceInventoryWorkspace workspace = new SourceInventoryWorkspace();
            workspace.bind(service, () -> { });
            workspace.showDetailedMapping();
            return workspace;
        }

        static Fixture ch3FullMatch() {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile fileA = service.workspace().addFile(
                    new WorkspaceFile(Path.of("src_ch3.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 10));
            service.sourceInventoryModel().put(fileA.id(), inventory(fileA, List.of(channel(3, 31)), 31, 31));
            DefinitionSourceId defId = DefinitionSourceId.generate();
            service.definitionMatchingCoordinator().registerDefinition(defId, "full.dbc", emptyDbc());
            SourceChannelKey key = SourceChannelKey.of(fileA.projectSourceId(), 3);
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    key, ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    defId, "full.dbc", null, "", 31, 31, 1.0));
            service.definitionMatchingModel().replaceComparisons(List.of(comparison(key, defId, "full.dbc", 31, 31)));
            return new Fixture(service, fileA, null);
        }

        static Fixture twoSourcesSameChannel() {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile fileA = service.workspace().addFile(
                    new WorkspaceFile(Path.of("src_a.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 10));
            WorkspaceFile fileB = service.workspace().addFile(
                    new WorkspaceFile(Path.of("src_b.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 10));
            service.sourceInventoryModel().put(fileA.id(), inventory(fileA, List.of(channel(1, 10)), 10, 10));
            service.sourceInventoryModel().put(fileB.id(), inventory(fileB, List.of(channel(1, 10)), 10, 10));
            DefinitionSourceId defId = DefinitionSourceId.generate();
            service.definitionMatchingCoordinator().registerDefinition(defId, "shared.dbc", emptyDbc());
            SourceChannelKey keyA = SourceChannelKey.of(fileA.projectSourceId(), 1);
            SourceChannelKey keyB = SourceChannelKey.of(fileB.projectSourceId(), 1);
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    keyA, ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    defId, "shared.dbc", null, "", 10, 6, 0.6));
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    keyB, ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    defId, "shared.dbc", null, "", 10, 2, 0.2));
            service.definitionMatchingModel().replaceComparisons(List.of(
                    comparison(keyA, defId, "shared.dbc", 10, 6),
                    comparison(keyB, defId, "shared.dbc", 10, 2)));
            return new Fixture(service, fileA, fileB);
        }

        static Fixture recommendedWithAlternate() {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile fileA = service.workspace().addFile(
                    new WorkspaceFile(Path.of("preview.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 10));
            service.sourceInventoryModel().put(fileA.id(),
                    inventory(fileA, List.of(channel(1, 10), channel(2, 56)), 66, 66));
            DefinitionSourceId recId = DefinitionSourceId.generate();
            DefinitionSourceId altId = DefinitionSourceId.generate();
            service.definitionMatchingCoordinator().registerDefinition(recId, "3.dbc", emptyDbc());
            service.definitionMatchingCoordinator().registerDefinition(altId, "1.dbc", emptyDbc());
            SourceChannelKey key1 = SourceChannelKey.of(fileA.projectSourceId(), 1);
            SourceChannelKey key2 = SourceChannelKey.of(fileA.projectSourceId(), 2);
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    key1, ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    recId, "3.dbc", null, "", 10, 1, 0.1));
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    key2, ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    recId, "3.dbc", null, "", 56, 9, 9.0 / 56.0));
            service.definitionMatchingModel().replaceCandidates(List.of(
                    candidate(key2, recId, "3.dbc", 56, 9),
                    candidate(key2, altId, "1.dbc", 56, 22),
                    candidate(key1, recId, "3.dbc", 10, 1)));
            service.definitionMatchingModel().replaceComparisons(List.of(
                    comparison(key2, recId, "3.dbc", 56, 9),
                    comparison(key2, altId, "1.dbc", 56, 22),
                    comparison(key1, recId, "3.dbc", 10, 1)));
            return new Fixture(service, fileA, null);
        }

        static Fixture confirmedZeroAndMissing() {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile fileA = service.workspace().addFile(
                    new WorkspaceFile(Path.of("confirmed.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 10));
            service.sourceInventoryModel().put(fileA.id(),
                    inventory(fileA, List.of(channel(1, 8), channel(2, 10), channel(3, 4)), 22, 22));
            DefinitionSourceId recId = DefinitionSourceId.generate();
            DefinitionSourceId mappedId = DefinitionSourceId.generate();
            DefinitionSourceId zeroId = DefinitionSourceId.generate();
            service.definitionMatchingCoordinator().registerDefinition(recId, "rec.dbc", emptyDbc());
            service.definitionMatchingCoordinator().registerDefinition(mappedId, "manual.dbc", emptyDbc());
            service.definitionMatchingCoordinator().registerDefinition(zeroId, "zero.dbc", emptyDbc());
            SourceChannelKey key1 = SourceChannelKey.of(fileA.projectSourceId(), 1);
            SourceChannelKey key2 = SourceChannelKey.of(fileA.projectSourceId(), 2);
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    key1, ChannelMappingStatus.CONFIRMED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    recId, "rec.dbc", mappedId, "manual.dbc", 8, 8, 1.0));
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    key2, ChannelMappingStatus.CONFIRMED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    recId, "rec.dbc", zeroId, "zero.dbc", 10, 0, 0.0));
            service.definitionMatchingModel().replaceComparisons(List.of(
                    comparison(key1, mappedId, "manual.dbc", 8, 3),
                    comparison(key1, recId, "rec.dbc", 8, 8),
                    comparison(key2, zeroId, "zero.dbc", 10, 0)));
            return new Fixture(service, fileA, null);
        }

        static Fixture comparisonOverridesStaleMapping() {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile fileA = service.workspace().addFile(
                    new WorkspaceFile(Path.of("stale.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 10));
            service.sourceInventoryModel().put(fileA.id(), inventory(fileA, List.of(channel(2, 56)), 56, 56));
            DefinitionSourceId defId = DefinitionSourceId.generate();
            service.definitionMatchingCoordinator().registerDefinition(defId, "3.dbc", emptyDbc());
            SourceChannelKey key = SourceChannelKey.of(fileA.projectSourceId(), 2);
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    key, ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    defId, "3.dbc", null, "", 56, 99, 1.0));
            service.definitionMatchingModel().replaceComparisons(List.of(
                    comparison(key, defId, "3.dbc", 56, 9)));
            return new Fixture(service, fileA, null);
        }
    }
}
