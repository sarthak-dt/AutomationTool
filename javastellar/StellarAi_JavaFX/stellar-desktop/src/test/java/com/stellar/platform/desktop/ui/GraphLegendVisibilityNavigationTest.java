package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.DesktopLogging;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphInvestigationState;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.AutomotiveSignalCanvas;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-chart legend clicks change waveform visibility only. The left Signals to Plot
 * selection stays, and a later Graph visit shows every selected signal again.
 */
class GraphLegendVisibilityNavigationTest {

    private static final String BAT1 = "A_BAT1PosRelay_Status";
    private static final String BAT2 = "A_BAT2PosRelay_Status";
    private static final String BAT3 = "A_BAT3PosRelay_Status";

    @TempDir Path temp;
    private DesktopAppService service;
    private GraphController graph;
    private GraphSignalSessionStore store;
    private Stage stage;
    private final StringBuilder report = new StringBuilder();

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        JavaFxTestSupport.runOnFx("close graph stage", () -> {
            if (graph != null) {
                graph.dispose();
                graph = null;
            }
            if (stage != null) {
                stage.close();
                stage = null;
            }
        });
        if (store != null) {
            store.close();
        }
        if (service != null) {
            service.shutdown();
        }
        DesktopLogging.closeForTests();
        Path evidence = evidenceDir();
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("state-matrix.txt"), report.toString());
    }

    @Test
    void legendClickHidesWaveformWithoutDeselectingAndNavigationShowsAllSelected() throws Exception {
        openProject("Legend Navigation");
        SignalCatalogKey bat1 = require(BAT1);
        SignalCatalogKey bat2 = require(BAT2);
        SignalCatalogKey bat3 = require(BAT3);
        List<SignalCatalogKey> selected = List.of(bat1, bat2, bat3);
        showGraph(selected);

        JavaFxTestSupport.runOnFx("overlay legend click", () -> {
            paintOverlay(selected);
            note("before-click", selected);
            shoot("01-three-signals-visible.png");
            assertEquals(3, graph.canvasForTest().sharedLegendHitCountForTest());

            assertTrue(graph.canvasForTest().clickLegendEntryForTest(bat2), "BAT2 legend hit");
            note("after-bat2-hide", selected);
            shoot("02-bat2-legend-hidden.png");
            assertSelected(selected);
            assertFalse(graph.isVisibleForTest(bat2));
            assertTrue(graph.isVisibleForTest(bat1));
            assertTrue(graph.isVisibleForTest(bat3));
            assertTrue(graph.explorerCheckboxSelectedForTest(bat2));
            assertTrue(service.graphInvestigation().isHidden(bat2));

            assertTrue(graph.canvasForTest().clickLegendEntryForTest(bat2), "BAT2 legend show");
            assertTrue(graph.isVisibleForTest(bat2));
            assertFalse(service.graphInvestigation().isHidden(bat2));
            assertSelected(selected);

            assertTrue(graph.canvasForTest().clickLegendEntryForTest(bat2));
            assertTrue(graph.canvasForTest().clickLegendEntryForTest(bat3));
            assertFalse(graph.isVisibleForTest(bat2));
            assertFalse(graph.isVisibleForTest(bat3));
            assertTrue(graph.isVisibleForTest(bat1));
            assertSelected(selected);
            assertTrue(graph.canvasForTest().clickLegendEntryForTest(bat2));
            assertTrue(graph.canvasForTest().clickLegendEntryForTest(bat3));
            assertTrue(graph.isVisibleForTest(bat2));
            assertTrue(graph.isVisibleForTest(bat3));

            assertTrue(graph.canvasForTest().clickLegendEntryForTest(bat2));
            graph.setLayoutModeForTest(true);
            graph.canvasForTest().flushDeferredPaintForTest();
            note("stacked-while-bat2-hidden", selected);
            assertEquals(GraphInvestigationState.STACKED, graph.layoutModeForTest());
            assertEquals(0, graph.canvasForTest().sharedLegendHitCountForTest());
            assertSelected(selected);
            assertFalse(graph.isVisibleForTest(bat2));
            assertTrue(graph.isVisibleForTest(bat1));

            graph.setLayoutModeForTest(false);
            paintOverlay(selected);
            graph.canvasForTest().setHiddenKeys(service.graphInvestigation().hiddenKeys());
            graph.canvasForTest().flushDeferredPaintForTest();
            note("overlay-again", selected);
            assertSelected(selected);
            assertFalse(graph.isVisibleForTest(bat2));
            assertTrue(graph.canvasForTest().clickLegendEntryForTest(bat2), "overlay legend restores BAT2");
            assertTrue(graph.isVisibleForTest(bat2));

            graph.setInspectorOpenForTest(false);
            graph.setInspectorOpenForTest(true);
            note("inspector-reopened", selected);
            assertSelected(selected);

            graph.refresh();
            note("after-refresh", selected);
            assertSelected(selected);

            paintOverlay(selected);
            assertTrue(graph.canvasForTest().clickLegendEntryForTest(bat2), "hide BAT2 before leaving Graph");
            note("before-leave", selected);
            assertSelected(selected);
            assertFalse(graph.isVisibleForTest(bat2));
        });

        List<String> beforeLeave = identities(service.signalsModel().workspaceKeys());
        JavaFxTestSupport.runOnFx("leave graph", () -> {
            graph.onHide();
            stage.close();
            stage = null;
            graph.dispose();
            graph = null;
        });
        showGraph(selected);
        JavaFxTestSupport.runOnFx("return to graph", () -> {
            graph.onShow();
            graph.bindMembershipForTest(service);
            graph.applyMembershipFromWorkspaceForTest();
            paintOverlay(selected);
            note("after-navigation", selected);
            shoot("03-navigation-back.png");
            assertEquals(beforeLeave, identities(service.signalsModel().workspaceKeys()));
            assertSelected(selected);
            assertTrue(graph.isVisibleForTest(bat1));
            assertTrue(graph.isVisibleForTest(bat2));
            assertTrue(graph.isVisibleForTest(bat3));
            assertFalse(service.graphInvestigation().isHidden(bat2));

            graph.toggleMembershipForTest(bat2, false);
            note("checkbox-off", List.of(bat1, bat3));
            assertFalse(graph.isSelectedForTest(bat2));
            assertFalse(service.signalsModel().workspaceKeys().contains(bat2));
            graph.toggleMembershipForTest(bat2, true);
            assertTrue(graph.isSelectedForTest(bat2));
            assertTrue(graph.isVisibleForTest(bat2));
        });
    }

    @Test
    void saveReopenKeepsSelectionAndDoesNotKeepLegendHide() throws Exception {
        openProject("Legend Save");
        SignalCatalogKey bat1 = require(BAT1);
        SignalCatalogKey bat2 = require(BAT2);
        SignalCatalogKey bat3 = require(BAT3);
        List<SignalCatalogKey> selected = List.of(bat1, bat2, bat3);
        showGraph(selected);
        JavaFxTestSupport.runOnFx("hide before save", () -> {
            paintOverlay(selected);
            assertTrue(graph.canvasForTest().clickLegendEntryForTest(bat2));
            note("save-while-hidden", selected);
            assertSelected(selected);
            assertFalse(graph.isVisibleForTest(bat2));
        });
        List<String> beforeSave = identities(service.signalsModel().workspaceKeys());
        service.persistGraphInvestigation();
        assertTrue(service.saveProject().saved());
        String id = service.currentProjectId();

        JavaFxTestSupport.runOnFx("close graph before project close", () -> {
            graph.dispose();
            graph = null;
            if (stage != null) {
                stage.close();
                stage = null;
            }
        });
        service.closeProject();
        assertTrue(service.openSavedProject(id).opened());
        service.signalsModel().replaceCatalog(store);
        service.restoreGraphInvestigation();
        report.append("after-reopen-restore\n");
        report.append("  workspace=").append(identities(service.signalsModel().workspaceKeys())).append('\n');
        report.append("  bat2Hidden=").append(service.graphInvestigation().isHidden(bat2)).append('\n');
        assertEquals(beforeSave, identities(service.signalsModel().workspaceKeys()));
        assertFalse(service.graphInvestigation().isHidden(bat2),
                "a legend hide must not come back as saved visibility");

        showGraph(service.signalsModel().workspaceKeys());
        JavaFxTestSupport.runOnFx("graph after reopen", () -> {
            graph.onShow();
            graph.bindMembershipForTest(service);
            graph.applyMembershipFromWorkspaceForTest();
            paintOverlay(service.signalsModel().workspaceKeys());
            note("graph-after-reopen", service.signalsModel().workspaceKeys());
            shoot("04-save-reopen.png");
            assertEquals(beforeSave, identities(graph.selectedMembershipForTest()));
            assertTrue(graph.isVisibleForTest(bat1));
            assertTrue(graph.isVisibleForTest(bat2));
            assertTrue(graph.isVisibleForTest(bat3));
        });
    }

    private void openProject(String name) {
        System.setProperty("stellarai.home", temp.resolve("home-" + name.hashCode()).toAbsolutePath().toString());
        service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve(name.hashCode() + ".db")));
        service.newProject(name);
        store = GraphSignalSessionStore.create("relay.mf4", "veh.dbc");
        store.accept(List.of(
                sample(BAT1, 1, 0L, 0.0),
                sample(BAT1, 1, 1_000_000_000L, 1.0),
                sample(BAT2, 2, 0L, 0.0),
                sample(BAT2, 2, 1_000_000_000L, 1.0),
                sample(BAT3, 3, 0L, 0.0),
                sample(BAT3, 3, 1_000_000_000L, 1.0)
        ));
        service.signalsModel().replaceCatalog(store);
    }

    private void showGraph(List<SignalCatalogKey> selected) throws Exception {
        JavaFxTestSupport.runOnFx("show graph", () -> {
            FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
            Parent root = loader.load();
            graph = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, 1366, 768));
            stage.show();
            graph.setAppService(service, null);
            graph.bindMembershipForTest(service);
            service.signalsModel().addToWorkspace(selected);
            graph.applyMembershipFromWorkspaceForTest();
            graph.setViewportForTest(0L, 2_000_000_000L);
            root.applyCss();
            root.layout();
        });
    }

    private void paintOverlay(List<SignalCatalogKey> keys) throws Exception {
        graph.setLayoutModeForTest(false);
        List<GraphRenderSeries> series = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            SignalCatalogKey key = keys.get(i);
            double level = i + 1.0;
            GraphBucket bucket = new GraphBucket(
                    100_000_000L, level,
                    100_000_000L, level,
                    1_000_000_000L, level + 1.0,
                    1_000_000_000L, level + 1.0,
                    2);
            series.add(new GraphRenderSeries(key, "state", 2, false, 0L, 2_000_000_000L, List.of(bucket)));
        }
        Field rendered = GraphController.class.getDeclaredField("lastRendered");
        rendered.setAccessible(true);
        rendered.set(graph, series);
        AutomotiveSignalCanvas canvas = graph.canvasForTest();
        canvas.setSharedViewport(0L, 2_000_000_000L);
        canvas.setHiddenKeys(service.graphInvestigation().hiddenKeys());
        // Set the series last. A width change asks the controller for placeholders and
        // would replace this frame before the legend is hit-tested.
        canvas.setSeries(series);
        canvas.flushDeferredPaintForTest();
    }

    private void assertSelected(List<SignalCatalogKey> expected) {
        assertEquals(expected, service.signalsModel().workspaceKeys());
        assertEquals(expected, graph.selectedMembershipForTest());
        for (SignalCatalogKey key : expected) {
            assertTrue(graph.explorerCheckboxSelectedForTest(key), key.signalName());
        }
    }

    private void note(String phase, List<SignalCatalogKey> expectedSelected) {
        report.append(phase).append('\n');
        report.append("  workspace=").append(identities(service.signalsModel().workspaceKeys())).append('\n');
        if (graph == null) {
            report.append("  controller=<none>\n");
            return;
        }
        report.append("  controller=").append(identities(graph.selectedMembershipForTest())).append('\n');
        for (SignalCatalogKey key : expectedSelected) {
            report.append("  ")
                    .append(key.signalName())
                    .append(" selected=").append(graph.isSelectedForTest(key))
                    .append(" visible=").append(graph.isVisibleForTest(key))
                    .append(" hidden=").append(service.graphInvestigation().isHidden(key))
                    .append(" checkbox=").append(graph.explorerCheckboxSelectedForTest(key))
                    .append('\n');
        }
        report.append("  layout=").append(graph.layoutModeForTest()).append('\n');
    }

    private void shoot(String name) throws Exception {
        Path dir = evidenceDir();
        Files.createDirectories(dir);
        WritableImage image = stage.getScene().snapshot(null);
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", dir.resolve(name).toFile());
    }

    private static Path evidenceDir() {
        return Path.of(System.getProperty("java.io.tmpdir"), "graph-legend-evidence");
    }

    private SignalCatalogKey require(String name) {
        return service.signalsModel().catalog().entries().stream()
                .map(SignalCatalogEntry::key)
                .filter(key -> name.equals(key.signalName()))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> identities(List<SignalCatalogKey> keys) {
        List<String> lines = new ArrayList<>();
        for (SignalCatalogKey key : keys) {
            lines.add(key.signalName()
                    + "|" + key.sourceType()
                    + "|" + key.messageName()
                    + "|" + key.canId()
                    + "|" + key.channel()
                    + "|" + key.sourceFile()
                    + "|" + key.sourceDatabase());
        }
        return lines;
    }

    private static CanonicalSignal sample(String name, int channel, long timestamp, double value) {
        return new CanonicalSignal(
                name, "BAT_RELAY", timestamp, 0x100L + channel, channel, value, (long) value, "", null,
                SourceReference.ofLine(channel));
    }

}
