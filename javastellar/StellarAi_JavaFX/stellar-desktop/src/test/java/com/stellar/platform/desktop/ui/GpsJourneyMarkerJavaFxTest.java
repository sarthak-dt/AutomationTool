package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.AnalysisScope;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ProjectRouteReportLoader;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.project.ProjectInventoryJobState;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.map.MapRoutePane;
import com.stellar.platform.desktop.ui.graph.map.MapTimeFormat;
import com.stellar.platform.desktop.ui.graph.map.RouteCanvas;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.processing.map.ProjectRoute;
import com.stellar.platform.processing.map.ProjectRouteAssembler;
import com.stellar.platform.processing.map.RouteSegment;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Xenon DAT recordings, read only.
 * All Measurements shows one combined START and END. One file keeps its own ends.
 */
@Timeout(value = 20, unit = TimeUnit.MINUTES)
class GpsJourneyMarkerJavaFxTest {

    private static final Path DAT = Path.of(
            "C:\\Users\\DT - User\\Downloads\\DriveTechData\\dat files 3.1\\.dat files 3.1");
    private static final String FILE_A =
            "Xenon new script C910P41 2020-02-03 12-39-52-680142 Partition 0.dat";
    private static final String FILE_C =
            "Xenon new script C910P41 2020-02-04 13-42-03-688072 Partition 0.dat";
    private static final Path EVIDENCE = Path.of("D:\\DesktopApp\\DriveT\\docs\\qa-handoff\\bd21-gps-global-markers.txt");
    private static final StringBuilder NOTES = new StringBuilder();

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterAll
    static void flush() throws Exception {
        Files.createDirectories(EVIDENCE.getParent());
        Files.writeString(EVIDENCE, NOTES.toString());
    }

    @Test
    void realDatAllMeasurementsShowsOneCombinedStartAndEnd() throws Exception {
        note("started=" + Instant.now());
        Path pathA = DAT.resolve(FILE_A);
        Path pathC = DAT.resolve(FILE_C);
        assertTrue(Files.isRegularFile(pathA), pathA.toString());
        assertTrue(Files.isRegularFile(pathC), pathC.toString());
        String hashA = sha256(pathA);
        String hashC = sha256(pathC);
        note("source " + FILE_A + " size=" + Files.size(pathA) + " sha256=" + hashA);
        note("source " + FILE_C + " size=" + Files.size(pathC) + " sha256=" + hashC);

        Path home = Files.createTempDirectory("stellar-bd20-gps");
        Path db = home.resolve("meta.db");
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        System.setProperty("stellarai.metadata.db", db.toString());
        note("isolatedHome=" + home);
        DesktopAppService service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
        ShellFx shell = null;
        try {
            final ShellFx opened = boot(service);
            shell = opened;
            createProject(opened, service, "BD-20 GPS");
            addMeasurement(opened, pathA);
            addMeasurement(opened, pathC);
            awaitInventory(service, 8);
            directProcess(opened, service);
            ProjectProcessingStatus status = awaitProcessing(service, 8, 0);
            note("processingStatus=" + status);
            assertTrue(status == ProjectProcessingStatus.COMPLETED
                    || status == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS, "processing " + status);

            service.selectAnalysisScope(AnalysisScope.allMeasurements(2));
            runOnFx(() -> {
                opened.controller.openRoute(WorkspaceRoute.GRAPHS);
                GraphController graph = opened.controller.graphControllerForTest();
                graph.selectAnalysisScopeForTest(AnalysisScope.allMeasurements(2));
                graph.selectMapRouteTabForTest();
            });
            GraphController graph = opened.controller.graphControllerForTest();
            await("gps", () -> {
                MapRoutePane pane = graph.mapRoutePaneForTest();
                return pane != null && pane.gpsSegmentsForTest().size() >= 2
                        && pane.gpsSegmentsForTest().stream().allMatch(segment -> segment.sampleCount() >= 2);
            }, 180);

            MapRoutePane pane = graph.mapRoutePaneForTest();
            List<RouteSegment> segments = List.copyOf(pane.gpsSegmentsForTest());
            var loaded = new ProjectRouteReportLoader().load(service);
            assertNotNull(loaded, "GPS route was not built");
            ProjectRoute project = loaded.route();
            assertNotNull(project, "GPS route was empty");
            assertFalse(ProjectRouteAssembler.connectsAdjacentFiles(project), "recordings were joined into one journey");
            assertEquals(2, project.routeSegmentCount());
            long points = segments.stream().mapToLong(RouteSegment::sampleCount).sum();
            note("allRoutes status=" + one(pane.routeStatusForTest())
                    + " segments=" + segments.size()
                    + " points=" + points
                    + " connected=" + ProjectRouteAssembler.connectsAdjacentFiles(project));
            assertEquals(2, segments.size());
            assertTrue(points > 0);

            runOnFx(() -> pane.routeCanvasForTest().paintForTest());
            RouteCanvas canvas = pane.routeCanvasForTest();
            assertCombinedEnds(canvas, segments);
            note("allRoutes policy=" + canvas.endpointPolicyForTest()
                    + " overlays=" + canvas.overlayCountForTest()
                    + " marks=" + canvas.endpointMarksForTest().size()
                    + " " + evidenceLine("all-start", segments.getFirst())
                    + " " + evidenceLine("all-end", segments.getLast()));
            MapRouteModel distant = segments.getLast().route().model();
            double endY = canvas.viewport().y(distant.latitude(distant.size() - 1));
            note("frame endY=" + endY + " height=" + canvas.viewport().height()
                    + " distantStartLat=" + distant.latitude(0)
                    + " breaks=" + breakCount(distant));
            assertTrue(endY >= 0 && endY <= canvas.viewport().height(), "combined end left the frame y=" + endY);
            assertTrue(distant.segmentBreakBefore(1) || breakCount(distant) >= 129);
            shot(opened, "bd21-all-measurements.png");
            assertEquals(points, segments.stream().mapToLong(RouteSegment::sampleCount).sum());
            assertTrue(pane.mappingStatusForTest().contains("Recording order unknown"), pane.mappingStatusForTest());
            runOnFx(() -> {
                note("mapCombos=" + pane.lookupAll(".combo-box").size());
                note("measurementChoices=" + graph.measurementComboLabelsForTest());
                assertEquals(0, pane.lookupAll(".combo-box").size());
                assertTrue(graph.measurementComboLabelsForTest().stream()
                        .anyMatch(label -> label.contains("All Measurements")));
                assertTrue(graph.measurementComboLabelsForTest().size() >= 3);
            });

            RouteSegment selected = segments.getFirst();
            runOnFx(() -> graph.selectAnalysisScopeForTest(
                    AnalysisScope.single(selected.measurementId(), selected.displayName())));
            await("one recording", () -> {
                MapRoutePane current = graph.mapRoutePaneForTest();
                return current.gpsSegmentsForTest().isEmpty()
                        && current.routeSizeForTest() == selected.sampleCount();
            }, 180);
            runOnFx(() -> canvas.paintForTest());
            assertFalse(pane.routeIsolatedForTest());
            assertEquals(RouteCanvas.EndpointPolicy.OWN_ROUTE, canvas.endpointPolicyForTest());
            assertEquals(0, canvas.overlayCountForTest());
            assertEquals(2, canvas.endpointMarksForTest().size());
            assertTrue(canvas.endpointMarksForTest().stream()
                    .allMatch(mark -> selected.displayName().equals(mark.measurementName())));
            assertEndpoints(canvas, selected);
            RouteSegment other = segments.get(1);
            assertTrue(canvas.endpointMarksForTest().stream()
                    .noneMatch(mark -> other.displayName().equals(mark.measurementName())));
            note("selectedRoute=" + selected.displayName()
                    + " marks=" + canvas.endpointMarksForTest().size()
                    + " " + evidenceLine("selected", selected));
            shot(opened, "bd21-selected-route.png");

            RouteCanvas.EndpointMark start = canvas.endpointMarksForTest().getFirst();
            RouteCanvas.PaintedMarker before = markerFor(canvas, start);
            double beforeX = before.x();
            double beforeY = before.y();
            runOnFx(() -> {
                canvas.showMarkerAtForTest(beforeX, beforeY);
            });
            String tip = canvas.tooltipForTest().getText();
            note("tooltip=" + one(tip));
            assertTrue(tip.startsWith("START"), tip);
            assertTrue(tip.contains("Xenon new script"), tip);
            assertTrue(tip.contains(String.format(Locale.ROOT, "Latitude   %.7f", start.latitude())), tip);
            assertTrue(tip.contains(String.format(Locale.ROOT, "Longitude  %.7f", start.longitude())), tip);
            if (start.timestampNanos() != null) {
                assertTrue(tip.contains(MapTimeFormat.withMillis(start.timestampNanos())), tip);
            }

            runOnFx(() -> {
                canvas.viewport().panByPixels(60, -30);
                canvas.paintForTest();
            });
            RouteCanvas.PaintedMarker panned = markerFor(canvas, start);
            assertEquals(start.latitude(), canvas.endpointMarksForTest().getFirst().latitude(), 0);
            assertEquals(start.longitude(), canvas.endpointMarksForTest().getFirst().longitude(), 0);
            assertTrue(Math.abs(panned.x() - beforeX) > 1 || Math.abs(panned.y() - beforeY) > 1,
                    "pan left the start marker on the same screen pixel");
            assertEquals(canvas.viewport().x(start.longitude()), panned.x(), 0.5);
            assertEquals(canvas.viewport().y(start.latitude()), panned.y(), 0.5);
            note("pan attached x=" + panned.x() + " y=" + panned.y());

            runOnFx(() -> {
                canvas.zoomByLevels(2);
                canvas.paintForTest();
            });
            RouteCanvas.PaintedMarker zoomed = markerFor(canvas, start);
            assertEquals(canvas.viewport().x(start.longitude()), zoomed.x(), 0.5);
            assertEquals(canvas.viewport().y(start.latitude()), zoomed.y(), 0.5);
            note("zoom attached x=" + zoomed.x() + " y=" + zoomed.y());

            runOnFx(() -> graph.selectAnalysisScopeForTest(AnalysisScope.allMeasurements(2)));
            await("all recordings", () -> graph.mapRoutePaneForTest().gpsSegmentsForTest().size() == 2, 180);
            runOnFx(() -> {
                canvas.fitRoute();
                canvas.paintForTest();
            });
            assertCombinedEnds(canvas, segments);
            RouteCanvas.EndpointMark fittedEnd = canvas.endpointMarksForTest().get(1);
            RouteCanvas.PaintedMarker fitted = markerFor(canvas, fittedEnd);
            double dx = canvas.viewport().x(fittedEnd.longitude()) - fitted.x();
            double dy = canvas.viewport().y(fittedEnd.latitude()) - fitted.y();
            assertTrue(Math.hypot(dx, dy) <= 18.5,
                    "fitted end marker left its coordinate, gap=" + Math.hypot(dx, dy));
            String shared = canvas.paintedMarkersForTest().stream()
                    .filter(marker -> marker.endpoints().size() > 1)
                    .map(marker -> marker.endpoints().stream()
                            .map(mark -> mark.role() + " " + mark.measurementName())
                            .toList()
                            .toString())
                    .findFirst()
                    .orElse("");
            note("overlapDetail=" + one(shared));
            if (!shared.isEmpty()) {
                assertTrue(shared.contains(FILE_A) && shared.contains(FILE_C), shared);
            }
            assertEquals(points, pane.gpsSegmentsForTest().stream().mapToLong(RouteSegment::sampleCount).sum());
            note("fit attached and point count unchanged points=" + points);

            String projectId = service.currentProjectId();
            runOnFx(() -> buttonStarting(opened.root, "Save").fire());
            await("saved", () -> service.projectSaveState() != null && !service.projectSaveState().name().contains("SAVING"), 30);
            note("saveState=" + service.projectSaveState());
            close(shell);
            shell = null;
            service.shutdown();

            DesktopAppService reopened = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
            ShellFx again = boot(reopened);
            try {
                runOnFx(() -> again.controller.requestOpenSavedProject(projectId));
                await("reopen", () -> reopened.workspace().measurementFiles().size() == 2, 90);
                GraphController reopenedGraph = again.controller.graphControllerForTest();
                runOnFx(() -> {
                    again.controller.openRoute(WorkspaceRoute.GRAPHS);
                    reopenedGraph.selectAnalysisScopeForTest(AnalysisScope.allMeasurements(2));
                    reopenedGraph.selectMapRouteTabForTest();
                });
                await("reopen gps", () -> {
                    MapRoutePane restored = reopenedGraph.mapRoutePaneForTest();
                    return restored != null && restored.gpsSegmentsForTest().size() == 2;
                }, 180);
                MapRoutePane restored = reopenedGraph.mapRoutePaneForTest();
                runOnFx(() -> restored.routeCanvasForTest().paintForTest());
                var reloaded = new ProjectRouteReportLoader().load(reopened);
                assertNotNull(reloaded, "reopened GPS route was not built");
                ProjectRoute restoredRoute = reloaded.route();
                assertNotNull(restoredRoute, "reopened GPS route was empty");
                assertEquals(points, restored.gpsSegmentsForTest().stream().mapToLong(RouteSegment::sampleCount).sum());
                assertFalse(ProjectRouteAssembler.connectsAdjacentFiles(restoredRoute));
                assertCombinedEnds(restored.routeCanvasForTest(), restored.gpsSegmentsForTest());
                for (RouteSegment segment : segments) {
                    RouteSegment match = restored.gpsSegmentsForTest().stream()
                            .filter(candidate -> segment.displayName().equals(candidate.displayName()))
                            .findFirst()
                            .orElseThrow();
                    assertEquals(segment.sampleCount(), match.sampleCount(), segment.displayName());
                    note(evidenceLine("reopen", match));
                }
                shot(again, "bd21-reopen-all-measurements.png");
                note("reopen points=" + restored.gpsSegmentsForTest().stream().mapToLong(RouteSegment::sampleCount).sum());
            } finally {
                close(again);
                reopened.shutdown();
            }
            assertEquals(hashA, sha256(pathA));
            assertEquals(hashC, sha256(pathC));
            note("hashUnchanged=true");
            note("decision=PASS");
        } finally {
            if (shell != null) {
                close(shell);
            }
            service.shutdown();
            note("finished=" + Instant.now());
            Files.createDirectories(EVIDENCE.getParent());
            Files.writeString(EVIDENCE, NOTES.toString());
        }
    }

    private static int breakCount(MapRouteModel model) {
        int breaks = 0;
        for (int i = 1; i < model.size(); i++) {
            if (model.segmentBreakBefore(i)) {
                breaks++;
            }
        }
        return breaks;
    }

    private static void assertCombinedEnds(RouteCanvas canvas, List<RouteSegment> segments) {
        assertEquals(RouteCanvas.EndpointPolicy.GLOBAL, canvas.endpointPolicyForTest());
        assertEquals(segments.size(), canvas.overlayCountForTest());
        assertEquals(2, canvas.endpointMarksForTest().size());
        RouteSegment first = segments.getFirst();
        RouteSegment last = segments.getLast();
        MapRouteModel startModel = first.route().model();
        MapRouteModel endModel = last.route().model();
        RouteCanvas.EndpointMark start = find(canvas, first.displayName(), "START");
        RouteCanvas.EndpointMark end = find(canvas, last.displayName(), "END");
        assertEquals(startModel.latitude(0), start.latitude(), 0, first.displayName());
        assertEquals(startModel.longitude(0), start.longitude(), 0, first.displayName());
        assertEquals(startModel.timestampNanos(0), start.timestampNanos());
        int endIndex = endModel.size() - 1;
        assertEquals(endModel.latitude(endIndex), end.latitude(), 0, last.displayName());
        assertEquals(endModel.longitude(endIndex), end.longitude(), 0, last.displayName());
        assertEquals(endModel.timestampNanos(endIndex), end.timestampNanos());
        assertEquals(1, canvas.endpointMarksForTest().stream().filter(mark -> "START".equals(mark.role())).count());
        assertEquals(1, canvas.endpointMarksForTest().stream().filter(mark -> "END".equals(mark.role())).count());
    }

    private static void assertEndpoints(RouteCanvas canvas, RouteSegment segment) {
        MapRouteModel model = segment.route().model();
        assertTrue(model.size() >= 2, segment.displayName());
        RouteCanvas.EndpointMark start = find(canvas, segment.displayName(), "START");
        RouteCanvas.EndpointMark end = find(canvas, segment.displayName(), "END");
        assertEquals(model.latitude(0), start.latitude(), 0, segment.displayName());
        assertEquals(model.longitude(0), start.longitude(), 0, segment.displayName());
        assertEquals(model.latitude(model.size() - 1), end.latitude(), 0, segment.displayName());
        assertEquals(model.longitude(model.size() - 1), end.longitude(), 0, segment.displayName());
        assertEquals(model.timestampNanos(0), start.timestampNanos());
        assertEquals(model.timestampNanos(model.size() - 1), end.timestampNanos());
    }

    private static RouteCanvas.EndpointMark find(RouteCanvas canvas, String name, String role) {
        return canvas.endpointMarksForTest().stream()
                .filter(mark -> role.equals(mark.role()) && name.equals(mark.measurementName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(role + " missing for " + name + " marks=" + canvas.endpointMarksForTest()));
    }

    private static RouteCanvas.PaintedMarker markerFor(RouteCanvas canvas, RouteCanvas.EndpointMark mark) {
        return canvas.paintedMarkersForTest().stream()
                .filter(painted -> painted.endpoints().stream().anyMatch(endpoint ->
                        mark.role().equals(endpoint.role())
                                && mark.measurementName().equals(endpoint.measurementName())))
                .findFirst()
                .orElseThrow();
    }

    private static String evidenceLine(String stage, RouteSegment segment) {
        MapRouteModel model = segment.route().model();
        int last = model.size() - 1;
        return stage + " file=" + segment.displayName()
                + " points=" + model.size()
                + " startLat=" + model.latitude(0)
                + " startLon=" + model.longitude(0)
                + " startT=" + model.timestampNanos(0)
                + " endLat=" + model.latitude(last)
                + " endLon=" + model.longitude(last)
                + " endT=" + model.timestampNanos(last);
    }

    private static void directProcess(ShellFx shell, DesktopAppService service) throws Exception {
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.SOURCES);
            shell.root.applyCss();
            shell.root.layout();
            var list = shell.controller.projectFilesControllerForTest().measurementSourceListForTest();
            list.getItems().stream()
                    .filter(file -> "MEASUREMENT_DAT".equals(file.kind().name()))
                    .findFirst()
                    .ifPresent(file -> {
                        list.getSelectionModel().select(file);
                        service.selectMeasurement(file.id());
                    });
            shell.root.applyCss();
            shell.root.layout();
            Button direct = shell.controller.projectFilesControllerForTest().continueToDecodeButtonForTest();
            assertNotNull(direct, "direct processing");
            assertEquals("Direct Processing", direct.getText());
            assertFalse(direct.isDisabled(), direct.getText());
            direct.fire();
        });
    }

    private static void addMeasurement(ShellFx shell, Path file) throws Exception {
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.SOURCES);
            shell.root.applyCss();
            shell.root.layout();
            shell.controller.projectFilesControllerForTest().setOpenDialogForTest(chooser -> List.of(file.toFile()));
            Button add = button(shell.root, "+ Add Measurement Files");
            if (add == null) {
                add = button(shell.root, "Browse Files");
            }
            assertNotNull(add, "add measurement");
            add.fire();
        });
    }

    private static ShellFx boot(DesktopAppService service) throws Exception {
        AtomicReference<Stage> stage = new AtomicReference<>();
        AtomicReference<MainShellController> controller = new AtomicReference<>();
        AtomicReference<Parent> root = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch booted = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                Parent page = loader.load();
                MainShellController shell = loader.getController();
                Stage window = new Stage();
                window.setScene(new Scene(page, 1440, 900));
                window.show();
                shell.init(service);
                page.applyCss();
                page.layout();
                stage.set(window);
                controller.set(shell);
                root.set(page);
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                booted.countDown();
            }
        });
        assertTrue(booted.await(60, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return new ShellFx(stage.get(), controller.get(), root.get());
    }

    private static void createProject(ShellFx shell, DesktopAppService service, String name) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread opener = new Thread(() -> {
            try {
                runOnFx(() -> {
                    shell.controller.openRoute(WorkspaceRoute.HOME);
                    Button button = button(shell.root, "New Project");
                    assertNotNull(button, "New Project");
                    button.fire();
                });
            } catch (Throwable thrown) {
                error.set(thrown);
            }
        });
        opener.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(runOnFxValue(() -> fillDialog(name)))) {
                break;
            }
            Thread.sleep(50);
        }
        opener.join(20_000);
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        await("project", () -> name.equals(service.workspace().projectName()), 20);
    }

    private static boolean fillDialog(String name) {
        for (javafx.stage.Window window : JavaFxTestSupport.snapshotWindows()) {
            if (!(window instanceof Stage stage) || stage.getScene() == null) {
                continue;
            }
            Parent pane = stage.getScene().getRoot();
            boolean matched = false;
            for (Node node : pane.lookupAll(".text-field")) {
                if (node instanceof javafx.scene.control.TextField field) {
                    field.setText(name);
                    matched = true;
                }
            }
            if (!matched) {
                continue;
            }
            Button ok = button(pane, "OK");
            if (ok != null) {
                ok.fire();
                return true;
            }
        }
        return false;
    }

    private static void awaitInventory(DesktopAppService service, int minutes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(minutes);
        while (System.nanoTime() < deadline) {
            List<WorkspaceFile> files = service.workspace().measurementFiles();
            if (files.size() >= 2 && files.stream().allMatch(file -> {
                ProjectInventoryJobState state = service.inventoryJobState(file.projectSourceId());
                return state == ProjectInventoryJobState.READY
                        || state == ProjectInventoryJobState.FAILED
                        || state == ProjectInventoryJobState.UNSUPPORTED
                        || state == ProjectInventoryJobState.REQUIRES_ATTENTION;
            })) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("inventory did not settle");
    }

    private static ProjectProcessingStatus awaitProcessing(DesktopAppService service, int minutes, int runsBefore) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(minutes);
        while (System.nanoTime() < deadline) {
            var journey = service.projectJourneyState();
            boolean newRun = service.persistedProcessingRuns().size() > runsBefore;
            ProjectProcessingStatus status = service.workspace().processingStatus();
            if (newRun && status != ProjectProcessingStatus.RUNNING
                    && status != ProjectProcessingStatus.IDLE
                    && status != ProjectProcessingStatus.READY
                    && journey != null && journey.analysisAvailable()
                    && !service.signalsModel().catalog().entries().isEmpty()) {
                return status;
            }
            if (newRun && (status == ProjectProcessingStatus.FAILED
                    || status == ProjectProcessingStatus.UNSUPPORTED_STRUCTURE
                    || status == ProjectProcessingStatus.CANCELLED)) {
                return status;
            }
            Thread.sleep(400);
        }
        return service.workspace().processingStatus();
    }

    private static void await(String label, java.util.function.BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("timeout " + label);
    }

    private static void runOnFx(JavaFxTestSupport.FxWork work) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                work.run();
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static <T> T runOnFxValue(java.util.function.Supplier<T> work) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                value.set(work.get());
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return value.get();
    }

    private static void close(ShellFx shell) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            shell.stage.close();
            done.countDown();
        });
        done.await(10, TimeUnit.SECONDS);
    }

    private static Button button(Parent root, String label) {
        for (Node node : root.lookupAll(".button")) {
            if (!(node instanceof Button button)) {
                continue;
            }
            String text = button.getText() == null ? "" : button.getText();
            if (text.contains(label)) {
                return button;
            }
            for (Node child : button.lookupAll(".label")) {
                if (child instanceof Labeled labeled && labeled.getText() != null && labeled.getText().contains(label)) {
                    return button;
                }
            }
        }
        return null;
    }

    private static Button buttonStarting(Parent root, String label) {
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button button && button.getText() != null && button.getText().startsWith(label)) {
                return button;
            }
        }
        return null;
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))).toUpperCase(Locale.ROOT);
    }

    private static void shot(ShellFx shell, String name) throws Exception {
        AtomicReference<WritableImage> image = new AtomicReference<>();
        runOnFx(() -> image.set(shell.stage.getScene().snapshot(null)));
        if (image.get() == null) {
            note("screenshotMissing=" + name);
            return;
        }
        Path out = EVIDENCE.getParent().resolve(name);
        Files.createDirectories(out.getParent());
        ImageIO.write(SwingFXUtils.fromFXImage(image.get(), null), "png", out.toFile());
        note("screenshot=" + out);
    }

    private static void note(String line) {
        NOTES.append(line).append('\n');
    }

    private static String one(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private record ShellFx(Stage stage, MainShellController controller, Parent root) {
    }
}
