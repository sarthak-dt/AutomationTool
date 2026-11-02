package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.map.MapRoutePane;
import com.stellar.platform.desktop.ui.graph.map.RouteCanvas;
import com.stellar.platform.processing.map.RouteSegment;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real 73-file Xenon project. All routes stay disconnected and unnumbered.
 * One chosen recording keeps its own START and END.
 */
@Timeout(value = 20, unit = TimeUnit.MINUTES)
class MapRouteLiveTest {

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
    void seventyThreeFilesShowIndependentRoutesWithoutNumberedPins() throws Exception {
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
        long started = System.nanoTime();
        fx("map tab", () -> graph.selectMapRouteTab());
        await("routes", () -> {
            String status = map().routeStatusForTest();
            return status.contains("Recording order unknown") && status.contains("GPS-capable");
        }, 180);
        long allRoutesMs = (System.nanoTime() - started) / 1_000_000L;
        MapRoutePane map = map();
        RouteCanvas canvas = canvas();
        assertEquals(RouteCanvas.EndpointPolicy.GLOBAL, canvas.endpointPolicyForTest());
        assertEquals(2, canvas.endpointMarksForTest().size());
        assertEquals("START", canvas.endpointMarksForTest().get(0).role());
        assertEquals("END", canvas.endpointMarksForTest().get(1).role());
        assertTrue(map.routeStatusForTest().contains("Recording order unknown"), map.routeStatusForTest());
        assertTrue(map.mappingStatusForTest().contains("Recording order unknown")
                || map.routeStatusForTest().contains("Recording order unknown"));
        shot("map-all-routes.png");
        AtomicReference<RouteSegment> chosen = new AtomicReference<>();
        fx("pick", () -> {
            for (RouteSegment segment : map().gpsSegmentsForTest()) {
                if (segment.hasRoute() && segment.route().model().size() > 1) {
                    chosen.set(segment);
                    return;
                }
            }
        });
        assertTrue(chosen.get() != null, "expected one GPS route with a start and an end");
        long oneStarted = System.nanoTime();
        fx("one route", () -> map().showOnly(chosen.get()));
        long oneRouteMs = (System.nanoTime() - oneStarted) / 1_000_000L;
        RouteCanvas one = canvas();
        assertEquals(RouteCanvas.EndpointPolicy.OWN_ROUTE, one.endpointPolicyForTest());
        assertEquals(2, one.endpointMarksForTest().size());
        assertEquals("START", one.endpointMarksForTest().get(0).role());
        assertEquals("END", one.endpointMarksForTest().get(1).role());
        assertTrue(map().routeIsolatedForTest());
        assertTrue(map().routeStatusForTest().contains("GPS samples"), map().routeStatusForTest());
        shot("map-one-route.png");
        fx("all again", () -> map().showAllRoutes());
        RouteCanvas restored = canvas();
        assertEquals(RouteCanvas.EndpointPolicy.GLOBAL, restored.endpointPolicyForTest());
        assertEquals(2, restored.endpointMarksForTest().size());
        Files.writeString(EVIDENCE.resolve("map-all-routes-ms.txt"),
                "allRoutesMs=" + allRoutesMs + "\noneRouteMs=" + oneRouteMs
                        + "\nstatus=" + map().routeStatusForTest());
    }

    private static MapRoutePane map() {
        return graph.mapRoutePaneForTest();
    }

    private static RouteCanvas canvas() throws Exception {
        AtomicReference<RouteCanvas> view = new AtomicReference<>();
        fx("canvas", () -> {
            view.set(map().routeCanvasForTest());
            view.get().paintForTest();
        });
        return view.get();
    }

    private static void shot(String name) throws Exception {
        Files.createDirectories(EVIDENCE);
        AtomicReference<WritableImage> image = new AtomicReference<>();
        fx("shot " + name, () -> image.set(scene.snapshot(null)));
        if (image.get() != null) {
            ImageIO.write(SwingFXUtils.fromFXImage(image.get(), null), "png", EVIDENCE.resolve(name).toFile());
        }
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
            Thread.sleep(250L);
        }
        throw new AssertionError("timed out waiting for " + label + " status=" + text());
    }

    private static String text() throws Exception {
        AtomicReference<String> value = new AtomicReference<>();
        fx("text", () -> value.set(map().routeStatusForTest()));
        return value.get() == null ? "" : value.get();
    }
}
