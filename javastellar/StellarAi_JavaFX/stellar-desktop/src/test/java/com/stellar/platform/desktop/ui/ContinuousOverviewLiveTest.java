package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.GraphSeriesPalette;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.processing.graph.timeline.UnknownOrderLayout;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Development Desktop against the local 73-file Xenon set. Isolated home only.
 * Skips when that dataset is not on this machine.
 */
@Timeout(value = 40, unit = TimeUnit.MINUTES)
class ContinuousOverviewLiveTest {

    private static final Path DATA = Path.of(
            "C:\\Users\\DT - User\\Downloads\\dat files 3.1\\.dat files 3.1");
    private static final Path EVIDENCE = Path.of(
            System.getProperty("java.io.tmpdir"), "stellar-accuracy", "m2-evidence");
    private static DesktopAppService service;
    private static Stage stage;
    private static Scene scene;
    private static GraphController graph;
    private static final StringBuilder TRANSITIONS = new StringBuilder();

    @AfterAll
    static void shutdown() {
        if (service != null) {
            service.shutdown();
            service = null;
        }
    }

    @Test
    void seventyThreeFileDesktopUsesOneUnknownOrderTrack() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(DATA), "73-file dataset is not present");
        List<Path> files = Files.list(DATA).filter(path -> path.getFileName().toString().endsWith(".dat")).sorted().toList();
        assertEquals(73, files.size());
        Path home = Path.of(System.getProperty("java.io.tmpdir"), "stellar-accuracy", "m2-73-home");
        Files.createDirectories(home);
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        Path db = home.resolve("meta.db");
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
        var existing = service.metadataStore().listProjects().stream()
                .filter(project -> "M2-73".equals(project.name()))
                .findFirst();
        long processStarted = System.nanoTime();
        long processMs;
        if (existing.isPresent()) {
            assertTrue(service.openSavedProject(existing.get().projectId()).opened());
            processMs = 0L;
        } else {
            service.newProject("M2-73");
            service.addMeasurementPaths(files, DesktopAppService.PlatformUi.NOOP);
            assertTrue(service.prepareDirectProcessingSelection());
            process();
            processMs = (System.nanoTime() - processStarted) / 1_000_000L;
        }
        assertEquals(73, service.workspace().measurementFiles().size());
        JavaFxTestSupport.startToolkit();
        launch();
        fx("open graph", () -> {
            shell().openEngineeringAnalysis();
            graph = (GraphController) shell().controllerForTest(NavigationPage.GRAPHS);
        });
        assertTrue(graph != null, "graph page");
        await("catalog", () -> graph.explorerRowsForTest() != null && !graph.explorerRowsForTest().isEmpty(), 60);
        fx("clear restored selection", () -> {
            graph.leaveFileFocusForTest();
            for (var key : List.copyOf(graph.selectedMembershipForTest())) {
                graph.removeTraceForTest(key);
            }
        });
        SignalCatalogEntry speed = entry("speed");
        SignalCatalogEntry second = entry("coolant");
        fx("select speed", () -> {
            graph.setLayoutModeForTest(true);
            graph.fireExplorerCheckboxForTest(speed.key());
        });
        awaitStableOverview();
        long fitSpan = span();
        fx("fit assertions", () -> {
            assertEquals("UNKNOWN_ORDER", graph.timelineGroupForTest());
            assertFalse(graph.fileFocusActiveForTest());
            assertFalse(graph.previousFileVisibleForTest());
            assertEquals("UNKNOWN ORDER", graph.timelineGroupLabelForTest());
            assertEquals(1, graph.logicalTrackCountForTest());
            assertEquals(73, graph.segmentCountForTest(0));
            String status = graph.graphStatusForTest();
            assertTrue(status.contains("track(s) · drag=pan"), status);
            assertTrue(status.contains("UNKNOWN ORDER"), status);
            assertTrue(graph.graphCanvasForTest().unknownOrderOverviewForTest(), status);
            javafx.scene.paint.Color trackColor = graph.graphCanvasForTest().logicalTrackColorForTest(0);
            boolean paletteColor = false;
            for (int i = 0; i < GraphSeriesPalette.size(); i++) {
                if (GraphSeriesPalette.at(i).equals(trackColor)) {
                    paletteColor = true;
                    break;
                }
            }
            assertTrue(paletteColor, "track color " + trackColor);
            assertEquals(0L, graph.viewportStartForTest());
            assertEquals(73L * UnknownOrderLayout.EQUAL_SLOT_SPAN, graph.viewportEndForTest());
            assertTrue(graph.rangeTextForTest().contains("not elapsed time"), graph.rangeTextForTest());
            assertFalse(graph.rangeTextForTest().contains(" s"), graph.rangeTextForTest());
        });
        shot("live-73-fit.png");
        String hoverFit = hover();
        assertTrue(hoverFit.startsWith("Pointer:"), hoverFit);
        assertTrue(hoverFit.contains("sample "), hoverFit);
        assertFalse(hoverFit.contains("UTC"), hoverFit);
        assertTrue(hoverFit.contains(".dat") || hoverFit.contains("Xenon")
                || hoverFit.matches("(?s).*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-.*"), hoverFit);

        fx("second signal", () -> graph.fireExplorerCheckboxForTest(second.key()));
        await("two tracks", () -> graph.logicalTrackCountForTest() >= 2, 120);
        fx("colours", () -> {
            assertEquals(2, graph.logicalTrackCountForTest());
            assertFalse(graph.graphCanvasForTest().logicalTrackColorForTest(0)
                    .equals(graph.graphCanvasForTest().logicalTrackColorForTest(1)));
        });
        shot("live-73-two-signals.png");

        long acceptedBeforeZoom = readLong(() -> graph.viewportAcceptedForTest());
        fx("zoom", () -> {
            for (int i = 0; i < 8; i++) graph.zoomInForTest();
        });
        await("zoomed", () -> graph.viewportAcceptedForTest() > acceptedBeforeZoom
                && graph.viewportEndForTest() - graph.viewportStartForTest() < fitSpan / 4
                && !graph.graphStatusForTest().startsWith("Loading"), 120);
        int zoomedSegments = count(() -> graph.segmentCountForTest(0));
        assertTrue(zoomedSegments < 73, "zoom should query fewer recordings, saw " + zoomedSegments);
        shot("live-73-zoom.png");
        long acceptedBeforeRepeat = readLong(() -> graph.viewportAcceptedForTest());
        fx("repeat zoom", () -> graph.reloadViewportForTest());
        await("repeated zoom", () -> graph.viewportAcceptedForTest() > acceptedBeforeRepeat
                && !graph.graphStatusForTest().startsWith("Loading"), 120);

        long beforePan = readLong(() -> graph.viewportStartForTest());
        long acceptedBeforePan = readLong(() -> graph.viewportAcceptedForTest());
        fx("pan", () -> graph.panRightForTest());
        await("panned", () -> graph.viewportAcceptedForTest() > acceptedBeforePan
                && graph.viewportStartForTest() != beforePan
                && !graph.graphStatusForTest().startsWith("Loading"), 60);
        shot("live-73-pan.png");

        long acceptedBeforeDeep = readLong(() -> graph.viewportAcceptedForTest());
        fx("deep zoom", () -> {
            for (int i = 0; i < 12; i++) graph.zoomInForTest();
        });
        await("deep", () -> graph.viewportAcceptedForTest() > acceptedBeforeDeep
                && graph.viewportEndForTest() - graph.viewportStartForTest() < 1_000_000_000L
                && !graph.graphStatusForTest().startsWith("Loading"), 120);
        int deepSegments = count(() -> graph.segmentCountForTest(0));
        assertTrue(deepSegments >= 1 && deepSegments <= 3, "deep zoom segments " + deepSegments);
        shot("live-73-deep-zoom.png");
        hover();
        long acceptedBeforeDeepRepeat = readLong(() -> graph.viewportAcceptedForTest());
        fx("repeat deep", () -> graph.reloadViewportForTest());
        await("repeated deep", () -> graph.viewportAcceptedForTest() > acceptedBeforeDeepRepeat
                && !graph.graphStatusForTest().startsWith("Loading"), 120);
        long staleBeforeBurst = readLong(() -> graph.viewportRejectedStaleForTest());
        fx("burst zoom", () -> graph.zoomInForTest());
        Thread.sleep(200L);
        fx("burst pan", () -> graph.panRightForTest());
        await("burst settled", () -> !graph.graphStatusForTest().startsWith("Loading"), 120);
        System.out.println("M2_73_BURST_STALE="
                + (readLong(() -> graph.viewportRejectedStaleForTest()) - staleBeforeBurst));

        long acceptedBeforeReset = readLong(() -> graph.viewportAcceptedForTest());
        fx("reset", () -> graph.resetViewForTest());
        await("reset", () -> graph.viewportAcceptedForTest() > acceptedBeforeReset
                && graph.viewportEndForTest() - graph.viewportStartForTest() >= fitSpan
                && !graph.graphStatusForTest().startsWith("Loading")
                && graph.graphStatusForTest().contains("track(s)"), 180);
        assertEquals(73, count(() -> graph.segmentCountForTest(0)), "reset overview segments");
        long acceptedBeforeSecondReset = readLong(() -> graph.viewportAcceptedForTest());
        fx("reset again", () -> graph.resetViewForTest());
        await("reset again", () -> graph.viewportAcceptedForTest() > acceptedBeforeSecondReset
                && !graph.graphStatusForTest().startsWith("Loading")
                && graph.segmentCountForTest(0) == 73, 180);
        javafx.scene.paint.Color speedColour = colorAt(0);
        int beforeSelect = count(() -> graph.selectedSignalCountForTest());
        long acceptedBeforeAll = readLong(() -> graph.viewportAcceptedForTest());
        fx("select all", () -> graph.selectAllFilteredForTest());
        await("select all", () -> graph.viewportAcceptedForTest() > acceptedBeforeAll
                && !graph.graphStatusForTest().startsWith("Loading")
                && graph.logicalTrackCountForTest() >= beforeSelect, 180);
        int selected = count(() -> graph.selectedSignalCountForTest());
        int plotted = count(() -> graph.logicalTrackCountForTest());
        assertEquals(selected, plotted, "selection must not be truncated");
        assertTrue(selected > 8, "selected " + selected);
        assertEquals(73, count(() -> graph.segmentCountForTest(0)));
        assertEquals(speedColour, colorAt(0));
        fx("scroll", () -> graph.scrollWaveformForTest(1));
        await("scrolled", () -> !graph.graphStatusForTest().startsWith("Loading")
                && graph.segmentCountForTest(graph.logicalTrackCountForTest() - 1) > 1, 180);
        int lastSegments = count(() -> graph.segmentCountForTest(graph.logicalTrackCountForTest() - 1));
        assertTrue(lastSegments > 1, "last track segments " + lastSegments + " status " + text(() -> graph.graphStatusForTest()));
        System.out.println("M2_22_RESET_SEGMENTS=73");
        System.out.println("M2_22_SELECTED=" + selected);
        System.out.println("M2_22_PLOTTED=" + plotted);
        System.out.println("M2_22_LAST_SEGMENTS=" + lastSegments);
        System.out.println("M2_22_STATUS=" + text(() -> graph.graphStatusForTest()).replace('\n', ' '));
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        System.out.println("M2_73_PROCESS_MS=" + processMs);
        System.out.println("M2_73_FIT_SEGMENTS=73");
        System.out.println("M2_73_ZOOM_SEGMENTS=" + zoomedSegments);
        System.out.println("M2_73_DEEP_SEGMENTS=" + deepSegments);
        System.out.println("M2_73_HEAP_BYTES=" + used);
        System.out.println("M2_73_STALE=" + readLong(() -> graph.viewportRejectedStaleForTest()));
        System.out.println("M2_73_HOVER=" + hoverFit.replace('\n', ' '));
    }

    private static MainShellController shell() {
        return (MainShellController) scene.getProperties().get("shell");
    }

    private static void process() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        service.startLocalProcessing(DesktopAppService.PlatformUi.NOOP, () -> {
            ProjectProcessingStatus status = service.workspace().processingStatus();
            if (!finished.get() && (status == ProjectProcessingStatus.COMPLETED
                    || status == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS
                    || status == ProjectProcessingStatus.FAILED
                    || status == ProjectProcessingStatus.CANCELLED)) {
                finished.set(true);
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.MINUTES), "processing did not finish");
        assertTrue(service.workspace().processingStatus() == ProjectProcessingStatus.COMPLETED
                || service.workspace().processingStatus() == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS,
                String.valueOf(service.workspace().processingStatus()));
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

    private static SignalCatalogEntry entry(String fragment) {
        AtomicReference<SignalCatalogEntry> found = new AtomicReference<>();
        try {
            fx("find " + fragment, () -> {
                for (SignalCatalogEntry entry : graph.explorerRowsForTest()) {
                    String name = entry.signalName() == null ? "" : entry.signalName().toLowerCase();
                    if (name.contains(fragment) && entry.sampleCount() > 0) {
                        found.set(entry);
                        return;
                    }
                }
            });
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
        assertTrue(found.get() != null, "no catalog row containing " + fragment);
        return found.get();
    }

    private static String hover() throws Exception {
        AtomicReference<String> found = new AtomicReference<>("");
        fx("hover", () -> {
            var canvas = graph.graphCanvasForTest();
            var frames = canvas.stackedFramesForTest();
            double[] size = canvas.innerCanvasSizeForTest();
            double y = frames.isEmpty()
                    ? size[2] + size[1] * 0.45
                    : size[2] + (frames.getFirst().plotTop() + frames.getFirst().plotBottom()) / 2.0;
            double width = size[0] > 1 ? size[0] : canvas.getWidth();
            StringBuilder tried = new StringBuilder();
            for (double x = 90; x < width - 10; x += 50) {
                var hit = canvas.hitForTest(x, y);
                canvas.movePointerForTest(x, y);
                String cursor = graph.liveCursorForTest();
                tried.append(" x=").append((int) x)
                        .append(" hit=").append(hit == null ? "none" : hit.measurementId())
                        .append(" cursor=").append(cursor);
                if (cursor.startsWith("Pointer:") && !cursor.contains("Hover waveform")) {
                    found.set(cursor);
                    return;
                }
            }
            found.set("MISS y=" + y + " size=" + size[0] + "x" + size[1] + "@" + size[2] + tried);
        });
        return found.get();
    }

    private static void shot(String name) throws Exception {
        Files.createDirectories(EVIDENCE);
        AtomicReference<WritableImage> image = new AtomicReference<>();
        fx("shot " + name, () -> image.set(scene.snapshot(null)));
        if (image.get() != null) {
            ImageIO.write(SwingFXUtils.fromFXImage(image.get(), null), "png", EVIDENCE.resolve(name).toFile());
        }
    }

    private static void fx(String label, JavaFxTestSupport.FxWork work) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            started.countDown();
            try {
                work.run();
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(180, TimeUnit.SECONDS),
                "JavaFX scenario timed out (" + label + ") pulseStarted=" + (started.getCount() == 0));
        if (error.get() != null) {
            throw new AssertionError(error.get());
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
            Thread.sleep(250L);
        }
        throw new AssertionError("timed out waiting for " + label + " status=" + text(() -> graph.graphStatusForTest()));
    }

    /**
     * The first completed status is not the end of loading. Publishing tracks changes the
     * canvas width and the visible window, and each of those calls requestViewport, which
     * increments the generation and sets "Loading waveform…" before the next query finishes.
     * Two observations of the same generation, 250 ms apart, are the same render.
     */
    private static void awaitStableOverview() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        long stableGeneration = Long.MIN_VALUE;
        int matches = 0;
        while (System.nanoTime() < deadline) {
            AtomicBoolean ready = new AtomicBoolean();
            AtomicLong generation = new AtomicLong();
            fx("one track", () -> {
                ready.set(overviewSettled());
                generation.set(graph.generationForTest());
                noteOverview("poll");
            });
            if (ready.get() && generation.get() == stableGeneration) {
                matches++;
                if (matches >= 2) {
                    fx("stable", () -> noteOverview("stable"));
                    Files.createDirectories(EVIDENCE);
                    Files.writeString(EVIDENCE.resolve("continuous-overview-transitions.txt"), TRANSITIONS.toString());
                    return;
                }
            } else if (ready.get()) {
                stableGeneration = generation.get();
                matches = 1;
            } else {
                stableGeneration = Long.MIN_VALUE;
                matches = 0;
            }
            Thread.sleep(250L);
        }
        Files.createDirectories(EVIDENCE);
        Files.writeString(EVIDENCE.resolve("continuous-overview-transitions.txt"), TRANSITIONS.toString());
        throw new AssertionError("timed out waiting for a stable overview status="
                + text(() -> graph.graphStatusForTest()));
    }

    private static boolean overviewSettled() {
        String status = graph.graphStatusForTest();
        if (status == null || graph.graphCanvasForTest() == null) {
            return false;
        }
        return graph.logicalTrackCountForTest() == 1
                && graph.segmentCountForTest(0) == 73
                && status.contains("track(s) · drag=pan")
                && status.contains("UNKNOWN ORDER")
                && !status.startsWith("Loading")
                && graph.graphCanvasForTest().unknownOrderOverviewForTest();
    }

    private static void noteOverview(String mark) {
        String status = graph.graphStatusForTest();
        TRANSITIONS.append(System.currentTimeMillis())
                .append(' ').append(mark)
                .append(" status=").append(status == null ? "" : status.replace('\n', ' '))
                .append(" tracks=").append(graph.logicalTrackCountForTest())
                .append(" segments=").append(graph.segmentCountForTest(0))
                .append(" generation=").append(graph.generationForTest())
                .append(" accepted=").append(graph.viewportAcceptedForTest())
                .append(" requests=").append(graph.viewportRequestsForTest())
                .append(" paints=").append(graph.canvasPaintCountForTest())
                .append(" fx=").append(Thread.currentThread().getName())
                .append('\n');
    }

    private static long span() throws Exception {
        AtomicReference<Long> value = new AtomicReference<>();
        fx("span", () -> value.set(graph.viewportEndForTest() - graph.viewportStartForTest()));
        return value.get();
    }

    private static int count(java.util.function.Supplier<Integer> read) throws Exception {
        AtomicReference<Integer> value = new AtomicReference<>();
        fx("count", () -> value.set(read.get()));
        return value.get();
    }

    private static long readLong(java.util.function.LongSupplier read) throws Exception {
        AtomicReference<Long> value = new AtomicReference<>();
        fx("long", () -> value.set(read.getAsLong()));
        return value.get();
    }

    private static javafx.scene.paint.Color colorAt(int track) throws Exception {
        AtomicReference<javafx.scene.paint.Color> value = new AtomicReference<>();
        fx("color", () -> value.set(graph.graphCanvasForTest().logicalTrackColorForTest(track)));
        return value.get();
    }

    private static String text(java.util.function.Supplier<String> read) throws Exception {
        AtomicReference<String> value = new AtomicReference<>("");
        fx("text", () -> value.set(read.get()));
        return value.get();
    }
}
