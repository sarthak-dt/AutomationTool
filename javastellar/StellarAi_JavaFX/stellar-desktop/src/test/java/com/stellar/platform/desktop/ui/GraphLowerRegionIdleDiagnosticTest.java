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
import javafx.animation.AnimationTimer;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
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
 * BD-07: continuous flicker of the lower Graph region, with no pointer or key input.
 * Samples geometry every pulse, then the upper and lower bands of the waveform.
 */
@Timeout(value = 25, unit = TimeUnit.MINUTES)
class GraphLowerRegionIdleDiagnosticTest {

    private static final Path EVIDENCE = Path.of("D:\\DesktopApp\\DriveT\\docs\\qa-handoff\\bd07-lower-region-"
            + System.getProperty("prism.order", "default") + ".txt");
    private static final List<String> NOTES = new ArrayList<>();
    private static final CopyOnWriteArrayList<Throwable> FX_ERRORS = new CopyOnWriteArrayList<>();
    private static DesktopAppService service;
    private static Scene scene;
    private static GraphController graph;
    private static Stage stage;

    @AfterAll
    static void shutdown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void idleLowerRegionAcrossSignalCountsAndLayouts() throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> FX_ERRORS.add(error));
        try {
            Path home = Path.of(System.getProperty("java.io.tmpdir"), "stellar-accuracy", "m2-73-home");
            Assumptions.assumeTrue(Files.isRegularFile(home.resolve("meta.db")), "isolated M2-73 project is not present");
            System.setProperty("stellarai.test.isolate", "true");
            System.setProperty("stellarai.home", home.toString());
            note("recording=unavailable; window follows the primary desktop work area");
            note("prism.order=" + System.getProperty("prism.order"));
            note("product=" + DesktopVersion.displayVersion());
            service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(home.resolve("meta.db")));
            var project = service.metadataStore().listProjects().stream()
                    .filter(row -> "M2-73".equals(row.name()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(service.openSavedProject(project.projectId()).opened());
            replaceWorkspace(2);
            JavaFxTestSupport.startToolkit();
            launchMaximized();
            openGraphs(2);
            watchPair(2);
            replaceWorkspace(24);
            awaitReady(24, true);
            watchPair(24);
            replaceWorkspace(30);
            awaitReady(30, true);
            watchPair(30);
            note("fxErrors=" + FX_ERRORS.size());
            if (!FX_ERRORS.isEmpty()) {
                note("fxError=" + FX_ERRORS.getFirst());
            }
        } catch (Throwable thrown) {
            note("ABORTED " + thrown);
            thrown.printStackTrace(System.out);
        } finally {
            Files.writeString(EVIDENCE, String.join(System.lineSeparator(), NOTES));
            closeStage();
        }
    }

    private static void watchPair(int signals) throws Exception {
        fx("stacked " + signals, () -> graph.setLayoutModeForTest(true));
        awaitReady(signals, true);
        watch(signals + "-stacked", true);
        fx("overlay " + signals, () -> graph.setLayoutModeForTest(false));
        awaitReady(signals, false);
        watch(signals + "-overlay", false);
    }

    private static void watch(String id, boolean stacked) throws Exception {
        awaitQuiet(id);
        Pulse geometry = pulses(id + "-geometry", false);
        Pulse pixels = pulses(id + "-pixels", true);
        note(id + " stacked=" + stacked + " GEOMETRY " + geometry);
        note(id + " stacked=" + stacked + " PIXELS " + pixels);
        note(id + " status=" + text(() -> graph.graphStatusForTest()));
    }

    private static void awaitQuiet(String id) throws Exception {
        AtomicReference<Integer> last = new AtomicReference<>(-1);
        AtomicReference<Integer> stable = new AtomicReference<>(0);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            int paints = count(() -> graph.canvasPaintCountForTest());
            if (last.get() == paints) {
                stable.set(stable.get() + 1);
                if (stable.get() >= 3) {
                    return;
                }
            } else {
                stable.set(0);
                last.set(paints);
            }
            Thread.sleep(150L);
        }
        note(id + " still painting when the idle window opened");
    }

    private static Pulse pulses(String label, boolean pixels) throws Exception {
        Pulse pulse = new Pulse();
        pulse.paints = count(() -> graph.canvasPaintCountForTest());
        pulse.requests = value(() -> graph.viewportRequestsForTest());
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        fx(label, () -> {
            AnimationTimer timer = new AnimationTimer() {
                private long started;

                @Override
                public void handle(long now) {
                    try {
                        if (started == 0) {
                            started = now;
                        }
                        if (pulse.frames < 180 && (pixels || pulse.frames % 1 == 0)) {
                            pulse.add(graph, pixels && pulse.frames % 4 == 0);
                        }
                        if (now - started >= 3_000_000_000L || pulse.frames >= 180) {
                            stop();
                            done.countDown();
                        }
                    } catch (Throwable thrown) {
                        error.set(thrown);
                        stop();
                        done.countDown();
                    }
                }
            };
            timer.start();
        });
        assertTrue(done.await(12, TimeUnit.SECONDS), label + " pulse watch did not finish");
        if (error.get() != null) {
            throw new AssertionError(label, error.get());
        }
        pulse.paintDelta = count(() -> graph.canvasPaintCountForTest()) - pulse.paints;
        pulse.requestDelta = value(() -> graph.viewportRequestsForTest()) - pulse.requests;
        return pulse;
    }

    private static void replaceWorkspace(int count) throws Exception {
        List<SignalCatalogKey> keys = firstKeys(count);
        if (graph == null) {
            service.signalsModel().clearWorkspace();
            service.signalsModel().addToWorkspace(keys);
            note("workspace=" + service.signalsModel().workspaceKeys().size() + " requested=" + count);
            return;
        }
        fx("workspace " + count, () -> {
            service.signalsModel().clearWorkspace();
            graph.addWorkspaceKeysPreservingViewForTest(keys);
            graph.selectWaveformTabForTest();
            graph.setLayoutModeForTest(true);
            graph.requestViewportForTest();
        });
        note("workspace=" + service.signalsModel().workspaceKeys().size() + " requested=" + count);
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
        await("ready " + signals + (stacked ? " stacked" : " overlay"), () ->
                graph.selectedSignalCountForTest() >= signals
                        && graph.analysisTabIndexForTest() == 0
                        && (stacked
                        ? GraphInvestigationState.STACKED.equals(graph.layoutModeForTest())
                        : GraphInvestigationState.OVERLAY.equals(graph.layoutModeForTest()))
                        && !String.valueOf(graph.graphStatusForTest()).contains("Loading"), 240);
    }

    private static void launchMaximized() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            try {
                Screen screen = Screen.getPrimary();
                Rectangle2D area = screen.getVisualBounds();
                note("screen=" + area.getWidth() + "x" + area.getHeight()
                        + " scale=" + screen.getOutputScaleX() + "x" + screen.getOutputScaleY()
                        + " dpi=" + screen.getDpi());
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
                note("stage=" + stage.getWidth() + "x" + stage.getHeight() + " maximized=" + stage.isMaximized());
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
        throw new AssertionError("timed out waiting for " + label + " status=" + text(() -> graph.graphStatusForTest())
                + " selected=" + count(() -> graph.selectedSignalCountForTest())
                + " mode=" + text(() -> graph.layoutModeForTest()));
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
        System.out.println("BD07L " + line);
    }

    private static final class Pulse {
        int frames;
        int paintDelta;
        long requestDelta;
        private int paints;
        private long requests;
        private double minViewport = Double.POSITIVE_INFINITY;
        private double maxViewport = Double.NEGATIVE_INFINITY;
        private double minCanvas = Double.POSITIVE_INFINITY;
        private double maxCanvas = Double.NEGATIVE_INFINITY;
        private double minInnerH = Double.POSITIVE_INFINITY;
        private double maxInnerH = Double.NEGATIVE_INFINITY;
        private double minInnerY = Double.POSITIVE_INFINITY;
        private double maxInnerY = Double.NEGATIVE_INFINITY;
        private double minScroll = Double.POSITIVE_INFINITY;
        private double maxScroll = Double.NEGATIVE_INFINITY;
        private double maxOverlap;
        private int viewportChanges;
        private int innerChanges;
        private double lastViewport = Double.NaN;
        private double lastInnerH = Double.NaN;
        private int lastPaintCount = -1;
        private final List<Integer> paintFrames = new ArrayList<>();
        private String screenRect = "";
        private final LinkedHashSet<Long> upper = new LinkedHashSet<>();
        private final LinkedHashSet<Long> lower = new LinkedHashSet<>();
        private final LinkedHashSet<Long> bar = new LinkedHashSet<>();
        private final LinkedHashSet<String> managed = new LinkedHashSet<>();

        void add(GraphController controller, boolean capturePixels) {
            frames++;
            double viewport = controller.waveformViewportHeightForTest();
            double canvas = controller.canvasHeightForTest();
            double[] inner = controller.innerCanvasSizeForTest();
            double scroll = controller.waveformScrollValueForTest();
            track(viewport, canvas, inner[1], inner[2], scroll);
            maxOverlap = Math.max(maxOverlap, overlap(controller));
            boolean drawer = controller.canvasForTest() != null
                    && controller.canvasForTest().getScene() != null
                    && !controller.canvasForTest().getScene().getRoot().lookupAll(".graph-plot-settings-drawer").isEmpty();
            managed.add(Boolean.toString(drawer));
            int paintsNow = controller.canvasPaintCountForTest();
            if (paintsNow != lastPaintCount) {
                paintFrames.add(frames);
                lastPaintCount = paintsNow;
            }
            if (capturePixels) {
                capture(controller);
            }
        }

        private void track(double viewport, double canvas, double innerH, double innerY, double scroll) {
            minViewport = Math.min(minViewport, viewport);
            maxViewport = Math.max(maxViewport, viewport);
            minCanvas = Math.min(minCanvas, canvas);
            maxCanvas = Math.max(maxCanvas, canvas);
            minInnerH = Math.min(minInnerH, innerH);
            maxInnerH = Math.max(maxInnerH, innerH);
            minInnerY = Math.min(minInnerY, innerY);
            maxInnerY = Math.max(maxInnerY, innerY);
            minScroll = Math.min(minScroll, scroll);
            maxScroll = Math.max(maxScroll, scroll);
            if (!Double.isNaN(lastViewport) && Math.abs(lastViewport - viewport) > 0.25) {
                viewportChanges++;
            }
            if (!Double.isNaN(lastInnerH) && Math.abs(lastInnerH - innerH) > 0.25) {
                innerChanges++;
            }
            lastViewport = viewport;
            lastInnerH = innerH;
        }

        private void capture(GraphController controller) {
            Node scroll = controller.waveformScrollForTest();
            if (!(scroll instanceof Region region) || region.getWidth() < 8 || region.getHeight() < 80 || region.getScene() == null) {
                return;
            }
            Bounds bounds = region.localToScreen(region.getBoundsInLocal());
            if (bounds == null) {
                return;
            }
            double scale = region.getScene().getWindow().getOutputScaleX();
            int x = (int) Math.round(bounds.getMinX() * scale);
            int y = (int) Math.round(bounds.getMinY() * scale);
            int width = (int) Math.round(bounds.getWidth() * scale);
            int height = (int) Math.round(bounds.getHeight() * scale);
            int band = Math.min(96, height / 3);
            screenRect = x + "," + y + " " + width + "x" + height + " scale=" + scale;
            try {
                java.awt.Robot robot = new java.awt.Robot();
                upper.add(hash(robot.createScreenCapture(new java.awt.Rectangle(x, y, width, band))));
                lower.add(hash(robot.createScreenCapture(new java.awt.Rectangle(x, y + height - band, width, band))));
            } catch (RuntimeException | java.awt.AWTException ex) {
                screenRect = screenRect + " robot=" + ex.getClass().getSimpleName();
            }
            Node barNode = visibleBar(controller);
            if (barNode instanceof Region interaction && interaction.getWidth() > 8 && interaction.getHeight() > 4) {
                Bounds barBounds = interaction.localToScreen(interaction.getBoundsInLocal());
                if (barBounds != null) {
                    int bx = (int) Math.round(barBounds.getMinX() * scale);
                    int by = (int) Math.round(barBounds.getMinY() * scale);
                    int bw = (int) Math.round(barBounds.getWidth() * scale);
                    int bh = Math.max(1, (int) Math.round(barBounds.getHeight() * scale));
                    try {
                        bar.add(hash(new java.awt.Robot().createScreenCapture(new java.awt.Rectangle(bx, by, bw, bh))));
                    } catch (RuntimeException | java.awt.AWTException ignored) {
                        bar.add(-1L);
                    }
                }
            }
        }

        private static long hash(java.awt.image.BufferedImage image) {
            long hash = 1;
            int width = image.getWidth();
            int rows = image.getHeight();
            for (int row = 0; row < rows; row += 2) {
                for (int column = 0; column < width; column += 4) {
                    hash = hash * 31 + image.getRGB(column, row);
                }
            }
            return hash;
        }

        private static Node visibleBar(GraphController controller) {
            if (controller.canvasForTest() == null || controller.canvasForTest().getScene() == null) {
                return null;
            }
            Node found = null;
            for (Node node : controller.canvasForTest().getScene().getRoot().lookupAll(".graph-interaction-bar")) {
                if (node.isVisible() && node.isManaged()) {
                    found = node;
                }
            }
            return found;
        }

        private static double overlap(GraphController controller) {
            Node scroll = controller.waveformScrollForTest();
            Node bar = visibleBar(controller);
            if (scroll == null || bar == null || scroll.getScene() == null) {
                return 0;
            }
            Bounds scrollBounds = scroll.localToScene(scroll.getBoundsInLocal());
            Bounds barBounds = bar.localToScene(bar.getBoundsInLocal());
            return scrollBounds.getMaxY() - barBounds.getMinY();
        }

        @Override
        public String toString() {
            return "frames=" + frames
                    + " paints=" + paintDelta
                    + " viewportRequests=" + requestDelta
                    + " viewportH=" + minViewport + ".." + maxViewport + " changes=" + viewportChanges
                    + " canvasH=" + minCanvas + ".." + maxCanvas
                    + " innerH=" + minInnerH + ".." + maxInnerH + " changes=" + innerChanges
                    + " innerY=" + minInnerY + ".." + maxInnerY
                    + " scroll=" + minScroll + ".." + maxScroll
                    + " overlap=" + maxOverlap
                    + " plotSettings=" + managed
                    + " upperHashes=" + upper.size()
                    + " lowerHashes=" + lower.size()
                    + " barHashes=" + bar.size()
                    + " paintFrames=" + paintFrames
                    + " screen=" + screenRect;
        }
    }
}
