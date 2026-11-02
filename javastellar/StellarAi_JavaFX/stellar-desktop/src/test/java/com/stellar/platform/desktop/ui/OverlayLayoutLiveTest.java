package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphInvestigationState;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.processing.graph.timeline.UnknownOrderLayout;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
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
 * Same 73-file window in Stacked and Overlay. Overlay must keep each recording in its slot.
 */
@Timeout(value = 20, unit = TimeUnit.MINUTES)
class OverlayLayoutLiveTest {

    private static final long SLOT = UnknownOrderLayout.EQUAL_SLOT_SPAN;
    private static final Path EVIDENCE = Path.of(
            System.getProperty("java.io.tmpdir"), "stellar-accuracy", "m2-evidence");
    private static DesktopAppService service;
    private static Scene scene;
    private static GraphController graph;

    @AfterAll
    static void shutdown() {
        if (service != null) {
            service.shutdown();
            service = null;
        }
    }

    @Test
    void overlayMatchesStackedSlotsOnTheIsolatedProject() throws Exception {
        Path home = Path.of(System.getProperty("java.io.tmpdir"), "stellar-accuracy", "m2-73-home");
        Path db = home.resolve("meta.db");
        Assumptions.assumeTrue(Files.isRegularFile(db), "isolated M2-73 project is not present");
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
        var project = service.metadataStore().listProjects().stream()
                .filter(row -> "M2-73".equals(row.name()))
                .findFirst()
                .orElseThrow();
        assertTrue(service.openSavedProject(project.projectId()).opened());
        assertEquals(73, service.workspace().measurementFiles().size());
        JavaFxTestSupport.startToolkit();
        launch();
        fx("open graph", () -> {
            shell().openEngineeringAnalysis();
            graph = (GraphController) shell().controllerForTest(NavigationPage.GRAPHS);
        });
        await("catalog", () -> graph.explorerRowsForTest() != null && !graph.explorerRowsForTest().isEmpty(), 90);
        fx("clear restored selection", () -> {
            graph.leaveFileFocusForTest();
            for (var key : List.copyOf(graph.selectedMembershipForTest())) {
                graph.removeTraceForTest(key);
            }
        });
        SignalCatalogEntry speed = exact("IN_Vehicle_speed");
        SignalCatalogEntry coolant = named("coolant");
        fx("select", () -> {
            graph.fireExplorerCheckboxForTest(speed.key());
            graph.fireExplorerCheckboxForTest(coolant.key());
            graph.setLayoutModeForTest(true);
        });
        await("fit", () -> graph.logicalTrackCountForTest() == 2
                && graph.segmentCountForTest(0) == 73
                && graph.segmentCountForTest(1) == 73
                && GraphInvestigationState.STACKED.equals(graph.layoutModeForTest())
                && !graph.graphStatusForTest().startsWith("Loading"), 180);
        assertEquals("UNKNOWN_ORDER", text(() -> graph.timelineGroupForTest()));
        assertEquals(GraphInvestigationState.STACKED, text(() -> graph.layoutModeForTest()));
        shot("overlay-live-stacked-fit.png");
        note("stacked-fit");

        long overlayFx = fxMillis("overlay", () -> graph.setLayoutModeForTest(false));
        await("overlay fit", () -> GraphInvestigationState.OVERLAY.equals(graph.layoutModeForTest())
                && graph.segmentCountForTest(0) == 73
                && !graph.graphStatusForTest().startsWith("Loading"), 120);
        shot("overlay-live-overlay-fit.png");
        note("overlay-fit");
        String overlayHover = hover();
        assertTrue(overlayHover.startsWith("Pointer:"), overlayHover);
        assertFalse(overlayHover.contains("UTC"), overlayHover);

        long slotFx = fxMillis("slot", () -> {
            graph.setViewportForTest(37 * SLOT, 38 * SLOT);
            graph.reloadViewportForTest();
        });
        await("slot 38", () -> {
            String range = graph.rangeTextForTest();
            return graph.segmentCountForTest(0) >= 1 && graph.segmentCountForTest(0) <= 3
                    && range.contains("Slots 38") && range.contains("not elapsed time")
                    && !graph.graphStatusForTest().startsWith("Loading");
        }, 120);
        shot("overlay-live-overlay-slot38.png");
        note("overlay-slot38");

        long stackedFx = fxMillis("stacked", () -> graph.setLayoutModeForTest(true));
        await("stacked slot", () -> GraphInvestigationState.STACKED.equals(graph.layoutModeForTest())
                && graph.segmentCountForTest(0) >= 1
                && !graph.graphStatusForTest().startsWith("Loading"), 60);
        shot("overlay-live-stacked-slot38.png");
        note("stacked-slot38");

        long backFx = fxMillis("overlay again", () -> graph.setLayoutModeForTest(false));
        long zoomFx = fxMillis("zoom", () -> {
            for (int i = 0; i < 8; i++) graph.zoomInForTest();
        });
        await("deep", () -> graph.viewportEndForTest() - graph.viewportStartForTest() < SLOT
                && graph.segmentCountForTest(0) >= 1
                && !graph.graphStatusForTest().startsWith("Loading"), 120);
        shot("overlay-live-overlay-deep.png");
        note("overlay-deep");
        String deepHover = hover();
        assertTrue(deepHover.startsWith("Pointer:"), deepHover);

        long beforePan = value(() -> graph.viewportStartForTest());
        long panFx = fxMillis("pan", () -> graph.panRightForTest());
        await("panned", () -> graph.viewportStartForTest() != beforePan
                && !graph.graphStatusForTest().startsWith("Loading"), 60);
        note("overlay-pan");

        long resetFx = fxMillis("reset", () -> graph.resetViewForTest());
        await("reset", () -> graph.segmentCountForTest(0) == 73
                && graph.viewportEndForTest() - graph.viewportStartForTest() >= 73L * SLOT
                && !graph.graphStatusForTest().startsWith("Loading"), 180);
        shot("overlay-live-overlay-reset.png");
        note("overlay-reset");
        long stackedAgain = fxMillis("stacked reset", () -> graph.setLayoutModeForTest(true));
        await("stacked reset", () -> GraphInvestigationState.STACKED.equals(graph.layoutModeForTest())
                && graph.segmentCountForTest(0) == 73, 60);

        System.out.println("OVERLAY_MODE_FX_MS=" + overlayFx);
        System.out.println("OVERLAY_SLOT_FX_MS=" + slotFx);
        System.out.println("OVERLAY_STACKED_FX_MS=" + stackedFx);
        System.out.println("OVERLAY_BACK_FX_MS=" + backFx);
        System.out.println("OVERLAY_ZOOM_FX_MS=" + zoomFx);
        System.out.println("OVERLAY_PAN_FX_MS=" + panFx);
        System.out.println("OVERLAY_RESET_FX_MS=" + resetFx);
        System.out.println("OVERLAY_STACKED_AGAIN_FX_MS=" + stackedAgain);
        System.out.println("OVERLAY_HOVER_FIT=" + overlayHover.replace('\n', ' '));
        System.out.println("OVERLAY_HOVER_DEEP=" + deepHover.replace('\n', ' '));
        assertTrue(overlayFx < 500, "overlay switch " + overlayFx);
        assertTrue(zoomFx < 500, "zoom " + zoomFx);
        assertTrue(panFx < 500, "pan " + panFx);
        assertTrue(resetFx < 500, "reset " + resetFx);
    }

    private static void note(String name) throws Exception {
        System.out.println("OVERLAY_VIEW " + name
                + " layout=" + text(() -> graph.layoutModeForTest())
                + " group=" + text(() -> graph.timelineGroupForTest())
                + " tracks=" + count(() -> graph.logicalTrackCountForTest())
                + " seg0=" + count(() -> graph.segmentCountForTest(0))
                + " seg1=" + count(() -> graph.segmentCountForTest(1))
                + " range=" + text(() -> graph.rangeTextForTest())
                + " status=" + text(() -> graph.graphStatusForTest()).replace('\n', ' '));
    }

    private static void shot(String name) throws Exception {
        Files.createDirectories(EVIDENCE);
        AtomicReference<WritableImage> image = new AtomicReference<>();
        fx("shot " + name, () -> image.set(scene.snapshot(null)));
        if (image.get() != null) {
            ImageIO.write(SwingFXUtils.fromFXImage(image.get(), null), "png", EVIDENCE.resolve(name).toFile());
        }
    }

    private static String hover() throws Exception {
        AtomicReference<String> found = new AtomicReference<>("");
        fx("hover", () -> {
            var canvas = graph.graphCanvasForTest();
            double[] size = canvas.innerCanvasSizeForTest();
            double y = size[1] > 1 ? size[2] + size[1] * 0.45 : 220;
            double width = size[0] > 1 ? size[0] : canvas.getWidth();
            for (double x = 120; x < width - 20; x += 40) {
                canvas.movePointerForTest(x, y);
                String cursor = graph.liveCursorForTest();
                if (cursor.startsWith("Pointer:") && !cursor.contains("Hover waveform")) {
                    found.set(cursor);
                    return;
                }
            }
            found.set(graph.liveCursorForTest());
        });
        return found.get();
    }

    private static SignalCatalogEntry exact(String name) throws Exception {
        AtomicReference<SignalCatalogEntry> found = new AtomicReference<>();
        fx("find " + name, () -> {
            for (SignalCatalogEntry entry : graph.explorerRowsForTest()) {
                if (name.equals(entry.signalName())) {
                    found.set(entry);
                    return;
                }
            }
        });
        assertTrue(found.get() != null, "missing " + name);
        return found.get();
    }

    private static SignalCatalogEntry named(String fragment) throws Exception {
        AtomicReference<SignalCatalogEntry> found = new AtomicReference<>();
        fx("find " + fragment, () -> {
            for (SignalCatalogEntry entry : graph.explorerRowsForTest()) {
                String signal = entry.signalName() == null ? "" : entry.signalName().toLowerCase();
                if (signal.contains(fragment) && entry.sampleCount() > 0) {
                    found.set(entry);
                    return;
                }
            }
        });
        assertTrue(found.get() != null, "missing " + fragment);
        return found.get();
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
                Stage stage = new Stage();
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

    private static MainShellController shell() {
        return (MainShellController) scene.getProperties().get("shell");
    }

    private static long fxMillis(String label, JavaFxTestSupport.FxWork work) throws Exception {
        AtomicLong elapsed = new AtomicLong();
        fx(label, () -> {
            long started = System.nanoTime();
            work.run();
            elapsed.set((System.nanoTime() - started) / 1_000_000L);
        });
        return elapsed.get();
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
        assertTrue(done.await(180, TimeUnit.SECONDS), "JavaFX scenario timed out (" + label + ")");
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
        throw new AssertionError("timed out waiting for " + label
                + " status=" + text(() -> graph.graphStatusForTest())
                + " range=" + text(() -> graph.rangeTextForTest()));
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
        AtomicReference<Long> value = new AtomicReference<>();
        fx("long", () -> value.set(read.get()));
        return value.get();
    }
}
