package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.model.AnalysisRequest;
import com.stellar.platform.analysis.model.AnalysisResult;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.AnalysisType;
import com.stellar.platform.analysis.model.SignalStatistics;
import com.stellar.platform.desktop.presentation.AnalysisScope;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ProcessingWarningReport;
import com.stellar.platform.desktop.presentation.ProjectRouteReportLoader;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.project.ProjectInventoryJobState;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.map.RouteSegment;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
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
 * Real Vehicle Spy MDF 3.30 recordings supplied with a .log extension.
 * asammdf 8.8.27 is the external sample oracle. The files are only read.
 */
@Timeout(value = 25, unit = TimeUnit.MINUTES)
class LogMeasurementJavaFxAcceptanceTest {

    private static final Path DIR = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\.log");
    private static final Path EVIDENCE = Path.of("D:\\DesktopApp\\DriveT\\docs\\qa-handoff\\log-javafx-acceptance.txt");
    private static final String FOCUS = "12-03-25-688233";
    private static final String REMOVED = "13-21-33-780996";
    private static final StringBuilder NOTES = new StringBuilder();
    private static boolean lifecycleRestorePassed;

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
    void realLogFilesThroughTheDesktop() throws Exception {
        note("started=" + Instant.now());
        List<Path> files = Files.list(DIR).filter(path -> path.getFileName().toString().endsWith(".log")).sorted().toList();
        assertEquals(4, files.size());
        for (Path file : files) {
            note("source name=" + file.getFileName() + " size=" + Files.size(file) + " sha256=" + sha256(file));
        }
        Path home = Files.createTempDirectory("stellar-log-javafx");
        Path db = home.resolve("meta.db");
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        System.setProperty("stellarai.metadata.db", db.toString());
        note("isolatedHome=" + home);
        DesktopAppService service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
        ShellFx shell = null;
        try {
            shell = boot(service);
            createProject(shell, service, "LOG Xenon");
            for (Path file : files) {
                addMeasurement(shell, file);
            }
            awaitInventory(service, 8);
            for (WorkspaceFile file : service.workspace().measurementFiles()) {
                note("import name=" + file.fileName()
                        + " kind=" + file.kind()
                        + " detected=" + file.detectedFormatLabel()
                        + " status=" + file.status()
                        + " inventory=" + service.inventoryJobState(file.projectSourceId()));
                assertEquals("MEASUREMENT_LOG", file.kind().name(), file.fileName());
                assertEquals(ProjectInventoryJobState.READY, service.inventoryJobState(file.projectSourceId()), file.fileName());
            }
            directProcess(shell, service);
            ProjectProcessingStatus status = awaitProcessing(service, 3, 0);
            note("processingStatus=" + status);
            note("processingMessage=" + service.workspace().latestSummary().map(summary -> summary.userMessage()).orElse(""));
            ProcessingWarningReport warnings = service.processingWarningReport();
            note("warningCount=" + warnings.count());
            for (ProcessingWarningReport.Item item : warnings.items()) {
                note("warning category=" + item.category() + " detail=" + one(item.technicalDetail()));
            }
            assertTrue(status == ProjectProcessingStatus.COMPLETED
                    || status == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS, "processing " + status);
            note("catalogSize=" + service.signalsModel().catalog().entries().size());
            service.signalsModel().catalog().entries().stream().limit(12).forEach(entry ->
                    note("catalog signal=" + entry.signalName()
                            + " source=" + entry.sourceFile()
                            + " count=" + entry.sampleCount()
                            + " unit=" + entry.unit()));
            compare(service, FOCUS, "IN_Vehicle_speed", 9638, 25.640625, "km/h", null);
            compare(service, FOCUS, "IN_Coolant_temperature", 9637, 94.25, "degC", null);
            compare(service, FOCUS, "ETC_Fan2_output", 9637, Double.NaN, "", "FALSE");
            compare(service, "12-29-28-330439", "IN_Vehicle_speed", 9637, 60.53125, "km/h", null);
            compare(service, "12-55-31-240431", "IN_Vehicle_speed", 9638, 42.8359375, "km/h", null);
            compare(service, REMOVED, "IN_Vehicle_speed", 9638, 55.4921875, "km/h", null);
            long focusSpeed = count(service, FOCUS, "IN_Vehicle_speed");
            graph(shell, service);
            gps(shell, service);
            analytics(service);
            html(shell, home);
            removeAndRestore(shell, service, focusSpeed);
            saveAndReopen(shell, service, db, home);
            shell = null;
            assertTrue(lifecycleRestorePassed, "re-added LOG was not decoded again");
            note("decision=PASS");
        } finally {
            if (shell != null) {
                close(shell);
            }
            service.shutdown();
            for (Path file : files) {
                note("hashUnchanged " + file.getFileName() + "=" + sha256(file));
            }
            note("finished=" + Instant.now());
            Files.createDirectories(EVIDENCE.getParent());
            Files.writeString(EVIDENCE, NOTES.toString());
        }
    }

    private static void compare(DesktopAppService service, String filePart, String signal,
                                long expectedCount, double physical, String unit, String text) {
        SignalCatalogEntry entry = physical(service, filePart, signal);
        AtomicReference<SignalSamplePoint> first = new AtomicReference<>();
        service.signalsModel().catalogStore().forEachSample(entry.key(), sample -> {
            if (first.get() == null) {
                first.set(sample);
            }
        });
        SignalSamplePoint sample = first.get();
        assertNotNull(sample, signal);
        note("sample file=" + filePart
                + " signal=" + signal
                + " count=" + entry.sampleCount()
                + " unit=" + entry.unit()
                + " source=" + entry.sourceFile()
                + " t0=" + sample.timestampNanos()
                + " raw=" + sample.rawValue()
                + " physical=" + sample.physicalValue()
                + " text=" + sample.textualValue()
                + " validity=" + sample.validity());
        assertEquals(expectedCount, entry.sampleCount(), signal + " " + filePart);
        assertTrue(entry.sourceFile().contains(filePart), entry.sourceFile());
        if (text != null) {
            assertEquals(text, sample.textualValue());
        } else {
            assertEquals(physical, sample.physicalValue(), 1e-9, signal);
            assertTrue(entry.unit() == null || entry.unit().isBlank() || entry.unit().equals(unit), entry.unit());
        }
    }

    private static long count(DesktopAppService service, String filePart, String signal) {
        ProjectMergedSignalStore merged = (ProjectMergedSignalStore) service.signalsModel().catalogStore();
        return merged.physicalCatalogAll().stream()
                .filter(item -> signal.equals(item.signalName()) && item.sourceFile().contains(filePart))
                .mapToLong(SignalCatalogEntry::sampleCount)
                .findFirst()
                .orElse(0L);
    }

    private static String speedCounts(DesktopAppService service) {
        ProjectMergedSignalStore merged = (ProjectMergedSignalStore) service.signalsModel().catalogStore();
        if (merged == null) {
            return "[]";
        }
        return merged.physicalCatalogAll().stream()
                .filter(item -> "IN_Vehicle_speed".equals(item.signalName()))
                .map(item -> item.sourceFile() + "=" + item.sampleCount())
                .toList()
                .toString();
    }

    private static SignalCatalogEntry physical(DesktopAppService service, String filePart, String signal) {
        ProjectMergedSignalStore merged = (ProjectMergedSignalStore) service.signalsModel().catalogStore();
        return merged.physicalCatalogAll().stream()
                .filter(item -> signal.equals(item.signalName()) && item.sourceFile().contains(filePart))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + signal + " in " + filePart));
    }

    private static void graph(ShellFx shell, DesktopAppService service) throws Exception {
        WorkspaceFile focus = service.workspace().measurementFiles().stream()
                .filter(file -> file.fileName().contains(FOCUS))
                .findFirst()
                .orElseThrow();
        runOnFx(() -> shell.controller.openRoute(WorkspaceRoute.GRAPHS));
        GraphController graph = shell.controller.graphControllerForTest();
        runOnFx(() -> {
            graph.selectMeasurementForTest(focus.id());
            for (String name : List.of("IN_Vehicle_speed", "IN_Coolant_temperature")) {
                var match = graph.graphCatalogForTest().stream()
                        .filter(item -> name.equals(item.signalName()) && item.sourceFile().contains(FOCUS))
                        .findFirst()
                        .or(() -> graph.graphCatalogForTest().stream()
                                .filter(item -> name.equals(item.signalName()))
                                .findFirst());
                match.ifPresent(item -> {
                    if (!graph.isSelectedForTest(item.key())) {
                        graph.fireExplorerCheckboxForTest(item.key());
                    }
                });
            }
            graph.setLayoutModeForTest(true);
        });
        await("stacked", () -> "STACKED".equals(graph.layoutModeForTest())
                && graph.selectedSignalCountForTest() == 2
                && !graph.graphStatusForTest().startsWith("Loading"), 180);
        String stacked = colours(graph);
        String hover = hover(graph, "IN_Vehicle_speed");
        runOnFx(() -> graph.setLayoutModeForTest(false));
        await("overlay", () -> graph.layoutModeForTest().contains("OVERLAY")
                && !graph.graphStatusForTest().startsWith("Loading"), 120);
        String overlay = colours(graph);
        long before = graph.viewportEndForTest() - graph.viewportStartForTest();
        runOnFx(() -> fireTooltip(shell.root, "Zoom in"));
        await("zoom", () -> graph.viewportEndForTest() - graph.viewportStartForTest() < before
                && !graph.graphStatusForTest().startsWith("Loading"), 60);
        long zoomed = graph.viewportEndForTest() - graph.viewportStartForTest();
        long startBeforePan = graph.viewportStartForTest();
        runOnFx(() -> fireTooltip(shell.root, "Pan left"));
        long panned = graph.viewportStartForTest();
        runOnFx(() -> graph.fitForTest());
        await("fit", () -> !graph.graphStatusForTest().startsWith("Loading"), 60);
        runOnFx(() -> graph.resetViewForTest());
        await("reset", () -> !graph.graphStatusForTest().startsWith("Loading"), 60);
        note("graph stacked=" + stacked);
        note("graph overlay=" + overlay);
        note("graph hover=" + one(hover));
        note("graph zoomSpan=" + before + "->" + zoomed
                + " panStart=" + startBeforePan + "->" + panned
                + " timeline=" + graph.timelineGroupForTest()
                + " status=" + one(graph.graphStatusForTest()));
        assertTrue(stacked.contains("IN_Vehicle_speed") && stacked.contains("km/h"), stacked);
        assertTrue(overlay.contains("IN_Coolant_temperature"), overlay);
        assertTrue(hover.contains("IN_Vehicle_speed") && hover.contains(FOCUS), hover);
        assertTrue(zoomed < before, "zoom did not shrink the span");
        assertTrue(panned != startBeforePan, "pan did not move the start");
    }

    private static void gps(ShellFx shell, DesktopAppService service) throws Exception {
        GraphController graph = shell.controller.graphControllerForTest();
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.GRAPHS);
            graph.selectAnalysisScopeForTest(AnalysisScope.allMeasurements(
                    service.workspace().measurementFiles().size()));
            graph.selectMapRouteTabForTest();
        });
        await("gps", () -> {
            var pane = graph.mapRoutePaneForTest();
            return pane != null && pane.gpsSegmentsForTest().stream().anyMatch(RouteSegment::hasRoute);
        }, 120);
        String status = graph.mapRoutePaneForTest().routeStatusForTest();
        List<RouteSegment> segments = graph.mapRoutePaneForTest().gpsSegmentsForTest();
        int points = segments.stream().mapToInt(RouteSegment::sampleCount).sum();
        note("gps status=" + one(status) + " segments=" + segments.size() + " points=" + points);
        assertFalse(status.contains("No GPS"), status);
        assertTrue(points > 0, status);
    }

    private static void analytics(DesktopAppService service) {
        SignalCatalogEntry speed = physical(service, FOCUS, "IN_Vehicle_speed");
        AnalysisResult result = service.runEngineeringAnalysis(AnalysisRequest.builder(AnalysisType.STATISTICS)
                .projectId(service.currentProjectId())
                .addSignal(speed.key())
                .build());
        note("analytics status=" + result.status() + " message=" + result.message());
        assertEquals(AnalysisStatus.COMPLETED, result.status(), result.message());
        SignalStatistics stats = result.statistics().getFirst();
        note("analytics samples=" + stats.sampleCount() + " min=" + stats.min() + " max=" + stats.max());
        assertEquals(speed.sampleCount(), stats.sampleCount());
        assertTrue(stats.min() <= 25.640625 && stats.max() >= 25.640625);
    }

    private static void html(ShellFx shell, Path home) throws Exception {
        Path reports = home.resolve("reports");
        Files.createDirectories(reports);
        GraphController graph = shell.controller.graphControllerForTest();
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.GRAPHS);
            button(shell.root, "Create Investigation").fire();
        });
        await("investigation", () -> graph.graphStatusForTest().contains("Created investigation"), 30);
        runOnFx(() -> shell.controller.openRoute(WorkspaceRoute.INVESTIGATIONS));
        await("report button", () -> {
            Button generate = button(shell.root, "Generate HTML Report");
            return generate != null && !generate.isDisabled();
        }, 60);
        runOnFx(() -> {
            shell.controller.investigationControllerForTest().setReportDestinationForTest("LOG Xenon", reports);
            button(shell.root, "Generate HTML Report").fire();
        });
        await("html", () -> Files.exists(reports) && htmlReady(reports), 120);
        Path html = Files.list(reports).filter(path -> path.getFileName().toString().endsWith(".html")).findFirst().orElseThrow();
        String text = Files.readString(html);
        note("html bytes=" + Files.size(html)
                + " project=" + text.contains("LOG Xenon")
                + " file=" + text.contains(FOCUS)
                + " speed=" + text.contains("IN_Vehicle_speed")
                + " unit=" + text.contains("km/h"));
        assertTrue(text.contains(FOCUS), "report omitted the LOG file");
        assertTrue(text.contains("IN_Vehicle_speed") && text.contains("km/h"), "report omitted the speed signal");
    }

    private static void removeAndRestore(ShellFx shell, DesktopAppService service, long focusSpeed) throws Exception {
        long removedBefore = count(service, REMOVED, "IN_Vehicle_speed");
        note("lifecycle beforeRemoval=" + speedCounts(service));
        Path original = Files.list(DIR).filter(path -> path.getFileName().toString().contains(REMOVED)).findFirst().orElseThrow();
        String hash = sha256(original);
        long size = Files.size(original);
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.SOURCES);
            shell.root.applyCss();
            shell.root.layout();
            removeRow(shell, REMOVED);
        });
        await("removed", () -> service.workspace().measurementFiles().stream()
                .noneMatch(file -> file.fileName().contains(REMOVED)), 20);
        int runsBeforeRemove = service.persistedProcessingRuns().size();
        directProcess(shell, service);
        awaitProcessing(service, 4, runsBeforeRemove);
        note("lifecycle removedCount=" + count(service, REMOVED, "IN_Vehicle_speed")
                + " focusRetained=" + count(service, FOCUS, "IN_Vehicle_speed")
                + " hash=" + sha256(original).equals(hash)
                + " size=" + Files.size(original)
                + " status=" + service.workspace().processingStatus()
                + " message=" + service.workspace().latestSummary().map(summary -> summary.userMessage()).orElse(""));
        assertEquals(0, count(service, REMOVED, "IN_Vehicle_speed"));
        assertEquals(focusSpeed, count(service, FOCUS, "IN_Vehicle_speed"));
        assertEquals(hash, sha256(original));
        assertEquals(size, Files.size(original));
        addMeasurement(shell, original);
        awaitInventory(service, 4);
        int runsBeforeRestore = service.persistedProcessingRuns().size();
        note("readd files=" + service.workspace().measurementFiles().stream()
                .map(file -> file.fileName() + ":" + service.inventoryJobState(file.projectSourceId()))
                .toList());
        directProcess(shell, service);
        awaitProcessing(service, 4, runsBeforeRestore);
        note("lifecycle restoredCount=" + count(service, REMOVED, "IN_Vehicle_speed")
                + " before=" + removedBefore
                + " status=" + service.workspace().processingStatus()
                + " message=" + service.workspace().latestSummary().map(summary -> summary.userMessage()).orElse("")
                + " counts=" + speedCounts(service));
        boolean restored = count(service, REMOVED, "IN_Vehicle_speed") == removedBefore;
        note("lifecycleRestore=" + (restored ? "PASS" : "FAIL"));
        assertEquals(hash, sha256(original));
        lifecycleRestorePassed = restored;
    }

    private static void saveAndReopen(ShellFx shell, DesktopAppService service, Path db, Path home) throws Exception {
        String projectId = service.currentProjectId();
        int files = service.workspace().measurementFiles().size();
        runOnFx(() -> buttonStarting(shell.root, "Save").fire());
        await("saved", () -> service.projectSaveState() != null && !service.projectSaveState().name().contains("SAVING"), 30);
        note("saveState=" + service.projectSaveState());
        close(shell);
        service.shutdown();
        DesktopAppService reopened = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
        ShellFx again = boot(reopened);
        try {
            runOnFx(() -> again.controller.requestOpenSavedProject(projectId));
            await("reopen", () -> reopened.workspace().measurementFiles().size() == files, 60);
            long speed = count(reopened, FOCUS, "IN_Vehicle_speed");
            note("reopen files=" + reopened.workspace().measurementFiles().size()
                    + " kinds=" + reopened.workspace().measurementFiles().stream().map(file -> file.kind().name()).distinct().toList()
                    + " focusSpeed=" + speed
                    + " status=" + reopened.workspace().processingStatus()
                    + " counts=" + speedCounts(reopened));
            assertEquals(9638, speed);
            assertTrue(reopened.workspace().measurementFiles().stream().allMatch(file -> "MEASUREMENT_LOG".equals(file.kind().name())));
            reopened.selectAnalysisScope(AnalysisScope.allMeasurements(files));
            var route = new ProjectRouteReportLoader().load(reopened);
            int segments = route == null || route.route() == null ? 0 : route.route().routeSegmentCount();
            long points = route == null || route.route() == null ? 0 : route.route().gpsSampleCount();
            boolean html = htmlReady(home.resolve("reports"));
            note("reopen graphKeys=" + reopened.signalsModel().workspaceKeys().size()
                    + " gpsSegments=" + segments
                    + " gpsPoints=" + points
                    + " html=" + html);
            assertEquals(2, reopened.signalsModel().workspaceKeys().size(), "graph traces were not restored");
            assertEquals(4, segments, "GPS segments were not restored");
            assertEquals(24512L, points, "GPS points were not restored");
            assertTrue(html, "HTML report was not available after reopen");
        } finally {
            close(again);
            reopened.shutdown();
        }
    }

    private static void directProcess(ShellFx shell, DesktopAppService service) throws Exception {
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.SOURCES);
            shell.root.applyCss();
            shell.root.layout();
            var list = shell.controller.projectFilesControllerForTest().measurementSourceListForTest();
            list.getItems().stream()
                    .filter(file -> "MEASUREMENT_LOG".equals(file.kind().name()))
                    .findFirst()
                    .ifPresent(file -> {
                        list.getSelectionModel().select(file);
                        service.selectMeasurement(file.id());
                    });
            shell.root.applyCss();
            shell.root.layout();
            Button direct = shell.controller.projectFilesControllerForTest().continueToDecodeButtonForTest();
            note("directLabel=" + direct.getText() + " disabled=" + direct.isDisabled());
            assertEquals("Direct Processing", direct.getText(), "direct processing was not offered");
            assertFalse(direct.isDisabled(), direct.getText());
            direct.fire();
        });
    }

    private static void removeRow(ShellFx shell, String namePart) {
        var list = shell.controller.projectFilesControllerForTest().measurementSourceListForTest();
        list.setMinHeight(480);
        list.setPrefHeight(480);
        for (WorkspaceFile file : list.getItems()) {
            if (file.fileName().contains(namePart)) {
                list.scrollTo(file);
                list.getSelectionModel().select(file);
            }
        }
        list.applyCss();
        list.layout();
        for (Node cell : list.lookupAll(".list-cell")) {
            if (!text(cell).contains(namePart)) {
                continue;
            }
            for (Node node : ((Parent) cell).lookupAll(".button")) {
                if (node instanceof Button button && button.getText() != null && button.getText().startsWith("Remove")) {
                    button.fire();
                    return;
                }
            }
        }
        throw new AssertionError("remove button not found for " + namePart);
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
            if (!files.isEmpty() && files.stream().allMatch(file -> {
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
            // Each direct file inserts its own run while the batch is still RUNNING and the
            // previous catalog is still analysis-available. Completion is the workspace status
            // written after every target has been committed and the catalog republished.
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
        note("processingWait runs=" + service.persistedProcessingRuns().size()
                + " before=" + runsBefore
                + " status=" + service.workspace().processingStatus());
        return service.workspace().processingStatus();
    }

    private static String colours(GraphController graph) {
        return graph.renderAnalysisTracksForTest().stream()
                .map(series -> series.key().signalName() + " unit=" + series.unit() + " samples=" + series.sourceSampleCount())
                .toList()
                .toString();
    }

    private static String hover(GraphController graph, String name) throws Exception {
        return runOnFxValue(() -> {
            var canvas = graph.canvasForTest();
            String last = "";
            for (int y = 24; y <= 640; y += 16) {
                canvas.fireEvent(new MouseEvent(canvas, canvas, MouseEvent.MOUSE_MOVED, 400, y, 400, y,
                        MouseButton.NONE, 0, false, false, false, false, false, false, false, false, false, false, null));
                last = graph.liveCursorForTest() == null ? "" : graph.liveCursorForTest();
                if (last.contains(name)) {
                    return last;
                }
            }
            return last;
        });
    }

    private static void fireTooltip(Parent root, String tooltip) {
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button button && button.getTooltip() != null
                    && button.getTooltip().getText() != null
                    && button.getTooltip().getText().startsWith(tooltip)) {
                button.fire();
                return;
            }
        }
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

    private static String text(Node node) {
        StringBuilder text = new StringBuilder();
        if (node instanceof Labeled labeled && labeled.getText() != null) {
            text.append(labeled.getText());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                text.append(text(child));
            }
        }
        return text.toString();
    }

    private static boolean htmlReady(Path reports) {
        try (var list = Files.list(reports)) {
            return list.anyMatch(path -> {
                try {
                    return path.getFileName().toString().endsWith(".html") && Files.size(path) > 500;
                } catch (Exception ex) {
                    return false;
                }
            });
        } catch (Exception ex) {
            return false;
        }
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

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))).toUpperCase(Locale.ROOT);
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
