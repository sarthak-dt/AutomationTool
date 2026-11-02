package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.model.AnalysisRequest;
import com.stellar.platform.analysis.model.AnalysisResult;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.AnalysisType;
import com.stellar.platform.analysis.model.SignalStatistics;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.project.ProjectInventoryJobState;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.map.MapRoutePane;
import com.stellar.platform.desktop.ui.graph.map.RouteCanvas;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.graph.GraphQueryProvider;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
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
 * Independent physical comparison for the i10 and TVS BMW native MF4 channels.
 * These channels are measurement groups, not CAN/DBC signals. b1-3 is recorded
 * from the structural inspection and is not reprocessed here.
 */
class Mf4NativeOracleJavaFxAcceptanceTest {

    private static final Path I10 = Path.of(
            "C:\\Users\\DT - User\\Downloads\\DriveTechData\\MF4Files\\i10_MF4\\i10_cinfig_00_7E0_0002259_20231223_105925.mf4");
    private static final Path TVS = Path.of(
            "C:\\Users\\DT - User\\Downloads\\DriveTechData\\MF4Files\\TVS_BMW_MF4\\TVS_bmw_0002211_20250217_153502.mf4");
    private static final Path EVIDENCE = Path.of("D:\\DesktopApp\\DriveT\\docs\\qa-handoff\\mf4-native-oracle.txt");
    private static final StringBuilder NOTES = new StringBuilder();

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
        note("started=" + Instant.now());
        note("b1-3 classification=CORRECTLY_UNSUPPORTED_FOR_CAN_DBC_START_PROCESSING");
        note("b1-3 reason=all 93 channel groups are bus-event (cg_flags=2) already-decoded CAN messages written by asammdf 8.8; there is no CAN_DataFrame group and no matching DBC. Start Processing stays disabled because the plan has no executable raw-CAN job. Direct Processing is DAT/LOG only. Physical samples exist in the file but are outside the supported raw-frame decode path.");
    }

    @AfterAll
    static void writeEvidence() throws Exception {
        note("finished=" + Instant.now());
        Files.createDirectories(EVIDENCE.getParent());
        Files.writeString(EVIDENCE, NOTES.toString());
    }

    @Test
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    void nativeChannelsMatchAsammdfAndSurviveReopen() throws Exception {
        accept("i10", I10, List.of(
                new Sample("Accelerometer X", 0L, 0.038064, 1e-4),
                new Sample("LATITUDE", 31_923_630_000L, 18.7247137, 1e-6),
                new Sample("SPEED_OVER_GROUND", 500_965_450_000L, 70.1712, 1e-3)), 15);
        accept("TVS", TVS, List.of(
                new Sample("LATITUDE", 108_313_180_000L, 12.5075055, 1e-6),
                new Sample("LONGITUDE", 129_326_020_000L, 77.681875, 1e-6),
                new Sample("SPEED_OVER_GROUND", 145_735_940_000L, 0.2016, 1e-3)), 9);
    }

    private static void accept(String label, Path file, List<Sample> samples, int minimumSignals) throws Exception {
        note("--- " + label);
        String before = sha256(file);
        note("file=" + file.getFileName() + " sha256=" + before + " size=" + Files.size(file));
        Path home = Files.createTempDirectory("stellar-mf4-oracle-" + label);
        Path db = home.resolve("meta.db");
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        System.setProperty("stellarai.metadata.db", db.toString());
        DesktopAppService service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
        ShellFx shell = boot(service);
        try {
            createProject(shell, "MF4 " + label + " oracle");
            addMeasurement(shell, file);
            awaitInventory(service, file.getFileName().toString());
            boolean started = startProcessing(shell);
            assertTrue(started, label + " Start Processing stayed disabled");
            ProjectProcessingStatus status = awaitCatalog(service, minimumSignals, 12);
            note("processing=" + status + " catalog=" + service.signalsModel().catalog().entries().size()
                    + " warnings=" + service.processingWarningReport().count());
            service.signalsModel().catalog().entries().forEach(entry ->
                    note("signal " + entry.signalName() + " samples=" + entry.sampleCount()
                            + " unit='" + entry.unit() + "' source=" + entry.sourceFile()
                            + " first=" + entry.firstTimestampNanos() + " last=" + entry.lastTimestampNanos()));
            for (Sample sample : samples) {
                compare(service, file.getFileName().toString(), sample);
            }
            graph(shell, samples.getFirst().name());
            analytics(service, samples.getFirst());
            html(shell, home, label, samples.getFirst().name());
            if ("TVS".equals(label) || "i10".equals(label)) {
                gps(shell, label);
            }
            String projectId = service.currentProjectId();
            runOnFx(() -> {
                Button save = buttonStarting(shell.root, "Save");
                if (save != null && !save.isDisabled()) {
                    save.fire();
                }
            });
            await("saved " + label, () -> service.projectSaveState() != null
                    && !service.projectSaveState().name().contains("SAVING"), 40);
            note("saveState=" + service.projectSaveState());
            close(shell);
            service.shutdown();
            DesktopAppService reopened = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
            ShellFx again = boot(reopened);
            try {
                runOnFx(() -> again.controller.requestOpenSavedProject(projectId));
                await("reopen " + label, () -> reopened.workspace().measurementFiles().stream()
                        .anyMatch(item -> file.getFileName().toString().equals(item.fileName())), 90);
                note("reopen catalog=" + reopened.signalsModel().catalog().entries().size()
                        + " processing=" + reopened.workspace().processingStatus()
                        + " warnings=" + reopened.processingWarningReport().count());
                for (Sample sample : samples) {
                    compare(reopened, file.getFileName().toString(), sample);
                }
            } finally {
                close(again);
                reopened.shutdown();
            }
            assertEquals(before, sha256(file), label);
            note("hashUnchanged=true");
        } finally {
            close(shell);
            try {
                service.shutdown();
            } catch (RuntimeException ignored) {
                // Reopen already closed the store.
            }
        }
    }

    private static void compare(DesktopAppService service, String fileName, Sample sample) {
        assertTrue(service.signalsModel().catalogStore() instanceof ProjectMergedSignalStore);
        ProjectMergedSignalStore merged = (ProjectMergedSignalStore) service.signalsModel().catalogStore();
        GraphQueryProvider query = (GraphQueryProvider) merged;
        List<SignalCatalogEntry> matches = merged.physicalCatalogAll().stream()
                .filter(entry -> sample.name().equals(entry.signalName()))
                .filter(entry -> entry.sourceFile() != null && entry.sourceFile().contains(fileName))
                .toList();
        assertEquals(1, matches.size(), sample.name());
        SignalCatalogEntry entry = matches.getFirst();
        List<SignalSamplePoint> points = query.graphQuery().queryRange(
                entry.key(), sample.timestampNanos(), sample.timestampNanos(), 5);
        assertFalse(points.isEmpty(), sample.name() + " missing " + sample.timestampNanos());
        SignalSamplePoint point = points.getFirst();
        assertEquals(sample.timestampNanos(), point.timestampNanos(), sample.name());
        assertEquals(sample.physical(), point.physicalValue(), sample.delta(), sample.name());
        note("compare " + sample.name() + " PASS t=" + point.timestampNanos()
                + " raw=" + point.rawValue() + " physical=" + point.physicalValue()
                + " unit='" + entry.unit() + "' validity=" + point.validity()
                + " samples=" + entry.sampleCount() + " source=" + entry.sourceFile());
    }

    private static void graph(ShellFx shell, String signal) throws Exception {
        GraphController graph = shell.controller.graphControllerForTest();
        runOnFx(() -> shell.controller.openRoute(WorkspaceRoute.GRAPHS));
        await("explorer", () -> graph.explorerRowsForTest().stream()
                .anyMatch(entry -> signal.equals(entry.signalName())), 60);
        runOnFx(() -> {
            graph.explorerRowsForTest().stream()
                    .filter(entry -> signal.equals(entry.signalName()))
                    .findFirst()
                    .ifPresent(entry -> graph.fireExplorerCheckboxForTest(entry.key()));
        });
        await("rendered " + signal, () -> graph.lastRenderedCountForTest() > 0, 60);
        runOnFx(() -> {
            graph.zoomInForTest();
            graph.panLeftForTest();
            graph.fitForTest();
        });
        note("graph signal=" + signal + " rendered=" + graph.lastRenderedCountForTest()
                + " cursor='" + oneLine(graph.liveCursorForTest()) + "' status=" + oneLine(graph.graphStatusForTest()));
    }

    private static void analytics(DesktopAppService service, Sample sample) {
        var logical = service.signalsModel().catalog().entries().stream()
                .filter(entry -> sample.name().equals(entry.signalName()))
                .findFirst()
                .orElseThrow();
        AnalysisResult result = service.runEngineeringAnalysis(AnalysisRequest.builder(AnalysisType.STATISTICS)
                .projectId(service.currentProjectId())
                .addSignal(logical.key())
                .build());
        note("analytics " + sample.name() + " status=" + result.status() + " message=" + result.message());
        assertEquals(AnalysisStatus.COMPLETED, result.status(), result.message());
        SignalStatistics stats = result.statistics().getFirst();
        note("analytics samples=" + stats.sampleCount() + " min=" + stats.min() + " max=" + stats.max()
                + " knownInside=" + (stats.min() <= sample.physical() && stats.max() >= sample.physical())
                + " aggregateOracle=UNVERIFIED");
        assertTrue(stats.sampleCount() > 0, sample.name());
        assertTrue(stats.min() <= sample.physical() + sample.delta()
                && stats.max() >= sample.physical() - sample.delta(), sample.name());
    }

    private static void html(ShellFx shell, Path home, String label, String signal) throws Exception {
        Path reports = home.resolve("reports");
        Files.createDirectories(reports);
        GraphController graph = shell.controller.graphControllerForTest();
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.GRAPHS);
            Button create = button(shell.root, "Create Investigation");
            assertNotNull(create, "Create Investigation");
            create.fire();
        });
        await("investigation", () -> graph.graphStatusForTest().contains("Created investigation"), 30);
        runOnFx(() -> shell.controller.openRoute(WorkspaceRoute.INVESTIGATIONS));
        await("report button", () -> {
            Button generate = button(shell.root, "Generate HTML Report");
            return generate != null && !generate.isDisabled();
        }, 60);
        String project = "MF4 " + label + " oracle";
        runOnFx(() -> {
            shell.controller.investigationControllerForTest().setReportDestinationForTest(project, reports);
            button(shell.root, "Generate HTML Report").fire();
        });
        await("html", () -> htmlReady(reports), 180);
        Path html = Files.list(reports).filter(path -> path.getFileName().toString().endsWith(".html")).findFirst().orElseThrow();
        String text = Files.readString(html);
        note("html=" + html.getFileName() + " bytes=" + Files.size(html)
                + " project=" + text.contains(project)
                + " signal=" + text.contains(signal)
                + " file=" + text.contains(label.equals("i10") ? "105925" : "153502"));
        assertTrue(text.contains(project), "html project");
        assertTrue(text.contains(signal), "html signal");
    }

    private static void gps(ShellFx shell, String label) throws Exception {
        GraphController graph = shell.controller.graphControllerForTest();
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.GRAPHS);
            graph.selectMapRouteTab();
        });
        await("gps " + label, () -> {
            MapRoutePane pane = graph.mapRoutePaneForTest();
            if (pane == null) {
                return false;
            }
            int projectPoints = pane.gpsSegmentsForTest().stream().mapToInt(segment -> segment.sampleCount()).sum();
            return pane.routeSizeForTest() > 0 || projectPoints > 0;
        }, 90);
        runOnFx(() -> {
            MapRoutePane pane = graph.mapRoutePaneForTest();
            RouteCanvas canvas = pane.routeCanvasForTest();
            canvas.paintForTest();
            canvas.zoom(1.4);
            canvas.fitRoute();
            int points = Math.max(pane.routeSizeForTest(),
                    pane.gpsSegmentsForTest().stream().mapToInt(segment -> segment.sampleCount()).sum());
            var marks = canvas.endpointMarksForTest();
            note("gps " + label + " points=" + points + " segments=" + pane.gpsSegmentsForTest().size()
                    + " policy=" + canvas.endpointPolicyForTest()
                    + " marks=" + marks.size()
                    + " status=" + oneLine(pane.routeStatusForTest())
                    + " mapping=" + oneLine(pane.mappingStatusForTest()));
            marks.forEach(mark -> note("gps mark " + mark.role() + " lat=" + mark.latitude() + " lon=" + mark.longitude()));
            if ("i10".equals(label)) {
                assertEquals(1877, points, "i10 GPS points");
                assertEquals(18.7247137, marks.getFirst().latitude(), 1e-6, "i10 start latitude");
            }
            if ("TVS".equals(label)) {
                assertEquals(296, points, "TVS GPS points");
                assertEquals(12.5075055, marks.getFirst().latitude(), 1e-5, "TVS start latitude");
            }
        });
    }

    private static boolean htmlReady(Path reports) {
        try (var files = Files.list(reports)) {
            return files.anyMatch(path -> path.getFileName().toString().endsWith(".html") && size(path) > 500);
        } catch (Exception ex) {
            return false;
        }
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (Exception ex) {
            return 0;
        }
    }

    private static ProjectProcessingStatus awaitCatalog(DesktopAppService service, int minimum, int minutes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(minutes);
        int stable = 0;
        int last = -1;
        while (System.nanoTime() < deadline) {
            int catalog = service.signalsModel().catalog().entries().size();
            ProjectProcessingStatus status = service.workspace().processingStatus();
            if (catalog == last && catalog >= minimum && status != ProjectProcessingStatus.RUNNING) {
                stable++;
                if (stable >= 3) {
                    return status;
                }
            } else {
                stable = 0;
                last = catalog;
            }
            Thread.sleep(400);
        }
        return service.workspace().processingStatus();
    }

    private static void awaitInventory(DesktopAppService service, String fileName) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(8);
        while (System.nanoTime() < deadline) {
            WorkspaceFile measurement = service.workspace().measurementFiles().stream()
                    .filter(file -> fileName.equals(file.fileName()))
                    .findFirst()
                    .orElse(null);
            if (measurement != null) {
                ProjectInventoryJobState state = service.inventoryJobState(measurement.projectSourceId());
                if (state == ProjectInventoryJobState.READY || state == ProjectInventoryJobState.FAILED
                        || state == ProjectInventoryJobState.UNSUPPORTED) {
                    note("inventory=" + state + " kind=" + measurement.kind());
                    return;
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError("inventory did not finish for " + fileName);
    }

    private static boolean startProcessing(ShellFx shell) throws Exception {
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.DECODE);
            shell.root.applyCss();
            shell.root.layout();
            Button proceed = button(shell.root, "Review & Process");
            if (proceed != null && !proceed.isDisabled()) {
                proceed.fire();
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Boolean started = runOnFxValue(() -> {
                shell.controller.openRoute(WorkspaceRoute.REVIEW_AND_PROCESS);
                shell.root.applyCss();
                shell.root.layout();
                Button run = button(shell.root, "Start Processing");
                if (run != null && !run.isDisabled()) {
                    run.fire();
                    return true;
                }
                return false;
            });
            if (Boolean.TRUE.equals(started)) {
                return true;
            }
            Thread.sleep(400);
        }
        return false;
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
            assertNotNull(add, "Add Measurement");
            add.fire();
        });
    }

    private static void createProject(ShellFx shell, String name) throws Exception {
        Thread opener = new Thread(() -> {
            try {
                runOnFx(() -> {
                    shell.controller.openRoute(WorkspaceRoute.HOME);
                    Button button = button(shell.root, "New Project");
                    assertNotNull(button, "New Project");
                    button.fire();
                });
            } catch (Throwable thrown) {
                throw new RuntimeException(thrown);
            }
        });
        opener.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(runOnFxValue(() -> fillDialog(name)))) {
                break;
            }
            Thread.sleep(50);
        }
        opener.join(20_000);
        await("project " + name, () -> name.equals(projectName(shell)), 20);
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

    private static String projectName(ShellFx shell) {
        try {
            return shell.service.workspace().projectName();
        } catch (RuntimeException ex) {
            return "";
        }
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
        assertTrue(booted.await(60, TimeUnit.SECONDS), "shell did not open");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return new ShellFx(stage.get(), controller.get(), root.get(), service);
    }

    private static Button button(Parent root, String label) {
        Button hidden = null;
        for (Node node : root.lookupAll(".button")) {
            if (!(node instanceof Button button)) {
                continue;
            }
            String text = button.getText() == null ? "" : button.getText();
            if (!text.contains(label)) {
                continue;
            }
            if (button.isVisible() && button.isManaged()) {
                return button;
            }
            hidden = button;
        }
        return hidden;
    }

    private static Button buttonStarting(Parent root, String prefix) {
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button button && button.getText() != null && button.getText().startsWith(prefix)) {
                return button;
            }
        }
        return null;
    }

    private static void await(String label, java.util.function.BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for " + label);
    }

    private static void close(ShellFx shell) throws Exception {
        if (shell == null || shell.stage == null) {
            return;
        }
        runOnFx(() -> shell.stage.close());
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
        assertTrue(done.await(60, TimeUnit.SECONDS), "FX work timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static <T> T runOnFxValue(java.util.function.Supplier<T> work) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        runOnFx(() -> value.set(work.get()));
        return value.get();
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] chunk = new byte[1024 * 1024];
            int read;
            while ((read = input.read(chunk)) >= 0) {
                digest.update(chunk, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest()).toUpperCase(Locale.ROOT);
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace('\n', ' ').trim();
    }

    private static void note(String line) {
        NOTES.append(line).append('\n');
    }

    private record Sample(String name, long timestampNanos, double physical, double delta) {
    }

    private record ShellFx(Stage stage, MainShellController controller, Parent root, DesktopAppService service) {
    }
}
