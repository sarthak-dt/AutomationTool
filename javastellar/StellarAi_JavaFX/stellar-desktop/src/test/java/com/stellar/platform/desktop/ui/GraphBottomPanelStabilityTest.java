package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphInvestigationState;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BD-07: the Graph lower panel must keep its selected tab and canvas size while idle,
 * and after the user switches Waveforms, Analytics and Engineering.
 */
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class GraphBottomPanelStabilityTest {

    private static final Path EVIDENCE = Path.of("D:\\DesktopApp\\DriveT\\docs\\qa-handoff\\bd07-stability-after-plot-settings-removal.txt");
    private static final List<String> NOTES = new ArrayList<>();
    private static final List<String> FAILURES = new ArrayList<>();
    private static final CopyOnWriteArrayList<Throwable> FX_ERRORS = new CopyOnWriteArrayList<>();
    private static DesktopAppService service;
    private static Scene scene;
    private static GraphController graph;
    private static Stage stage;

    @AfterAll
    static void shutdown() {
        if (service != null) {
            service.shutdown();
            service = null;
        }
    }

    @Test
    void bottomPanelStaysStableForTwoStackedSignals() throws Exception {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            FX_ERRORS.add(error);
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
        try {
            Path home = Path.of(System.getProperty("java.io.tmpdir"), "stellar-accuracy", "m2-73-home");
            Assumptions.assumeTrue(Files.isRegularFile(home.resolve("meta.db")), "isolated M2-73 project is not present");
            System.setProperty("stellarai.test.isolate", "true");
            System.setProperty("stellarai.home", home.toString());
            note("prism.order=" + System.getProperty("prism.order"));
            note("product=" + DesktopVersion.displayVersion());
            service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(home.resolve("meta.db")));
            var project = service.metadataStore().listProjects().stream()
                    .filter(row -> "M2-73".equals(row.name()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(service.openSavedProject(project.projectId()).opened());
            narrowToSpeedAndCoolant();
            JavaFxTestSupport.startToolkit();
            launch();
            fx("graphs", () -> {
                shell().openEngineeringAnalysis();
                graph = (GraphController) shell().controllerForTest(NavigationPage.GRAPHS);
            });
            await("two tracks", () -> graph.logicalTrackCountForTest() == 2
                    && !String.valueOf(graph.graphStatusForTest()).contains("Loading"), 180);
            fx("stacked", () -> {
                if (!GraphInvestigationState.STACKED.equals(graph.layoutModeForTest())) {
                    graph.setLayoutModeForTest(true);
                }
            });
            await("stacked ready", () -> GraphInvestigationState.STACKED.equals(graph.layoutModeForTest())
                    && graph.logicalTrackCountForTest() == 2
                    && !String.valueOf(graph.graphStatusForTest()).contains("Loading"), 120);
            fx("waveforms first", () -> graph.selectWaveformTabForTest());
            await("waveforms showing", () -> graph.analysisTabIndexForTest() == 0
                    && graph.logicalTrackCountForTest() == 2
                    && !String.valueOf(graph.graphStatusForTest()).contains("Loading"), 60);
            // A shown stage receives the desktop cursor. Each move paints the crosshair once.
            // Hold that pointer so this budget measures unattended layout and data paints.
            fx("hold pointer", () -> graph.holdWaveformPointerForTest(true));
            Sample idle = watch("idle-waveforms", 30);
            checkStable("idle", idle, 0);
            fx("no plot settings", () -> {
                if (scene.getRoot().lookupAll(".button").stream().anyMatch(node ->
                        node instanceof javafx.scene.control.Button button
                                && button.getText() != null
                                && button.getText().contains("Plot Settings"))) {
                    throw new IllegalStateException("Plot Settings button is still in the graph");
                }
            });
            fx("analytics", () -> graph.selectAnalysisTabForTest(1));
            Sample analytics = watch("analytics", 8);
            checkTab("analytics-tab", 1);
            fx("engineering", () -> graph.selectAnalysisTabForTest(2));
            Sample engineering = watch("engineering", 8);
            checkTab("engineering-tab", 2);
            fx("waveforms", () -> graph.selectWaveformTabForTest());
            await("waveforms restored", () -> graph.analysisTabIndexForTest() == 0
                    && graph.logicalTrackCountForTest() == 2
                    && !String.valueOf(graph.graphStatusForTest()).contains("Loading"), 120);
            Sample back = watch("waveforms-return", 4);
            checkStable("return", back, 0);
            fx("resize", () -> stage.setWidth(1280));
            await("resized", () -> graph.logicalTrackCountForTest() == 2
                    && !String.valueOf(graph.graphStatusForTest()).contains("Loading"), 60);
            Sample resized = watch("resized", 4);
            checkStable("resized", resized, 0);
            note("fxErrors=" + FX_ERRORS.size());
            if (!FX_ERRORS.isEmpty()) {
                FAILURES.add("FX exceptions " + FX_ERRORS.getFirst());
            }
        } catch (Throwable thrown) {
            note("ABORTED " + thrown);
            FAILURES.add("aborted: " + thrown.getMessage());
        } finally {
            Files.writeString(EVIDENCE, String.join(System.lineSeparator(), NOTES));
            closeStage();
        }
        assertTrue(FAILURES.isEmpty(), String.join("\n", FAILURES));
    }

    private static void checkTab(String id, int expected) throws Exception {
        int actual = count(() -> graph.analysisTabIndexForTest());
        note(id + " selected=" + actual);
        if (actual != expected) {
            FAILURES.add(id + " selected " + actual + " expected " + expected);
        }
    }

    private static void checkStable(String id, Sample sample, int tab) {
        note(id + " " + sample);
        if (sample.tabs.stream().distinct().count() != 1 || sample.tabs.getFirst() != tab) {
            FAILURES.add(id + " tab changed " + sample.tabs);
        }
        if (sample.maxCanvas - sample.minCanvas > 8) {
            FAILURES.add(id + " canvas height moved " + sample.minCanvas + ".." + sample.maxCanvas);
        }
        if (sample.maxViewport - sample.minViewport > 8) {
            FAILURES.add(id + " waveform viewport moved " + sample.minViewport + ".." + sample.maxViewport);
        }
        if (sample.blankFrames > 0) {
            FAILURES.add(id + " blank canvas frames " + sample.blankFrames);
        }
        if (sample.paintDelta > 6) {
            FAILURES.add(id + " paint storm " + sample.paintDelta);
        }
        if (sample.viewportRequestDelta > 2) {
            FAILURES.add(id + " viewport requests " + sample.viewportRequestDelta);
        }
        if (sample.analyticsLayouts > 40) {
            FAILURES.add(id + " analytics layout storm " + sample.analyticsLayouts);
        }
    }

    private static Sample watch(String label, int seconds) throws Exception {
        Sample sample = new Sample();
        long requests = value(() -> graph.viewportRequestsForTest());
        int paints = count(() -> graph.canvasPaintCountForTest());
        int layouts = count(() -> graph.analyticsLayoutCountForTest());
        note(label + " startDiag " + text(() -> graph.paintDiagnosticsForTest()) + " tab=" + count(() -> graph.analysisTabIndexForTest()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            fx(label, () -> sample.add(graph));
            Thread.sleep(250L);
        }
        sample.paintDelta = count(() -> graph.canvasPaintCountForTest()) - paints;
        sample.viewportRequestDelta = value(() -> graph.viewportRequestsForTest()) - requests;
        sample.analyticsLayouts = count(() -> graph.analyticsLayoutCountForTest()) - layouts;
        note(label + " " + sample);
        note(label + " diag " + text(() -> graph.paintDiagnosticsForTest()));
        return sample;
    }

    private static void narrowToSpeedAndCoolant() {
        var model = service.signalsModel();
        model.clearWorkspace();
        for (String name : List.of("IN_Vehicle_speed", "IN_Coolant_temperature")) {
            model.catalog().entries().stream()
                    .filter(entry -> name.equals(entry.signalName()))
                    .findFirst()
                    .ifPresent(entry -> model.addToWorkspace(entry.key()));
        }
        note("workspace=" + model.workspaceKeys().size());
    }

    private static void launch() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                Parent root = loader.load();
                MainShellController shell = loader.getController();
                shell.init(service);
                shell.setUnsavedLeaveDecisionForTest(
                        intent -> com.stellar.platform.desktop.presentation.UnsavedLeaveChoice.DISCARD);
                scene = new Scene(root, 1440, 900);
                scene.getProperties().put("shell", shell);
                scene.getStylesheets().add(
                        MainShellController.class.getResource("/css/stellar-desktop.css").toExternalForm());
                stage = new Stage();
                stage.setTitle(DesktopVersion.windowTitle());
                stage.setScene(scene);
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
        assertTrue(done.await(60, TimeUnit.SECONDS), "JavaFX scenario timed out (" + label + ")");
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
        System.out.println("BD07 " + line);
    }

    private static final class Sample {
        final List<Integer> tabs = new ArrayList<>();
        double minCanvas = Double.POSITIVE_INFINITY;
        double maxCanvas = Double.NEGATIVE_INFINITY;
        double minViewport = Double.POSITIVE_INFINITY;
        double maxViewport = Double.NEGATIVE_INFINITY;
        double minAnalytics = Double.POSITIVE_INFINITY;
        double maxAnalytics = Double.NEGATIVE_INFINITY;
        double minEngineering = Double.POSITIVE_INFINITY;
        double maxEngineering = Double.NEGATIVE_INFINITY;
        int blankFrames;
        int paintDelta;
        long viewportRequestDelta;
        int analyticsLayouts;

        void add(GraphController controller) {
            tabs.add(controller.analysisTabIndexForTest());
            double canvas = controller.canvasHeightForTest();
            double viewport = controller.waveformViewportHeightForTest();
            minCanvas = Math.min(minCanvas, canvas);
            maxCanvas = Math.max(maxCanvas, canvas);
            minViewport = Math.min(minViewport, viewport);
            maxViewport = Math.max(maxViewport, viewport);
            minAnalytics = Math.min(minAnalytics, controller.analyticsPaneHeightForTest());
            maxAnalytics = Math.max(maxAnalytics, controller.analyticsPaneHeightForTest());
            minEngineering = Math.min(minEngineering, controller.engineeringPaneHeightForTest());
            maxEngineering = Math.max(maxEngineering, controller.engineeringPaneHeightForTest());
            double[] inner = controller.innerCanvasSizeForTest();
            if (controller.analysisTabIndexForTest() == 0 && controller.logicalTrackCountForTest() > 0
                    && (inner[0] < 2 || inner[1] < 2)) {
                blankFrames++;
            }
        }

        @Override
        public String toString() {
            return "tabs=" + tabs.stream().distinct().toList()
                    + " canvas=" + minCanvas + ".." + maxCanvas
                    + " viewport=" + minViewport + ".." + maxViewport
                    + " analytics=" + minAnalytics + ".." + maxAnalytics
                    + " engineering=" + minEngineering + ".." + maxEngineering
                    + " blank=" + blankFrames
                    + " paints=" + paintDelta
                    + " viewportRequests=" + viewportRequestDelta
                    + " analyticsLayouts=" + analyticsLayouts;
        }
    }
}
