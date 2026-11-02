package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphInvestigationState;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plot Settings is an approved removal. This checks the waveform column, interaction bar,
 * layout modes, navigation and viewport tools. It does not save the project.
 */
@Timeout(value = 20, unit = TimeUnit.MINUTES)
class GraphPlotSettingsRemovalLiveTest {

    private static final Path EVIDENCE = Path.of("D:\\DesktopApp\\DriveT\\docs\\qa-handoff\\plot-settings-removal-"
            + System.getProperty("prism.order", "default") + ".txt");
    private static final List<String> NOTES = new ArrayList<>();
    private static final List<String> FAILURES = new ArrayList<>();
    private static final CopyOnWriteArrayList<Throwable> FX_ERRORS = new CopyOnWriteArrayList<>();
    private static DesktopAppService service;
    private static Scene scene;
    private static GraphController graph;
    private static Stage stage;
    private static List<String> colorsAtTwo = List.of();

    @AfterAll
    static void shutdown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void graphWorksWithoutPlotSettings() throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> FX_ERRORS.add(error));
        try {
            Path home = Path.of(System.getProperty("java.io.tmpdir"), "stellar-accuracy", "m2-73-home");
            Assumptions.assumeTrue(Files.isRegularFile(home.resolve("meta.db")), "isolated M2-73 project is not present");
            System.setProperty("stellarai.test.isolate", "true");
            System.setProperty("stellarai.home", home.toString());
            note("prism.order=" + System.getProperty("prism.order"));
            note("product=" + DesktopVersion.displayVersion());
            note("save=not performed");
            service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(home.resolve("meta.db")));
            var project = service.metadataStore().listProjects().stream()
                    .filter(row -> "M2-73".equals(row.name()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(service.openSavedProject(project.projectId()).opened());
            replaceWorkspace(2);
            JavaFxTestSupport.startToolkit();
            launch();
            openGraphs(2);
            assertChrome("2-stacked");
            colorsAtTwo = chipColors();
            note("colors=" + colorsAtTwo);
            if (colorsAtTwo.size() < 2 || colorsAtTwo.get(0).equals(colorsAtTwo.get(1))) {
                FAILURES.add("first two signal colours are not distinct: " + colorsAtTwo);
            }
            exerciseViewport();
            if (!colorsAtTwo.equals(chipColors())) {
                FAILURES.add("colours changed after zoom pan fit reset: " + chipColors());
            }
            fx("overlay", () -> graph.setLayoutModeForTest(false));
            awaitReady(2, false);
            assertChrome("2-overlay");
            if (!colorsAtTwo.equals(chipColors())) {
                FAILURES.add("colours changed in overlay: " + chipColors());
            }
            visitTabs();
            replaceWorkspace(24);
            awaitReady(24, true);
            assertChrome("24-stacked");
            fx("24 overlay", () -> graph.setLayoutModeForTest(false));
            awaitReady(24, false);
            assertChrome("24-overlay");
            replaceWorkspace(30);
            awaitReady(30, true);
            assertChrome("30-stacked");
            fx("30 overlay", () -> graph.setLayoutModeForTest(false));
            awaitReady(30, false);
            assertChrome("30-overlay");
            note("fxErrors=" + FX_ERRORS.size());
            if (!FX_ERRORS.isEmpty()) {
                FAILURES.add("FX exception " + FX_ERRORS.getFirst());
            }
        } catch (Throwable thrown) {
            note("ABORTED " + thrown);
            FAILURES.add("aborted: " + thrown.getMessage());
            thrown.printStackTrace(System.out);
        } finally {
            Files.writeString(EVIDENCE, String.join(System.lineSeparator(), NOTES));
            closeStage();
        }
        assertTrue(FAILURES.isEmpty(), String.join("\n", FAILURES));
    }

    private static void exerciseViewport() throws Exception {
        long start = value(() -> graph.viewportStartForTest());
        long end = value(() -> graph.viewportEndForTest());
        long span = end - start;
        fx("zoom", () -> graph.zoomInForTest());
        awaitQuiet("zoom");
        long zoomed = value(() -> graph.viewportEndForTest()) - value(() -> graph.viewportStartForTest());
        note("zoom span " + span + " -> " + zoomed);
        if (zoomed >= span) {
            FAILURES.add("zoom in did not shrink the viewport");
        }
        long beforePan = value(() -> graph.viewportStartForTest());
        fx("pan", () -> graph.panRightForTest());
        awaitQuiet("pan");
        long afterPan = value(() -> graph.viewportStartForTest());
        note("pan start " + beforePan + " -> " + afterPan);
        if (afterPan == beforePan) {
            FAILURES.add("pan did not move the viewport");
        }
        fx("fit", () -> graph.fitForTest());
        awaitQuiet("fit");
        fx("reset", () -> graph.resetViewForTest());
        awaitQuiet("reset");
        note("reset span " + (value(() -> graph.viewportEndForTest()) - value(() -> graph.viewportStartForTest())));
    }

    private static void visitTabs() throws Exception {
        fx("analytics", () -> graph.selectAnalysisTabForTest(1));
        await("analytics", () -> graph.analysisTabIndexForTest() == 1 && graph.analyticsPaneHeightForTest() > 80, 30);
        assertChrome("analytics");
        fx("engineering", () -> graph.selectAnalysisTabForTest(2));
        await("engineering", () -> graph.analysisTabIndexForTest() == 2 && graph.engineeringPaneHeightForTest() > 80, 30);
        assertChrome("engineering");
        fx("map", () -> graph.selectMapRouteTabForTest());
        await("map", () -> graph.analysisTabIndexForTest() == 3 && graph.mapRoutePaneForTest().getHeight() > 80, 60);
        assertChrome("map");
        note("map height=" + text(() -> String.valueOf(graph.mapRoutePaneForTest().getHeight())));
        fx("waveforms", () -> graph.selectWaveformTabForTest());
        awaitReady(2, false);
    }

    private static void assertChrome(String id) throws Exception {
        fx(id + " chrome", () -> {
            if (plotSettingsPresent()) {
                FAILURES.add(id + " still shows Plot Settings");
            }
            if (graph.analysisTabIndexForTest() != 0) {
                return;
            }
            Node scroll = graph.waveformScrollForTest();
            if (!(scroll.getParent() instanceof VBox waveform) || waveform.getChildren().size() != 3) {
                FAILURES.add(id + " waveform column is not file focus, canvas and interaction bar");
                return;
            }
            Node bar = waveform.getChildren().get(2);
            Bounds scrollBounds = scroll.localToParent(scroll.getBoundsInLocal());
            Bounds barBounds = bar.localToParent(bar.getBoundsInLocal());
            if (barBounds.getHeight() < 20 || barBounds.getHeight() > 48) {
                FAILURES.add(id + " interaction bar height " + barBounds.getHeight());
            }
            if (Math.abs(scrollBounds.getMaxY() - barBounds.getMinY()) >= 2) {
                FAILURES.add(id + " gap between waveform and interaction bar");
            }
            double[] inner = graph.innerCanvasSizeForTest();
            if (graph.selectedSignalCountForTest() > 0 && (inner[0] < 2 || inner[1] < 2)) {
                FAILURES.add(id + " blank canvas " + inner[0] + "x" + inner[1]);
            }
            if (!hasLabel(bar, "Cursor A") || !hasLabel(bar, "Cursor B") || !hasLabel(bar, "Clear")) {
                FAILURES.add(id + " interaction controls missing");
            }
        });
        note(id + " chrome checked selected=" + count(() -> graph.selectedSignalCountForTest())
                + " mode=" + text(() -> graph.layoutModeForTest())
                + " status=" + text(() -> graph.graphStatusForTest()));
    }

    private static boolean plotSettingsPresent() {
        return scene.getRoot().lookupAll(".button").stream().anyMatch(node ->
                node instanceof Button button && button.getText() != null && button.getText().contains("Plot Settings"))
                || !scene.getRoot().lookupAll(".graph-plot-settings-drawer").isEmpty();
    }

    private static boolean hasLabel(Node parent, String text) {
        return parent.lookupAll(".button").stream().anyMatch(node -> text.equals(textOf(node)));
    }

    private static String textOf(Node node) {
        return node instanceof Labeled labeled ? labeled.getText() : "";
    }

    private static List<String> chipColors() throws Exception {
        AtomicReference<List<String>> colors = new AtomicReference<>(List.of());
        fx("colors", () -> {
            List<String> found = new ArrayList<>();
            for (Node node : scene.getRoot().lookupAll(".graph-chip-dot")) {
                found.add(node.getStyle());
            }
            colors.set(List.copyOf(found));
        });
        return colors.get();
    }

    private static void replaceWorkspace(int count) throws Exception {
        List<SignalCatalogKey> keys = firstKeys(count);
        if (graph == null) {
            service.signalsModel().clearWorkspace();
            service.signalsModel().addToWorkspace(keys);
            note("workspace=" + service.signalsModel().workspaceKeys().size());
            return;
        }
        fx("workspace " + count, () -> {
            service.signalsModel().clearWorkspace();
            graph.addWorkspaceKeysPreservingViewForTest(keys);
            graph.selectWaveformTabForTest();
            graph.setLayoutModeForTest(true);
            graph.requestViewportForTest();
        });
        note("workspace=" + service.signalsModel().workspaceKeys().size());
    }

    private static List<SignalCatalogKey> firstKeys(int count) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        List<SignalCatalogKey> keys = new ArrayList<>();
        for (SignalCatalogEntry entry : service.signalsModel().catalog().entries()) {
            if (entry.signalName() == null || !names.add(entry.signalName())) {
                continue;
            }
            keys.add(entry.key());
            if (names.size() == count) {
                break;
            }
        }
        return keys;
    }

    private static void openGraphs(int signals) throws Exception {
        fx("graphs", () -> {
            shell().openEngineeringAnalysis();
            graph = (GraphController) shell().controllerForTest(NavigationPage.GRAPHS);
            graph.selectWaveformTabForTest();
            graph.setLayoutModeForTest(true);
        });
        awaitReady(signals, true);
    }

    private static void awaitReady(int signals, boolean stacked) throws Exception {
        await("ready " + signals, () -> graph.selectedSignalCountForTest() >= signals
                && graph.analysisTabIndexForTest() == 0
                && (stacked
                ? GraphInvestigationState.STACKED.equals(graph.layoutModeForTest())
                : GraphInvestigationState.OVERLAY.equals(graph.layoutModeForTest()))
                && !String.valueOf(graph.graphStatusForTest()).contains("Loading"), 240);
    }

    private static void awaitQuiet(String id) throws Exception {
        await(id, () -> !String.valueOf(graph.graphStatusForTest()).contains("Loading"), 90);
    }

    private static void launch() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            try {
                var area = Screen.getPrimary().getVisualBounds();
                note("screen=" + area.getWidth() + "x" + area.getHeight()
                        + " scale=" + Screen.getPrimary().getOutputScaleX());
                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                Parent root = loader.load();
                MainShellController shell = loader.getController();
                shell.init(service);
                shell.setUnsavedLeaveDecisionForTest(
                        intent -> com.stellar.platform.desktop.presentation.UnsavedLeaveChoice.DISCARD);
                scene = new Scene(root, area.getWidth(), area.getHeight());
                scene.getProperties().put("shell", shell);
                scene.getStylesheets().add(
                        MainShellController.class.getResource("/css/stellar-desktop.css").toExternalForm());
                stage = new Stage();
                stage.setTitle(DesktopVersion.windowTitle());
                stage.setX(area.getMinX());
                stage.setY(area.getMinY());
                stage.setScene(scene);
                stage.setMaximized(true);
                stage.show();
            } catch (Exception ex) {
                error.set(ex);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(30, TimeUnit.SECONDS), "shell did not start");
        if (error.get() != null) {
            throw new IllegalStateException("shell failed", error.get());
        }
    }

    private static void closeStage() {
        if (stage == null) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            stage.close();
            latch.countDown();
        });
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static MainShellController shell() {
        return (MainShellController) scene.getProperties().get("shell");
    }

    private static void fx(String label, JavaFxTestSupport.FxWork work) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            try {
                work.run();
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(90, TimeUnit.SECONDS), "JavaFX scenario timed out (" + label + ")");
        if (error.get() != null) {
            throw new AssertionError(label, error.get());
        }
    }

    private static void await(String label, java.util.function.BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            AtomicBoolean ok = new AtomicBoolean();
            fx(label, () -> ok.set(condition.getAsBoolean()));
            if (ok.get()) {
                return;
            }
            Thread.sleep(200L);
        }
        throw new AssertionError("timed out waiting for " + label + " status=" + text(() -> graph.graphStatusForTest()));
    }

    private static String text(java.util.function.Supplier<String> read) throws Exception {
        AtomicReference<String> value = new AtomicReference<>();
        fx("text", () -> value.set(read.get()));
        return value.get() == null ? "" : value.get();
    }

    private static int count(java.util.function.Supplier<Integer> read) throws Exception {
        AtomicReference<Integer> value = new AtomicReference<>();
        fx("count", () -> value.set(read.get()));
        return value.get();
    }

    private static long value(java.util.function.Supplier<Long> read) throws Exception {
        AtomicReference<Long> found = new AtomicReference<>();
        fx("long", () -> found.set(read.get()));
        return found.get();
    }

    private static void note(String line) {
        NOTES.add(line);
        System.out.println("PLOT " + line);
    }
}
