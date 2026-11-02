package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphInvestigationState;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.graph.timeline.UnknownOrderLayout;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Not Responding window was a mouse click scanning every sample on the JavaFX thread.
 * This drives that click on the isolated 73-file project and times the UI thread itself.
 */
@Timeout(value = 20, unit = TimeUnit.MINUTES)
class GraphClickResponsivenessLiveTest {

    private static final long SLOT = UnknownOrderLayout.EQUAL_SLOT_SPAN;
    private static final int SLOT_INDEX = 37;
    private static final long FX_BUDGET_MS = 500L;
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
    void clickOnUnknownOrderSlotStaysOnTheUiThread() throws Exception {
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
        long focusFx = fxMillis("focus speed", () -> graph.fireExplorerCheckboxForTest(speed.key()));
        await("one track", () -> graph.logicalTrackCountForTest() == 1
                && graph.segmentCountForTest(0) == 73
                && !graph.graphStatusForTest().startsWith("Loading"), 180);
        long focusRowFx = fxMillis("focus row", () -> graph.focusSignalByNameForTest("IN_Vehicle_speed"));
        SignalCatalogKey focused = key(() -> graph.focusedExplorerKeyForTest());
        assertEquals(speed.key(), focused);

        long slotFx = fxMillis("slot 38", () -> {
            graph.setViewportForTest(SLOT_INDEX * SLOT, (SLOT_INDEX + 1) * SLOT);
            graph.reloadViewportForTest();
        });
        await("slot ready", () -> {
            int segments = graph.segmentCountForTest(0);
            String range = graph.rangeTextForTest();
            return segments >= 1 && segments <= 3
                    && range.contains("Slots 38")
                    && range.contains("of 73")
                    && range.contains("not elapsed time")
                    && !graph.graphStatusForTest().startsWith("Loading");
        }, 120);
        long zoomFx = fxMillis("zoom burst", () -> {
            for (int i = 0; i < 8; i++) {
                graph.zoomInForTest();
            }
        });
        await("zoomed", () -> {
            int segments = graph.segmentCountForTest(0);
            return segments >= 1 && segments <= 3
                    && graph.viewportEndForTest() - graph.viewportStartForTest() < SLOT
                    && !graph.graphStatusForTest().startsWith("Loading");
        }, 120);
        int zoomedSegments = countSegments();

        long[] clicks = new long[5];
        for (int i = 0; i < clicks.length; i++) {
            clicks[i] = clickCenter();
            assertTrue(clicks[i] < FX_BUDGET_MS, "click " + i + " blocked the UI thread for " + clicks[i] + " ms");
        }
        AtomicReference<Long> cursor = new AtomicReference<>();
        fx("cursor", () -> cursor.set(graph.cursorAForTest()));
        assertTrue(cursor.get() != null, "click did not place cursor A");
        long hoverFx = fxMillis("hover", () -> moveCenter());
        String hover = text(() -> graph.liveCursorForTest());
        assertTrue(hover.startsWith("Pointer:"), hover);
        assertFalse(hover.contains("UTC"), hover);

        long beforePan = value(() -> graph.viewportStartForTest());
        long panFx = fxMillis("pan", () -> graph.panRightForTest());
        await("panned", () -> graph.viewportStartForTest() != beforePan
                && !graph.graphStatusForTest().startsWith("Loading"), 60);

        long overlayFx = fxMillis("overlay", () -> graph.setLayoutModeForTest(false));
        await("overlay mode", () -> GraphInvestigationState.OVERLAY.equals(graph.layoutModeForTest()), 30);
        long overlayClick = clickCenter();
        long stackedFx = fxMillis("stacked", () -> graph.setLayoutModeForTest(true));
        await("stacked mode", () -> GraphInvestigationState.STACKED.equals(graph.layoutModeForTest()), 30);

        SignalCatalogEntry coolant = named("coolant");
        long secondFx = fxMillis("second signal", () -> graph.fireExplorerCheckboxForTest(coolant.key()));
        await("two tracks", () -> graph.logicalTrackCountForTest() >= 2
                && !graph.graphStatusForTest().startsWith("Loading"), 180);
        long multiClick = clickCenter();

        long resetFx = fxMillis("reset", () -> graph.resetViewForTest());
        await("reset", () -> graph.segmentCountForTest(0) == 73
                && graph.viewportEndForTest() - graph.viewportStartForTest() >= 73L * SLOT
                && !graph.graphStatusForTest().startsWith("Loading"), 180);

        long scanWaitStart = System.nanoTime();
        await("speed summary cached", () -> graph.categoricalCachedForTest(speed.key()), 120);
        long scanWaitMs = (System.nanoTime() - scanWaitStart) / 1_000_000L;
        long cachedClick = clickCenter();
        String inspector = text(() -> graph.inspectorTextForTest());
        assertFalse(inspector.contains("Categorical"), inspector);
        assertTrue(inspector.contains("IN_Vehicle_speed") || inspector.contains("Vehicle_speed"), inspector);

        long worstClick = clicks[0];
        for (long click : clicks) {
            worstClick = Math.max(worstClick, click);
        }
        System.out.println("P0_FOCUS_FX_MS=" + focusFx);
        System.out.println("P0_FOCUS_ROW_FX_MS=" + focusRowFx);
        System.out.println("P0_SLOT_FX_MS=" + slotFx);
        System.out.println("P0_ZOOM_BURST_FX_MS=" + zoomFx);
        System.out.println("P0_CLICK_FX_MS=" + clicks[0] + "," + clicks[1] + "," + clicks[2] + "," + clicks[3] + "," + clicks[4]);
        System.out.println("P0_CLICK_WORST_FX_MS=" + worstClick);
        System.out.println("P0_HOVER_FX_MS=" + hoverFx);
        System.out.println("P0_PAN_FX_MS=" + panFx);
        System.out.println("P0_OVERLAY_FX_MS=" + overlayFx);
        System.out.println("P0_OVERLAY_CLICK_FX_MS=" + overlayClick);
        System.out.println("P0_STACKED_FX_MS=" + stackedFx);
        System.out.println("P0_SECOND_FX_MS=" + secondFx);
        System.out.println("P0_MULTI_CLICK_FX_MS=" + multiClick);
        System.out.println("P0_RESET_FX_MS=" + resetFx);
        System.out.println("P0_SCAN_WAIT_MS=" + scanWaitMs);
        System.out.println("P0_CACHED_CLICK_FX_MS=" + cachedClick);
        System.out.println("P0_ZOOMED_SEGMENTS=" + zoomedSegments);
        System.out.println("P0_RANGE=" + text(() -> graph.rangeTextForTest()));
        System.out.println("P0_STATUS=" + text(() -> graph.graphStatusForTest()).replace('\n', ' '));
        System.out.println("P0_HOVER=" + hover.replace('\n', ' '));
        assertTrue(worstClick < FX_BUDGET_MS);
        assertTrue(overlayClick < FX_BUDGET_MS, "overlay click " + overlayClick);
        assertTrue(multiClick < FX_BUDGET_MS, "multi click " + multiClick);
        assertTrue(cachedClick < FX_BUDGET_MS, "cached click " + cachedClick);
        assertTrue(zoomFx < FX_BUDGET_MS, "zoom " + zoomFx);
        assertTrue(panFx < FX_BUDGET_MS, "pan " + panFx);
        assertTrue(resetFx < FX_BUDGET_MS, "reset " + resetFx);
        assertTrue(hoverFx < FX_BUDGET_MS, "hover " + hoverFx);
    }

    private static int countSegments() throws Exception {
        AtomicReference<Integer> value = new AtomicReference<>();
        fx("segments", () -> value.set(graph.segmentCountForTest(0)));
        return value.get();
    }

    private static long clickCenter() throws Exception {
        return fxMillis("click", GraphClickResponsivenessLiveTest::fireClick);
    }

    private static void fireClick() {
        var canvas = graph.graphCanvasForTest();
        double[] size = canvas.innerCanvasSizeForTest();
        double width = size[0] > 1 ? size[0] : Math.max(200, canvas.getWidth());
        double x = Math.max(80, width * 0.55);
        double y = size[1] > 1 ? size[2] + size[1] * 0.45 : Math.max(40, canvas.getHeight() * 0.45);
        javafx.geometry.Point2D scenePoint = canvas.localToScene(x, y);
        double sx = scenePoint == null ? x : scenePoint.getX();
        double sy = scenePoint == null ? y : scenePoint.getY();
        canvas.fireEvent(new MouseEvent(
                canvas, canvas, MouseEvent.MOUSE_PRESSED,
                sx, sy, sx, sy, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null));
        canvas.fireEvent(new MouseEvent(
                canvas, canvas, MouseEvent.MOUSE_RELEASED,
                sx, sy, sx, sy, MouseButton.PRIMARY, 1,
                false, false, false, false, false, false, false, true, false, false, null));
    }

    private static void moveCenter() {
        var canvas = graph.graphCanvasForTest();
        double[] size = canvas.innerCanvasSizeForTest();
        double width = size[0] > 1 ? size[0] : Math.max(200, canvas.getWidth());
        double x = Math.max(80, width * 0.55);
        double y = size[1] > 1 ? size[2] + size[1] * 0.45 : Math.max(40, canvas.getHeight() * 0.45);
        canvas.movePointerForTest(x, y);
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
                String name = entry.signalName() == null ? "" : entry.signalName().toLowerCase();
                if (name.contains(fragment) && entry.sampleCount() > 0) {
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

    private static long value(java.util.function.Supplier<Long> read) throws Exception {
        AtomicReference<Long> value = new AtomicReference<>();
        fx("long", () -> value.set(read.get()));
        return value.get();
    }

    private static SignalCatalogKey key(java.util.function.Supplier<SignalCatalogKey> read) throws Exception {
        AtomicReference<SignalCatalogKey> value = new AtomicReference<>();
        fx("key", () -> value.set(read.get()));
        return value.get();
    }
}
