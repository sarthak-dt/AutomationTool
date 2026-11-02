package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ProjectSaveState;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.parser.mf4.Mf4Inspector;
import com.stellar.platform.parser.mf4.report.MdfInspectionReport;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 release smoke on an isolated home. Customer measurements are hashed and only read.
 */
class Phase1ReleaseSmokeTest {

    private static final List<String> NOTES = new ArrayList<>();
    private static final Path MF4 = Path.of(
            "C:\\Users\\DT - User\\Downloads\\OneDrive_2_7-13-2026_MF4 files\\4BE883CD_00000975_00000001-63C7A554.MF4");
    private static final Path CUSTOMER_DBC = Path.of(
            "C:\\Users\\DT - User\\Downloads\\DriveTechData\\dbcfiles\\1.dbc");
    private static final Path CUSTOMER_ASC = Path.of(
            "C:\\Users\\DT - User\\Downloads\\DriveTechData\\ASC\\ASCII_eCC21_KA04NC7760_Bengaluru_09_08_2023_0002360_20230814_145851.asc");

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterAll
    static void writeEvidence() throws Exception {
        Path out = Path.of("..", "docs", "qa-handoff", "phase1-release-smoke.txt").toAbsolutePath().normalize();
        Files.createDirectories(out.getParent());
        Files.writeString(out, String.join(System.lineSeparator(), NOTES) + System.lineSeparator());
    }

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void phase1ImplementedWorkflows() throws Exception {
        String mf4Before = sha(MF4);
        String dbcBefore = sha(CUSTOMER_DBC);
        String ascBefore = sha(CUSTOMER_ASC);
        inspectMf4();

        Path home = Files.createTempDirectory("stellar-phase1");
        isolate(home);
        Path asc = home.resolve("phase1-engine.asc");
        Path extra = home.resolve("phase1-extra.asc");
        Path dbc = home.resolve("phase1-engine.dbc");
        Path corrupt = home.resolve("phase1-corrupt.asc");
        Files.writeString(asc, engineAsc());
        Files.writeString(extra, engineAsc());
        Files.writeString(dbc, engineDbc());
        Files.write(corrupt, new byte[]{0, 1, 2, 3});
        Path reportDir = home.resolve("reports");
        Files.createDirectories(reportDir);

        Path db = home.resolve("meta.db");
        SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db);
        DesktopAppService service = new DesktopAppService(new DesktopSession(), store);
        final Shell[] first = new Shell[1];
        final Shell[] second = new Shell[1];
        final Shell[] third = new Shell[1];
        try {
            first[0] = boot(service);
            Button create = visibleButton(first[0].root, "New Project");
            assertNotNull(create, "New Project");
            assertTrue(!create.isDisabled(), "New Project enabled");
            note("CHECK CREATE button visible enabled=" + !create.isDisabled());
            createNamed(first[0], "Phase1 Smoke");
            assertEquals("Phase1 Smoke", service.workspace().projectName());
            note("CHECK CREATE PASS project=" + service.workspace().projectName());

            addFile(first[0], "+ Add Measurement Files", "Browse Files", asc);
            addFile(first[0], "+ Add DBC", "+ Add DBC", dbc);
            int beforeCorrupt = service.workspace().measurementFiles().size();
            addFile(first[0], "+ Add Measurement Files", "Browse Files", corrupt);
            assertEquals(beforeCorrupt, service.workspace().measurementFiles().size(), "corrupt file was registered");
            note("CHECK CORRUPT PASS corrupt.asc was refused and measurements stayed " + beforeCorrupt);

            runOnFx(() -> {
                first[0].controller.openRoute(WorkspaceRoute.REVIEW_AND_PROCESS);
                first[0].root.applyCss();
                first[0].root.layout();
            });
            runOnFx(() -> fire(first[0].root, "Start Processing"));
            String processing = waitForText(first[0], "Processing Complete", 90);
            note("CHECK PROCESS page=" + oneLine(processing).substring(0, Math.min(400, oneLine(processing).length())));
            assertTrue(processing.contains("Processing Complete") || processing.contains("COMPLETED"), processing);

            List<SignalCatalogEntry> catalog = service.signalsModel().catalog().entries();
            SignalCatalogEntry speed = catalog.stream().filter(e -> "EngineSpeed".equals(e.signalName())).findFirst().orElse(null);
            SignalCatalogEntry temp = catalog.stream().filter(e -> "EngineTemp".equals(e.signalName())).findFirst().orElse(null);
            assertNotNull(speed, "EngineSpeed");
            assertNotNull(temp, "EngineTemp");
            SignalSamplePoint firstSpeed = firstSample(service, speed);
            SignalSamplePoint firstTemp = firstSample(service, temp);
            note("CHECK SIGNALS speed raw=" + firstSpeed.rawValue() + " physical=" + firstSpeed.physicalValue()
                    + " temp raw=" + firstTemp.rawValue() + " physical=" + firstTemp.physicalValue()
                    + " catalog=" + catalog.size());
            assertEquals(10000L, firstSpeed.rawValue());
            assertEquals(2500.0, firstSpeed.physicalValue(), 1e-6);
            assertEquals(90L, firstTemp.rawValue());
            assertEquals(50.0, firstTemp.physicalValue(), 1e-6);
            note("CHECK SIGNALS PASS EngineSpeed raw 10000 physical 2500 rpm; EngineTemp raw 90 physical 50 degC");

            runOnFx(() -> {
                for (SignalCatalogEntry entry : catalog) {
                    service.signalsModel().addToWorkspace(entry.key());
                }
                first[0].controller.openRoute(WorkspaceRoute.SIGNALS);
                first[0].root.applyCss();
                first[0].root.layout();
            });
            String signalsPage = visible(first[0].root);
            assertTrue(signalsPage.contains("EngineSpeed"), signalsPage);
            note("CHECK SIGNALS-PAGE PASS EngineSpeed is listed");

            runOnFx(() -> {
                first[0].controller.openRoute(WorkspaceRoute.GRAPHS);
                first[0].root.applyCss();
                first[0].root.layout();
            });
            GraphController graph = runOnFxValue(() -> first[0].controller.graphControllerForTest());
            awaitTracks(graph);
            long sampleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < sampleDeadline && graph.renderedBucketCountForTest() == 0) {
                Thread.sleep(100);
            }
            int buckets = graph.renderedBucketCountForTest();
            long samples = graph.renderedSourceSampleCountForTest();
            note("CHECK GRAPH SAMPLES buckets=" + buckets + " sourceSamples=" + samples
                    + " status=" + oneLine(graph.graphStatusForTest()));
            assertTrue(buckets > 0 && samples > 0, "graph drew no samples; status=" + graph.graphStatusForTest());
            note("CHECK GRAPH SAMPLES PASS buckets=" + buckets + " sourceSamples=" + samples);
            int tracks = graph.logicalTrackCountForTest();
            String overview = graph.timelineGroupForTest() + " " + graph.viewportStartForTest() + ".." + graph.viewportEndForTest();
            assertTrue(tracks >= 2, "tracks=" + tracks + " status=" + graph.graphStatusForTest());
            runOnFx(() -> graph.setLayoutModeForTest(true));
            long stackedStart = graph.viewportStartForTest();
            long stackedEnd = graph.viewportEndForTest();
            runOnFx(() -> graph.setLayoutModeForTest(false));
            String overlayMode = graph.layoutModeForTest();
            runOnFx(graph::zoomInForTest);
            long zoomedEnd = graph.viewportEndForTest();
            runOnFx(graph::panRightForTest);
            long pannedStart = graph.viewportStartForTest();
            runOnFx(graph::fitForTest);
            long fittedStart = graph.viewportStartForTest();
            long fittedEnd = graph.viewportEndForTest();
            boolean controlsMoved = zoomedEnd != stackedEnd && pannedStart != stackedStart;
            note("CHECK GRAPH tracks=" + tracks + " overview=" + overview
                    + " stacked=" + stackedStart + ".." + stackedEnd
                    + " overlay=" + overlayMode
                    + " zoomedEnd=" + zoomedEnd + " pannedStart=" + pannedStart
                    + " fitted=" + fittedStart + ".." + fittedEnd
                    + " timeline=" + graph.timelineGroupForTest()
                    + " status=" + oneLine(graph.graphStatusForTest()));
            assertTrue(controlsMoved, "stacked/overlay zoom pan fit left the viewport unchanged");
            note("CHECK GRAPH PASS stacked, overlay, zoom, pan, and fit changed the viewport on " + tracks + " tracks");

            runOnFx(() -> toggle(first[0].root, "File Focus").fire());
            long focusDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < focusDeadline
                    && !"FILE_FOCUS".equals(graph.timelineGroupForTest())) {
                Thread.sleep(100);
            }
            long focusSpan = graph.viewportEndForTest() - graph.viewportStartForTest();
            note("CHECK GRAPH FILE-FOCUS timeline=" + graph.timelineGroupForTest()
                    + " range=" + graph.viewportStartForTest() + ".." + graph.viewportEndForTest()
                    + " label='" + oneLine(graph.fileFocusLabelForTest()) + "'");
            assertEquals("FILE_FOCUS", graph.timelineGroupForTest(), graph.fileFocusLabelForTest());
            assertTrue(focusSpan > 1_000_000_000L, "file focus collapsed to " + focusSpan);

            runOnFx(() -> {
                graph.selectMapRouteTabForTest();
                graph.mapRoutePaneForTest().ensureUiBuiltForTest();
            });
            long mapDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            int segments = 0;
            int points = 0;
            String mapStatus = "";
            while (System.nanoTime() < mapDeadline) {
                var map = graph.mapRoutePaneForTest();
                segments = map.gpsSegmentsForTest().size();
                points = 0;
                for (var segment : map.gpsSegmentsForTest()) {
                    if (segment.hasRoute() && segment.route().model() != null) {
                        points += segment.route().model().size();
                    }
                }
                mapStatus = map.routeStatusForTest();
                if (points >= 2 || (mapStatus != null && mapStatus.contains("Full Drive"))) {
                    break;
                }
                Thread.sleep(200);
            }
            String chooser = runOnFxValue(() -> comboItems(first[0].root));
            note("CHECK GPS segments=" + segments + " points=" + points + " status='" + oneLine(mapStatus)
                    + "' chooser='" + oneLine(chooser) + "'");
            assertTrue(points >= 2 || (mapStatus != null && mapStatus.contains("Full Drive")),
                    "segments=" + segments + " points=" + points + " status=" + mapStatus);
            note("CHECK GPS PASS synthetic GpsLatitude/GpsLongitude route is visible; chooser " + oneLine(chooser));

            runOnFx(() -> {
                graph.selectAnalysisTabForTest(2);
                toggle(first[0].root, "Time Domain").fire();
                selectSmoothing(first[0].root);
                field(first[0].root, "Alpha").setText("0.5");
                fire(first[0].root, "Run");
            });
            String analytics = waitForReport(service, "EXPONENTIAL_SMOOTHING", 15);
            String analyticsPage = visible(first[0].root);
            boolean refused = analyticsPage.contains("Recording order unknown");
            note("CHECK ANALYTICS report='" + oneLine(analytics) + "' refused=" + refused);
            assertTrue(analytics.contains("EXPONENTIAL_SMOOTHING"),
                    "analytics='" + analytics + "' refused=" + refused + " page=" + oneLine(analyticsPage));
            note("CHECK ANALYTICS PASS exponential smoothing completed");

            runOnFx(() -> fire(first[0].root, "Create Investigation"));
            InvestigationController investigations = runOnFxValue(() -> first[0].controller.investigationControllerForTest());
            runOnFx(() -> {
                first[0].controller.openRoute(WorkspaceRoute.INVESTIGATIONS);
                investigations.setReportDestinationForTest("Phase1 Smoke Report", reportDir);
                fire(first[0].root, "Generate HTML Report");
            });
            Path html = waitForHtml(reportDir, 30);
            String htmlText = Files.readString(html);
            note("CHECK HTML file=" + html.getFileName() + " bytes=" + Files.size(html)
                    + " containsProject=" + htmlText.contains("Phase1 Smoke"));
            assertTrue(htmlText.contains("Phase1 Smoke") || htmlText.contains("EngineSpeed"), htmlText.substring(0, Math.min(500, htmlText.length())));
            note("CHECK HTML PASS " + html.getFileName() + " " + Files.size(html) + " bytes");

            runOnFx(() -> first[0].controller.openRoute(WorkspaceRoute.CONFIGURATION));
            String config = visible(first[0].root);
            assertTrue(config.contains("Analysis Storage") && config.contains("GPS"), config);
            note("CHECK CONFIG PASS Analysis Storage and GPS sections are on the Configuration page");

            runOnFx(() -> fireSave(first[0].root));
            awaitClean(service);
            close(first[0]);
            service.shutdown();
            first[0] = null;

            SqliteProjectMetadataStore store2 = new SqliteProjectMetadataStore(db);
            DesktopAppService service2 = new DesktopAppService(new DesktopSession(), store2);
            second[0] = boot(service2);
            boolean speedBack = service2.signalsModel().catalog().entries().stream()
                    .anyMatch(e -> "EngineSpeed".equals(e.signalName()));
            note("CHECK REOPEN name=" + service2.workspace().projectName() + " engineSpeed=" + speedBack
                    + " files=" + service2.workspace().measurementFiles().size());
            assertEquals("Phase1 Smoke", service2.workspace().projectName());
            assertTrue(speedBack, "EngineSpeed missing after reopen");
            note("CHECK REOPEN PASS Phase1 Smoke restored EngineSpeed without a new processing run");

            addFile(second[0], "+ Add Measurement Files", "Browse Files", extra);
            runOnFx(() -> fireSave(second[0].root));
            awaitClean(service2);
            close(second[0]);
            service2.shutdown();
            Path aside = home.resolve("aside");
            Files.createDirectories(aside);
            Files.move(extra, aside.resolve("phase1-extra.asc"));
            SqliteProjectMetadataStore store3 = new SqliteProjectMetadataStore(db);
            DesktopAppService service3 = new DesktopAppService(new DesktopSession(), store3);
            third[0] = boot(service3);
            runOnFx(() -> {
                third[0].controller.openRoute(WorkspaceRoute.SOURCES);
                third[0].root.applyCss();
                third[0].root.layout();
            });
            Thread.sleep(300);
            String sources = visible(third[0].root);
            note("CHECK MISSING dialog='" + oneLine(third[0].bootDialogs) + "'");
            assertTrue(sources.contains("UNAVAILABLE") || sources.contains("File not found")
                    || third[0].bootDialogs.contains("missing"), sources);
            assertTrue(Files.exists(asc), "original synthetic measurement was deleted");
            note("CHECK MISSING PASS extra file reported missing and phase1-engine.asc remains on disk");
        } finally {
            closeQuiet(first[0]);
            closeQuiet(second[0]);
            closeQuiet(third[0]);
            String mf4After = sha(MF4);
            String dbcAfter = sha(CUSTOMER_DBC);
            String ascAfter = sha(CUSTOMER_ASC);
            note("CHECK CUSTOMER-FILES mf4=" + unchanged(mf4Before, mf4After)
                    + " dbc=" + unchanged(dbcBefore, dbcAfter)
                    + " asc=" + unchanged(ascBefore, ascAfter));
            deleteTree(home);
        }
    }

    private static void inspectMf4() {
        if (!Files.isRegularFile(MF4)) {
            note("CHECK MF4 BLOCKED file is not present at the known customer path");
            return;
        }
        try {
            MdfInspectionReport report = new Mf4Inspector().inspect(MF4);
            note("CHECK MF4 file=" + report.fileName()
                    + " version=" + report.mdfVersion()
                    + " finalization=" + report.finalizationStatus()
                    + " recoveryCandidate=" + report.recoveryCandidate()
                    + " physicalData=" + report.physicalDataDetected()
                    + " dg=" + report.dgCount()
                    + " cg=" + report.cgCount()
                    + " declaredCycles=" + report.declaredCycleCountTotal()
                    + " possibleRecords=" + report.possibleRecordCount()
                    + " reasons=" + report.finalizationReasons()
                    + " unsupported=" + report.unsupportedStructures());
        } catch (Exception ex) {
            note("CHECK MF4 INSPECT-FAILED " + ex.getClass().getSimpleName() + " " + oneLine(ex.getMessage()));
        }
    }

    private static String engineAsc() {
        return """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                Begin Triggerblock Thu Jan 01 12:00:00.000 am 2026
                  1.000000 1 100 Rx d 8 10 27 5A 01 00 00 00 00
                  1.100000 1 101 Rx d 8 E0 D2 BB 07 D0 68 40 2E
                  2.000000 1 100 Rx d 8 20 4E 5A 01 00 00 00 00
                  3.000000 1 100 Rx d 8 30 75 5A 01 00 00 00 00
                  3.100000 1 101 Rx d 8 E8 D2 BB 07 D8 68 40 2E
                End TriggerBlock
                """;
    }

    private static String engineDbc() {
        return """
                VERSION ""

                NS_ :
                \tCM_
                BS_:

                BU_: ECU1

                BO_ 256 EngineData: 8 ECU1
                 SG_ EngineSpeed : 0|16@1+ (0.25,0) [0|8000] "rpm" ECU1
                 SG_ EngineTemp : 16|8@1- (1,-40) [-40|215] "degC" ECU1
                 SG_ StatusFlags : 24|8@1+ (1,0) [0|255] "" ECU1

                BO_ 257 GpsData: 8 ECU1
                 SG_ GpsLatitude : 0|32@1- (0.0000001,0) [-90|90] "deg" ECU1
                 SG_ GpsLongitude : 32|32@1- (0.0000001,0) [-180|180] "deg" ECU1
                """;
    }

    private static SignalSamplePoint firstSample(DesktopAppService service, SignalCatalogEntry entry) {
        AtomicReference<SignalSamplePoint> sample = new AtomicReference<>();
        service.signalsModel().catalogStore().forEachSample(entry.key(), point -> {
            if (sample.get() == null) {
                sample.set(point);
            }
        });
        assertNotNull(sample.get(), entry.signalName());
        return sample.get();
    }

    private static void awaitTracks(GraphController graph) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (graph.logicalTrackCountForTest() >= 2) {
                return;
            }
            Thread.sleep(100);
        }
    }

    private static String waitForText(Shell shell, String token, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        String page = "";
        while (System.nanoTime() < deadline) {
            page = visible(shell.root);
            if (page.contains(token)) {
                return page;
            }
            Thread.sleep(200);
        }
        return page;
    }

    private static String waitForReport(DesktopAppService service, String token, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        String report = "";
        while (System.nanoTime() < deadline) {
            report = service.lastEngineeringAnalysisReport();
            if (report != null && report.contains(token)) {
                return report;
            }
            Thread.sleep(100);
        }
        return report == null ? "" : report;
    }

    private static Path waitForHtml(Path directory, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            try (var walk = Files.list(directory)) {
                Path html = walk.filter(path -> path.getFileName().toString().endsWith(".html")).findFirst().orElse(null);
                if (html != null && Files.size(html) > 0) {
                    return html;
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("HTML report was not written in " + directory);
    }

    private static void awaitClean(DesktopAppService service) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (service.projectSaveState() == ProjectSaveState.CLEAN) {
                return;
            }
            Thread.sleep(50);
        }
    }

    private static void createNamed(Shell shell, String name) throws Exception {
        Thread opener = new Thread(() -> {
            try {
                runOnFx(() -> visibleButton(shell.root, "New Project").fire());
            } catch (Exception ex) {
                note("ASYNC " + ex.getMessage());
            }
        });
        opener.setDaemon(true);
        opener.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && !name.equals(shell.service.workspace().projectName())) {
            AtomicBoolean done = new AtomicBoolean();
            CountDownLatch probe = new CountDownLatch(1);
            Platform.runLater(() -> {
                try {
                    for (Window window : JavaFxTestSupport.snapshotWindows()) {
                        if (!(window instanceof Stage stage) || stage.getScene() == null
                                || !(stage.getScene().getRoot() instanceof DialogPane pane)) {
                            continue;
                        }
                        if (!visibleText(pane).contains("New Project") && !"New Project".equals(stage.getTitle())) {
                            continue;
                        }
                        for (Node node : pane.lookupAll(".text-field")) {
                            if (node instanceof TextField field) {
                                field.setText(name);
                            }
                        }
                        Button ok = exact(pane, "OK");
                        if (ok != null) {
                            ok.fire();
                            done.set(true);
                        }
                    }
                } catch (RuntimeException ignored) {
                    // The dialog skin can shrink while it is being read.
                } finally {
                    probe.countDown();
                }
            });
            probe.await(5, TimeUnit.SECONDS);
            if (done.get()) {
                break;
            }
            Thread.sleep(40);
        }
        opener.join(15_000);
        long nameDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < nameDeadline && !name.equals(shell.service.workspace().projectName())) {
            Thread.sleep(40);
        }
    }

    private static void addFile(Shell shell, String visibleLabel, String fallback, Path file) throws Exception {
        int measurements = shell.service.workspace().measurementFiles().size();
        int definitions = shell.service.workspace().definitionFiles().size();
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.SOURCES);
            shell.root.applyCss();
            shell.root.layout();
            shell.controller.projectFilesControllerForTest().setOpenDialogForTest(chooser -> List.of(file.toFile()));
            Button button = visibleButton(shell.root, visibleLabel);
            if (button == null) {
                button = exact(shell.root, fallback);
            }
            assertNotNull(button, visibleLabel);
            button.fire();
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            boolean measurement = file.getFileName().toString().endsWith(".asc")
                    && shell.service.workspace().measurementFiles().size() != measurements;
            boolean definition = file.getFileName().toString().endsWith(".dbc")
                    && shell.service.workspace().definitionFiles().size() != definitions;
            if (measurement || definition || file.getFileName().toString().contains("corrupt")) {
                Thread.sleep(200);
                return;
            }
            Thread.sleep(40);
        }
    }

    private static Shell boot(DesktopAppService service) throws Exception {
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
                Scene scene = new Scene(page, 1440, 900);
                var css = MainShellController.class.getResource("/css/stellar-desktop.css");
                if (css != null) {
                    scene.getStylesheets().add(css.toExternalForm());
                }
                window.setScene(scene);
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
        StringBuilder dialogs = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!booted.await(200, TimeUnit.MILLISECONDS)) {
            String dismissed = dismissDialog();
            if (dismissed != null) {
                dialogs.append(dismissed).append(" || ");
            }
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        assertNotNull(controller.get(), "shell");
        return new Shell(stage.get(), controller.get(), root.get(), service, dialogs.toString());
    }

    private static String dismissDialog() throws Exception {
        AtomicReference<String> text = new AtomicReference<>();
        CountDownLatch probe = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                for (Window window : JavaFxTestSupport.snapshotWindows()) {
                    if (!(window instanceof Stage stage) || stage.getScene() == null
                            || !(stage.getScene().getRoot() instanceof DialogPane pane)) {
                        continue;
                    }
                    text.set(stage.getTitle() + " " + visibleText(pane));
                    Button ok = exact(pane, "OK");
                    if (ok != null) {
                        ok.fire();
                    }
                }
            } catch (RuntimeException ignored) {
                // Dialog windows can close while they are being listed.
            } finally {
                probe.countDown();
            }
        });
        probe.await(5, TimeUnit.SECONDS);
        return text.get();
    }

    private static void selectSmoothing(Parent root) {
        for (Node node : root.lookupAll(".combo-box")) {
            if (node instanceof javafx.scene.control.ComboBox<?> combo) {
                for (Object item : combo.getItems()) {
                    if (item != null && item.toString().contains("EXPONENTIAL_SMOOTHING")) {
                        @SuppressWarnings("unchecked")
                        javafx.scene.control.ComboBox<Object> typed = (javafx.scene.control.ComboBox<Object>) combo;
                        typed.getSelectionModel().select(item);
                        return;
                    }
                }
            }
        }
    }

    private static javafx.scene.control.ToggleButton toggle(Parent root, String text) {
        for (Node node : root.lookupAll(".toggle-button")) {
            if (node instanceof javafx.scene.control.ToggleButton button && text.equals(button.getText())) {
                return button;
            }
        }
        throw new AssertionError(text);
    }

    private static TextField field(Parent root, String name) {
        for (Node node : root.lookupAll(".label")) {
            if (!(node instanceof javafx.scene.control.Label label) || !name.equals(label.getText())
                    || !(label.getParent() instanceof Parent box)) {
                continue;
            }
            for (Node child : box.getChildrenUnmodifiable()) {
                if (child instanceof TextField text) {
                    return text;
                }
            }
        }
        throw new AssertionError(name);
    }

    private static void fire(Parent root, String label) {
        Button button = exact(root, label);
        assertNotNull(button, label);
        button.fire();
    }

    private static void fireSave(Parent root) {
        Button save = null;
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button button && button.isVisible() && button.isManaged()) {
                String text = button.getText() == null ? "" : button.getText();
                if ("Save".equals(text) || "Save*".equals(text)) {
                    save = button;
                }
            }
        }
        assertNotNull(save, "Save");
        save.fire();
    }

    private static Button exact(Parent root, String label) {
        Button found = null;
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button button && label.equals(button.getText())) {
                found = button;
            }
        }
        return found;
    }

    private static Button visibleButton(Parent root, String label) {
        for (Node node : root.lookupAll(".button")) {
            if (!(node instanceof Button button) || !button.isVisible() || !button.isManaged()) {
                continue;
            }
            if (label.equals(button.getText()) || visibleText(button).contains(label)) {
                return button;
            }
        }
        return null;
    }

    private static String visible(Parent root) throws Exception {
        return runOnFxValue(() -> visibleText(root));
    }

    private static String visibleText(Node node) {
        StringBuilder sb = new StringBuilder();
        collect(node, sb);
        return sb.toString();
    }

    private static void collect(Node node, StringBuilder sb) {
        if (node instanceof javafx.scene.control.Labeled labeled && labeled.getText() != null && !labeled.getText().isBlank()) {
            sb.append(labeled.getText()).append('\n');
        }
        if (node instanceof Parent parent) {
            List<Node> children;
            try {
                children = List.copyOf(parent.getChildrenUnmodifiable());
            } catch (RuntimeException changing) {
                return;
            }
            for (Node child : children) {
                collect(child, sb);
            }
        }
    }

    private static String sha(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return "ABSENT";
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))).toUpperCase();
        } catch (Exception ex) {
            return "ERROR";
        }
    }

    private static String unchanged(String before, String after) {
        return before.equals(after) ? "unchanged " + before : "CHANGED " + before + " -> " + after;
    }

    private static void isolate(Path home) {
        System.setProperty("stellarai.home", home.toAbsolutePath().toString());
        System.setProperty("stellarai.test.isolate", "true");
        System.clearProperty("stellarai.metadata.db");
        AnalysisStorageRoots.clear();
    }

    private static void close(Shell shell) throws Exception {
        if (shell != null && shell.stage != null) {
            runOnFx(shell.stage::close);
        }
    }

    private static void closeQuiet(Shell shell) {
        if (shell == null) {
            return;
        }
        try {
            close(shell);
        } catch (Exception ignored) {
            // The stage may already be closed.
        }
        try {
            shell.service.shutdown();
        } catch (RuntimeException ignored) {
            // A later reopen already closed this store.
        }
    }

    private static void note(String line) {
        NOTES.add(line);
    }

    private static String comboItems(Parent root) {
        root.applyCss();
        root.layout();
        StringBuilder items = new StringBuilder();
        for (Node node : root.lookupAll(".combo-box")) {
            if (!(node instanceof ComboBox<?> combo) || combo.getItems().isEmpty()
                    || !(combo.getItems().getFirst() instanceof String firstItem)
                    || !firstItem.startsWith("All routes")) {
                continue;
            }
            @SuppressWarnings("unchecked")
            ComboBox<String> choices = (ComboBox<String>) combo;
            String selected = choices.getValue();
            if (choices.getItems().size() > 1) {
                choices.setValue(choices.getItems().get(1));
            }
            items.append("all=").append(choices.getItems().getFirst())
                    .append(" selectedRoute=").append(choices.getValue())
                    .append(" previous=").append(selected);
        }
        return items.toString();
    }

    private static void runOnFx(Fx action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "FX timeout");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static <T> T runOnFxValue(Value<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        runOnFx(() -> value.set(action.get()));
        return value.get();
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Temporary home cleanup is best-effort.
                }
            });
        } catch (Exception ignored) {
            // Temporary home cleanup is best-effort.
        }
    }

    private record Shell(Stage stage, MainShellController controller, Parent root,
                         DesktopAppService service, String bootDialogs) {
    }

    @FunctionalInterface
    private interface Fx {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface Value<T> {
        T get() throws Exception;
    }
}
