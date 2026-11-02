package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.model.AnalysisRequest;
import com.stellar.platform.analysis.model.AnalysisResult;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.AnalysisType;
import com.stellar.platform.analysis.model.SignalStatistics;
import com.stellar.platform.desktop.presentation.AnalysisScope;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.ProjectInventoryJobState;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.map.MapRoutePane;
import com.stellar.platform.desktop.ui.graph.map.RouteCanvas;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.graph.GraphQueryProvider;
import com.stellar.platform.storage.metadata.ProjectDashboardSummary;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One isolated JavaFX project containing a verified DAT, LOG, ASC, BLF and N597 MF4.
 * 2.dbc is associated only with the ASC and BLF. The N597 Rev7 DBC is associated only with N597.
 */
class MixedFormatJavaFxAcceptanceTest {

    private static final Path ROOT = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData");
    private static final Path DAT = ROOT.resolve(
            "dat files 3.1\\.dat files 3.1\\Xenon new script C910P41 2020-02-05 12-03-25-688233 Partition 0.dat");
    private static final Path LOG = ROOT.resolve(
            ".log\\Xenon new script C910P41 2020-02-05 13-21-33-780996 Partition 0.log");
    private static final Path ASC = ROOT.resolve(
            "ASC\\ASCII_eCC21_KA04NC7760_Bengaluru_09_08_2023_0002360_20230814_134817.asc");
    private static final Path BLF = ROOT.resolve(
            "BLF\\NEDC_STP_10002_ARAI 2023-07-20 13-38-16-551807 Partition 0.blf");
    private static final Path MF4 = ROOT.resolve(
            "MF4Files\\City - Highway_MF4\\N597_AFT_0001850_20250201_143215.mf4");
    private static final Path DBC2 = ROOT.resolve("dbcfiles\\2.dbc");
    private static final Path N597_DBC = ROOT.resolve(
            "MF4Files\\City - Highway_MF4\\Required DBC\\N597_ABSEMS_CAN dbc _Rev7 (1).dbc");
    private static final Path EVIDENCE = Path.of("D:\\DesktopApp\\DriveT\\docs\\qa-handoff\\mixed-format-javafx.txt");
    private static final String PROJECT = "Mixed format acceptance";
    private static final StringBuilder NOTES = new StringBuilder();

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
        note("started=" + Instant.now());
    }

    @AfterAll
    static void writeEvidence() throws Exception {
        note("finished=" + Instant.now());
        Files.createDirectories(EVIDENCE.getParent());
        Files.writeString(EVIDENCE, NOTES.toString());
    }

    @Test
    @Timeout(value = 8, unit = TimeUnit.HOURS)
    void fiveFormatsCoexistInOneJavaFxProject() throws Exception {
        for (Path file : List.of(DAT, LOG, ASC, BLF, MF4, DBC2, N597_DBC)) {
            assertTrue(Files.isRegularFile(file), file.toString());
            note("source " + file.getFileName() + " sha256=" + sha256(file) + " size=" + Files.size(file));
        }
        Path home = Files.createTempDirectory("stellar-mixed-format");
        Path db = home.resolve("meta.db");
        assertFalse(home.toString().toLowerCase(Locale.ROOT).contains("stellaraie2e"));
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        System.setProperty("stellarai.metadata.db", db.toString());
        DesktopAppService service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
        ShellFx shell = boot(service);
        try {
            createProject(shell, PROJECT);
            addMeasurement(shell, DAT);
            addMeasurement(shell, LOG);
            addMeasurement(shell, ASC);
            addMeasurement(shell, BLF);
            addMeasurement(shell, MF4);
            addDefinition(shell, DBC2);
            addDefinition(shell, N597_DBC);
            await("five measurements", () -> service.workspace().measurementFiles().size() == 5, 180);
            await("two definitions", () -> service.workspace().definitionFiles().size() == 2, 60);
            assertKinds(service);
            associate(service, "134817", "2.dbc");
            associate(service, "13-38-16", "2.dbc");
            associate(service, "143215", "Rev7");
            assertNoDefinition(service, "12-03-25");
            assertNoDefinition(service, "13-21-33");
            awaitInventory(service, "12-03-25");
            awaitInventory(service, "13-21-33");
            awaitInventory(service, "134817");
            awaitInventory(service, "13-38-16");
            awaitInventory(service, "143215");
            int runsBeforeDirect = service.persistedProcessingRuns().size();
            directProcess(shell, service);
            ProjectProcessingStatus direct = awaitDirect(service, runsBeforeDirect, 8);
            note("directStatus=" + direct + " catalog=" + service.signalsModel().catalog().entries().size()
                    + " runs=" + service.persistedProcessingRuns().size());
            compare(service, "12-03-25", new Sample("IN_Vehicle_speed", 42_646_110L, 25.640625, null, "km/h", 9638));
            compare(service, "12-03-25", new Sample("IN_Coolant_temperature", 129_229_128L, 94.25, null, "degC", 9637));
            compare(service, "13-21-33", new Sample("IN_Vehicle_speed", 60_212_493L, 55.4921875, null, "km/h", 9638));
            boolean can = startProcessing(shell);
            assertTrue(can, "CAN Start Processing stayed disabled");
            ProjectProcessingStatus canStatus = awaitSignal(service, "143215", "DISPLAY_SPEED", 30);
            note("canStatus=" + canStatus + " catalog=" + service.signalsModel().catalog().entries().size()
                    + " warnings=" + service.processingWarningReport().count()
                    + " runs=" + service.persistedProcessingRuns().size());
            compare(service, "134817", new Sample("DCDC_OUT_VOLTAGE", 0L, 12.3, 1230L, "V", 2483));
            compare(service, "13-38-16", new Sample("DCDC_OUT_VOLTAGE", 3_736_300L, 12.5, 1250L, "V", 12639));
            compare(service, "143215", new Sample("REAR_BRAKE_SWITCH_STATUS", 0L, 2.0, 2L, "", 0));
            compare(service, "143215", new Sample("DISPLAY_SPEED", 76_131_500_000L, 0.2, 2L, "km/h", 0));
            compare(service, "143215", new Sample("TARGET_CLUTCH_TORQUE_REQ", 786_557_560_000L, 17.5, 275L, "Nm", 0));
            assertDistinctSpeeds(service);
            homeKpi(shell, service, db);
            graph(shell, service);
            gps(shell, service);
            analytics(service);
            html(shell, home);
            String projectId = service.currentProjectId();
            int runs = service.persistedProcessingRuns().size();
            snapshot("1-after-terminal", service, db, projectId);
            snapshot("2-before-save", service, db, projectId);
            int logicalBeforeSave = service.signalsModel().catalog().entries().size();
            int distinctBeforeSave = distinctKeys(db, projectId);
            save(shell, service);
            snapshot("3-after-save", service, db, projectId);
            snapshot("4-before-close", service, db, projectId);
            close(shell);
            service.shutdown();
            reopenAndCheck(db, projectId, runs, logicalBeforeSave, distinctBeforeSave);
            lifecycle(db, projectId);
            for (Path file : List.of(DAT, LOG, ASC, BLF, MF4, DBC2, N597_DBC)) {
                note("hashAfter " + file.getFileName() + "=" + sha256(file));
            }
        } finally {
            close(shell);
            try {
                service.shutdown();
            } catch (RuntimeException ignored) {
                // A later reopen owns the store.
            }
        }
    }

    private static void assertKinds(DesktopAppService service) {
        assertKind(service, "12-03-25", ".dat", FileKind.MEASUREMENT_DAT);
        assertKind(service, "13-21-33", ".log", FileKind.MEASUREMENT_LOG);
        assertKind(service, "134817", ".asc", FileKind.MEASUREMENT_ASC);
        assertKind(service, "13-38-16", ".blf", FileKind.MEASUREMENT_BLF);
        assertKind(service, "143215", ".mf4", FileKind.MEASUREMENT_MF4);
        long ids = service.workspace().measurementFiles().stream().map(WorkspaceFile::id).distinct().count();
        assertEquals(5, ids, "source identities collided");
    }

    private static void assertKind(DesktopAppService service, String name, String extension, FileKind kind) {
        WorkspaceFile file = measurement(service, name);
        note("import " + file.fileName() + " kind=" + file.kind() + " definition=" + file.associatedDefinitionId());
        assertTrue(file.fileName().toLowerCase(Locale.ROOT).endsWith(extension), file.fileName());
        assertEquals(kind, file.kind(), file.fileName());
    }

    private static void associate(DesktopAppService service, String measurementPart, String definitionPart) {
        WorkspaceFile measurement = measurement(service, measurementPart);
        WorkspaceFile definition = service.workspace().definitionFiles().stream()
                .filter(file -> file.fileName().contains(definitionPart))
                .findFirst()
                .orElseThrow();
        service.selectMeasurement(measurement.id());
        service.selectDefinition(definition.id());
        note("associate " + measurement.fileName() + " -> " + definition.fileName()
                + " id=" + measurement.associatedDefinitionId());
        assertEquals(definition.id(), measurement.associatedDefinitionId(), measurement.fileName());
    }

    private static void assertNoDefinition(DesktopAppService service, String measurementPart) {
        WorkspaceFile file = measurement(service, measurementPart);
        note("unassociated " + file.fileName() + " definition=" + file.associatedDefinitionId());
        assertTrue(file.associatedDefinitionId() == null || file.associatedDefinitionId().isBlank(), file.fileName());
    }

    private static void directProcess(ShellFx shell, DesktopAppService service) throws Exception {
        int before = service.persistedProcessingRuns().size();
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.SOURCES);
            shell.root.applyCss();
            shell.root.layout();
            var list = shell.controller.projectFilesControllerForTest().measurementSourceListForTest();
            list.getItems().stream()
                    .filter(file -> file.fileName().contains("13-21-33"))
                    .findFirst()
                    .ifPresent(file -> {
                        list.getSelectionModel().select(file);
                        service.selectMeasurement(file.id());
                    });
            shell.root.applyCss();
            shell.root.layout();
            Button direct = shell.controller.projectFilesControllerForTest().continueToDecodeButtonForTest();
            note("directLabel=" + direct.getText() + " disabled=" + direct.isDisabled());
            assertEquals("Direct Processing", direct.getText());
            direct.fire();
        });
        note("directRunsBefore=" + before);
    }

    private static void compare(DesktopAppService service, String sourcePart, Sample sample) {
        SignalCatalogEntry entry = entry(service, sourcePart, sample.name());
        if (sample.expectedCount() > 0) {
            assertEquals(sample.expectedCount(), entry.sampleCount(), sample.name() + " " + sourcePart);
        }
        if (sample.unit() != null && !sample.unit().isBlank()) {
            assertEquals(sample.unit(), entry.unit(), sample.name());
        }
        List<SignalSamplePoint> points = query(service).queryRange(
                entry.key(), sample.timestampNanos(), sample.timestampNanos(), 5);
        assertFalse(points.isEmpty(), sample.name() + " missing at " + sample.timestampNanos());
        SignalSamplePoint point = points.getFirst();
        assertEquals(sample.timestampNanos(), point.timestampNanos(), sample.name());
        assertEquals(sample.physical(), point.physicalValue(), 1e-6, sample.name());
        if (sample.raw() != null) {
            assertEquals(sample.raw(), point.rawValue(), sample.name());
        }
        note("compare " + sample.name() + " source=" + entry.sourceFile()
                + " t=" + point.timestampNanos() + " raw=" + point.rawValue()
                + " physical=" + point.physicalValue() + " unit=" + entry.unit()
                + " validity=" + point.validity() + " samples=" + entry.sampleCount());
    }

    private static void assertDistinctSpeeds(DesktopAppService service) {
        List<SignalCatalogEntry> speeds = physicalEntries(service).stream()
                .filter(entry -> "IN_Vehicle_speed".equals(entry.signalName()))
                .toList();
        note("speedCopies=" + speeds.stream().map(entry -> entry.sourceFile() + ":" + entry.sampleCount()).toList());
        assertEquals(2, speeds.size(), "IN_Vehicle_speed copies");
        assertEquals(2, speeds.stream().map(SignalCatalogEntry::sourceFile).distinct().count(), "speed sources");
    }

    private static void homeKpi(ShellFx shell, DesktopAppService service, Path db) throws Exception {
        runOnFx(() -> shell.controller.openRoute(WorkspaceRoute.HOME));
        Thread.sleep(500);
        int dashboard = service.projectDashboardSummaries().stream()
                .filter(summary -> PROJECT.equals(summary.name()))
                .mapToInt(ProjectDashboardSummary::signalCount)
                .findFirst()
                .orElse(-1);
        int jdbc = distinctKeys(db, service.currentProjectId());
        int memory = physicalEntries(service).size();
        note("homeKpi dashboardDistinctKeys=" + dashboard + " jdbcDistinctKeys=" + jdbc
                + " physicalRows=" + memory + " logicalCatalog=" + service.signalsModel().catalog().entries().size());
        assertTrue(dashboard > 0, "dashboard signal count");
        assertEquals(dashboard, jdbc, "Home KPI distinct keys");
    }

    private static int distinctKeys(Path db, String projectId) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db + "?busy_timeout=3000");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(DISTINCT signal_key) FROM signal_catalog WHERE project_id=?")) {
            statement.setString(1, projectId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private static void graph(ShellFx shell, DesktopAppService service) throws Exception {
        GraphController graph = shell.controller.graphControllerForTest();
        runOnFx(() -> shell.controller.openRoute(WorkspaceRoute.GRAPHS));
        focusSignal(shell, graph, service, "12-03-25", "IN_Vehicle_speed");
        runOnFx(() -> graph.setLayoutModeForTest(false));
        note("overlayMode=" + graph.layoutModeForTest() + " rendered=" + graph.lastRenderedCountForTest());
        assertEquals("OVERLAY", graph.layoutModeForTest());
        runOnFx(() -> {
            graph.setLayoutModeForTest(true);
            graph.zoomInForTest();
            graph.panLeftForTest();
            graph.fitForTest();
            graph.resetViewForTest();
        });
        assertEquals("STACKED", graph.layoutModeForTest());
        String hover = hover(graph, "IN_Vehicle_speed");
        note("graph dat stacked=" + graph.layoutModeForTest()
                + " rendered=" + graph.lastRenderedCountForTest()
                + " status=" + oneLine(graph.graphStatusForTest())
                + " hover=" + oneLine(hover));
        assertTrue(hover.contains("IN_Vehicle_speed"), hover);
        assertTrue(hover.contains("12-03-25"), hover);
        focusSignal(shell, graph, service, "13-21-33", "IN_Vehicle_speed");
        String logHover = hoverAfterReady(graph, "13-21-33");
        note("graph log hover=" + oneLine(logHover) + " rendered=" + graph.lastRenderedCountForTest()
                + " confirmed=" + logHover.contains("13-21-33"));
        focusSignal(shell, graph, service, "143215", "DISPLAY_SPEED");
        String mf4Hover = hoverAfterReady(graph, "143215");
        note("graph mf4 hover=" + oneLine(mf4Hover) + " rendered=" + graph.lastRenderedCountForTest()
                + " confirmed=" + (mf4Hover.contains("DISPLAY_SPEED") && mf4Hover.contains("143215")));
    }

    private static void focusSignal(ShellFx shell, GraphController graph, DesktopAppService service,
                                    String source, String name) throws Exception {
        WorkspaceFile file = measurement(service, source);
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.GRAPHS);
            graph.selectAnalysisScopeForTest(AnalysisScope.single(file.projectSourceId().value(), file.fileName()));
        });
        await("explorer " + source, () -> graph.explorerRowsForTest().stream()
                .anyMatch(item -> name.equals(item.signalName())), 60);
        runOnFx(() -> graph.explorerRowsForTest().stream()
                .filter(item -> name.equals(item.signalName()))
                .findFirst()
                .ifPresent(item -> {
                    if (!graph.isSelectedForTest(item.key())) {
                        graph.fireExplorerCheckboxForTest(item.key());
                    }
                }));
        await("rendered " + name + " " + source, () -> graph.selectedSignalCountForTest() >= 1
                && graph.lastRenderedCountForTest() >= 1, 90);
        note("graphFocus " + source + " " + name + " selected=" + graph.selectedSignalCountForTest()
                + " rendered=" + graph.lastRenderedCountForTest());
    }

    private static String hoverAfterReady(GraphController graph, String name) throws Exception {
        String last = "";
        for (int attempt = 0; attempt < 4; attempt++) {
            last = hover(graph, name);
            if (last.contains(name)) {
                return last;
            }
            Thread.sleep(500);
        }
        return last;
    }

    private static String hover(GraphController graph, String name) throws Exception {
        return runOnFxValue(() -> {
            var canvas = graph.canvasForTest();
            String last = "";
            for (int y = 24; y <= 640; y += 20) {
                canvas.fireEvent(new MouseEvent(
                        canvas, canvas, MouseEvent.MOUSE_MOVED,
                        420, y, 420, y, MouseButton.NONE, 0,
                        false, false, false, false, false, false, false, false, false, false, null));
                last = graph.liveCursorForTest() == null ? "" : graph.liveCursorForTest();
                if (last.contains(name)) {
                    return last;
                }
            }
            return last;
        });
    }

    private static void gps(ShellFx shell, DesktopAppService service) throws Exception {
        GraphController graph = shell.controller.graphControllerForTest();
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.GRAPHS);
            graph.selectAnalysisScopeForTest(AnalysisScope.allMeasurements(5));
            graph.selectMapRouteTab();
        });
        await("gps routes", () -> {
            MapRoutePane pane = graph.mapRoutePaneForTest();
            return pane != null && pane.gpsSegmentsForTest().stream().mapToInt(segment -> segment.sampleCount()).sum() > 1000;
        }, 120);
        runOnFx(() -> {
            MapRoutePane pane = graph.mapRoutePaneForTest();
            RouteCanvas canvas = pane.routeCanvasForTest();
            canvas.paintForTest();
            canvas.zoom(1.3);
            canvas.fitRoute();
            int points = pane.gpsSegmentsForTest().stream().mapToInt(segment -> segment.sampleCount()).sum();
            var marks = canvas.endpointMarksForTest();
            note("gpsAll points=" + points + " segments=" + pane.gpsSegmentsForTest().size()
                    + " policy=" + canvas.endpointPolicyForTest() + " marks=" + marks.size()
                    + " mapping=" + oneLine(pane.mappingStatusForTest())
                    + " status=" + oneLine(pane.routeStatusForTest()));
            marks.forEach(mark -> note("gpsAll mark " + mark.role() + " lat=" + mark.latitude() + " lon=" + mark.longitude()));
            assertEquals(RouteCanvas.EndpointPolicy.GLOBAL, canvas.endpointPolicyForTest());
            assertEquals(2, marks.size());
            String mapping = pane.mappingStatusForTest() == null ? "" : pane.mappingStatusForTest();
            assertTrue(mapping.contains("no file-to-file connections"), mapping);
            assertTrue(mapping.contains("Recording order unknown") || mapping.contains("not ordered"), mapping);
        });
        WorkspaceFile dat = measurement(service, "12-03-25");
        runOnFx(() -> graph.selectAnalysisScopeForTest(AnalysisScope.single(dat.projectSourceId().value(), dat.fileName())));
        await("dat route", () -> graph.mapRoutePaneForTest().routeSizeForTest() > 0
                || graph.mapRoutePaneForTest().gpsSegmentsForTest().stream()
                .mapToInt(segment -> segment.sampleCount()).sum() > 0, 60);
        runOnFx(() -> {
            RouteCanvas canvas = graph.mapRoutePaneForTest().routeCanvasForTest();
            canvas.paintForTest();
            var marks = canvas.endpointMarksForTest();
            int points = Math.max(graph.mapRoutePaneForTest().routeSizeForTest(),
                    graph.mapRoutePaneForTest().gpsSegmentsForTest().stream()
                            .mapToInt(segment -> segment.sampleCount()).sum());
            note("gpsDat points=" + points + " policy=" + canvas.endpointPolicyForTest() + " marks=" + marks.size());
            marks.forEach(mark -> note("gpsDat mark " + mark.role() + " lat=" + mark.latitude() + " lon=" + mark.longitude()));
            assertEquals(RouteCanvas.EndpointPolicy.OWN_ROUTE, canvas.endpointPolicyForTest());
            assertEquals(2, marks.size());
            assertEquals(6128, points, "DAT GPS points");
            assertEquals(18.667971483866374, marks.getFirst().latitude(), 1e-6);
        });
    }

    private static void analytics(DesktopAppService service) {
        SignalCatalogEntry speed = entry(service, "12-03-25", "IN_Vehicle_speed");
        AnalysisResult result = service.runEngineeringAnalysis(AnalysisRequest.builder(AnalysisType.STATISTICS)
                .projectId(service.currentProjectId())
                .addSignal(speed.key())
                .build());
        assertEquals(AnalysisStatus.COMPLETED, result.status(), result.message());
        SignalStatistics stats = result.statistics().getFirst();
        note("analytics DAT speed samples=" + stats.sampleCount() + " min=" + stats.min() + " max=" + stats.max()
                + " catalog=" + speed.sampleCount() + " aggregateOracle=UNVERIFIED");
        assertEquals(speed.sampleCount(), stats.sampleCount(), "analytics merged another source");
        assertTrue(stats.min() <= 25.640625 && stats.max() >= 25.640625);
    }

    private static void html(ShellFx shell, Path home) throws Exception {
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
        runOnFx(() -> {
            shell.controller.investigationControllerForTest().setReportDestinationForTest(PROJECT, reports);
            button(shell.root, "Generate HTML Report").fire();
        });
        await("html", () -> htmlReady(reports), 180);
        Path html = Files.list(reports).filter(path -> path.getFileName().toString().endsWith(".html")).findFirst().orElseThrow();
        String text = Files.readString(html);
        note("html=" + html.getFileName() + " bytes=" + Files.size(html)
                + " project=" + text.contains(PROJECT)
                + " dat=" + text.contains("12-03-25")
                + " log=" + text.contains("13-21-33")
                + " asc=" + text.contains("134817")
                + " blf=" + text.contains("13-38-16")
                + " mf4=" + text.contains("143215")
                + " speed=" + text.contains("IN_Vehicle_speed")
                + " display=" + text.contains("DISPLAY_SPEED"));
        assertTrue(text.contains(PROJECT));
        assertTrue(text.contains("DISPLAY_SPEED"));
        assertTrue(text.contains("IN_Vehicle_speed"));
    }

    private static void save(ShellFx shell, DesktopAppService service) throws Exception {
        assertNotEqualsRunning(service);
        runOnFx(() -> {
            Button save = buttonStarting(shell.root, "Save");
            assertNotNull(save, "Save");
            save.fire();
        });
        await("saved", () -> service.projectSaveState() != null
                && service.projectSaveState().name().contains("CLEAN"), 60);
        note("saveState=" + service.projectSaveState());
    }

    private static void snapshot(String point, DesktopAppService service, Path db, String projectId) throws Exception {
        StringBuilder text = new StringBuilder();
        text.append("snapshot ").append(point);
        text.append(" status=").append(service.workspace().processingStatus());
        text.append(" logical=").append(service.signalsModel().catalog().entries().size());
        text.append(" physical=").append(physicalEntries(service).size());
        text.append(" runs=").append(service.persistedProcessingRuns().size());
        text.append(" graphSelected=").append(service.signalsModel().workspaceKeys().size());
        text.append(" graphMissing=").append(service.graphInvestigation().unavailableTraces().size());
        for (WorkspaceFile file : service.workspace().measurementFiles()) {
            text.append("\n  memory ").append(file.id()).append(" ").append(file.fileName())
                    .append(" kind=").append(file.kind())
                    .append(" definition=").append(file.associatedDefinitionId());
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db + "?busy_timeout=5000")) {
            try (PreparedStatement files = connection.prepareStatement(
                    "SELECT file_id, path, kind, associated_definition_id, selected_measurement, selected_definition "
                            + "FROM project_files WHERE project_id=? ORDER BY path")) {
                files.setString(1, projectId);
                try (ResultSet rs = files.executeQuery()) {
                    while (rs.next()) {
                        text.append("\n  sql-file ").append(rs.getString(1))
                                .append(" kind=").append(rs.getString(3))
                                .append(" definition=").append(rs.getString(4))
                                .append(" selectedMeasurement=").append(rs.getInt(5))
                                .append(" selectedDefinition=").append(rs.getInt(6))
                                .append(" path=").append(rs.getString(2));
                    }
                }
            }
            try (PreparedStatement runs = connection.prepareStatement(
                    "SELECT run_id, status FROM processing_runs WHERE project_id=?")) {
                runs.setString(1, projectId);
                try (ResultSet rs = runs.executeQuery()) {
                    while (rs.next()) {
                        text.append("\n  sql-run ").append(rs.getString(1)).append(" ").append(rs.getString(2));
                    }
                }
            }
            try (PreparedStatement artifacts = connection.prepareStatement(
                    "SELECT artifact_id, source_id, channel_id, definition_id, status, signal_count, sample_count "
                            + "FROM processing_artifacts WHERE project_id=?")) {
                artifacts.setString(1, projectId);
                try (ResultSet rs = artifacts.executeQuery()) {
                    while (rs.next()) {
                        text.append("\n  sql-artifact ").append(rs.getString(1))
                                .append(" source=").append(rs.getString(2))
                                .append(" channel=").append(rs.getInt(3))
                                .append(" definition=").append(rs.getString(4))
                                .append(" status=").append(rs.getString(5))
                                .append(" signals=").append(rs.getInt(6))
                                .append(" samples=").append(rs.getLong(7));
                    }
                }
            }
            try (PreparedStatement catalog = connection.prepareStatement(
                    "SELECT source_id, COUNT(DISTINCT signal_key), COALESCE(SUM(sample_count),0) "
                            + "FROM signal_catalog WHERE project_id=? GROUP BY source_id")) {
                catalog.setString(1, projectId);
                try (ResultSet rs = catalog.executeQuery()) {
                    while (rs.next()) {
                        text.append("\n  sql-catalog source=").append(rs.getString(1))
                                .append(" distinctKeys=").append(rs.getInt(2))
                                .append(" samples=").append(rs.getLong(3));
                    }
                }
            }
        }
        for (SignalCatalogEntry entry : physicalEntries(service)) {
            if ("IN_Vehicle_speed".equals(entry.signalName()) || "DISPLAY_SPEED".equals(entry.signalName())) {
                text.append("\n  physical ").append(entry.signalName())
                        .append(" source=").append(entry.sourceFile())
                        .append(" samples=").append(entry.sampleCount());
            }
        }
        note(text.toString());
    }

    private static void reopenAndCheck(Path db, String projectId, int runs,
                                       int logicalBeforeSave, int distinctBeforeSave) throws Exception {
        DesktopAppService reopened = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
        ShellFx again = boot(reopened);
        try {
            runOnFx(() -> again.controller.requestOpenSavedProject(projectId));
            await("reopen", () -> reopened.workspace().measurementFiles().size() == 5, 120);
            snapshot("5-after-reopen", reopened, db, projectId);
            note("reopen files=" + reopened.workspace().measurementFiles().stream()
                    .map(file -> file.fileName() + ":" + file.kind() + ":" + file.associatedDefinitionId()).toList()
                    + " definitions=" + reopened.workspace().definitionFiles().stream().map(WorkspaceFile::fileName).toList()
                    + " catalog=" + reopened.signalsModel().catalog().entries().size()
                    + " runs=" + reopened.persistedProcessingRuns().size()
                    + " status=" + reopened.workspace().processingStatus()
                    + " graphMissing=" + reopened.graphInvestigation().unavailableTraces().size());
            assertEquals(5, reopened.workspace().measurementFiles().size());
            assertEquals(2, reopened.workspace().definitionFiles().size());
            assertEquals(runs, reopened.persistedProcessingRuns().size(), "reopen created another processing run");
            assertNull(measurement(reopened, "12-03-25").associatedDefinitionId(), "DAT gained a DBC");
            assertNull(measurement(reopened, "13-21-33").associatedDefinitionId(), "LOG gained a DBC");
            assertEquals(measurement(reopened, "134817").associatedDefinitionId(),
                    measurement(reopened, "13-38-16").associatedDefinitionId(), "ASC and BLF definitions diverged");
            assertTrue(reopened.workspace().definitionFiles().stream()
                    .anyMatch(file -> file.fileName().contains("2.dbc")
                            && file.id().equals(measurement(reopened, "134817").associatedDefinitionId())));
            assertTrue(reopened.workspace().definitionFiles().stream()
                    .anyMatch(file -> file.fileName().contains("Rev7")
                            && file.id().equals(measurement(reopened, "143215").associatedDefinitionId())));
            assertEquals(logicalBeforeSave, reopened.signalsModel().catalog().entries().size(),
                    "logical catalog changed across reopen");
            assertEquals(distinctBeforeSave, distinctKeys(db, projectId), "persisted distinct keys changed");
            assertTrue(reopened.graphInvestigation().unavailableTraces().isEmpty(),
                    reopened.graphInvestigation().unavailableTraces().toString());
            compare(reopened, "12-03-25", new Sample("IN_Vehicle_speed", 42_646_110L, 25.640625, null, "km/h", 9638));
            compare(reopened, "13-21-33", new Sample("IN_Vehicle_speed", 60_212_493L, 55.4921875, null, "km/h", 9638));
            compare(reopened, "143215", new Sample("DISPLAY_SPEED", 76_131_500_000L, 0.2, 2L, "km/h", 0));
            compare(reopened, "134817", new Sample("DCDC_OUT_VOLTAGE", 0L, 12.3, 1230L, "V", 2483));
        } finally {
            close(again);
            reopened.shutdown();
        }
    }

    private static void lifecycle(Path db, String projectId) throws Exception {
        DesktopAppService service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
        ShellFx shell = boot(service);
        try {
            runOnFx(() -> shell.controller.requestOpenSavedProject(projectId));
            await("lifecycle open", () -> service.workspace().measurementFiles().size() == 5, 120);
            long before = entry(service, "134817", "DCDC_OUT_VOLTAGE").sampleCount();
            long blf = entry(service, "13-38-16", "DCDC_OUT_VOLTAGE").sampleCount();
            note("lifecycle before asc=" + before + " blf=" + blf);
            runOnFx(() -> {
                shell.controller.openRoute(WorkspaceRoute.SOURCES);
                removeRow(shell, service, "134817");
            });
            await("asc removed", () -> physicalEntries(service).stream()
                    .noneMatch(entry -> entry.sourceFile() != null && entry.sourceFile().contains("134817")), 90);
            assertTrue(physicalEntries(service).stream()
                    .anyMatch(entry -> "DCDC_OUT_VOLTAGE".equals(entry.signalName())
                            && entry.sourceFile() != null && entry.sourceFile().contains("13-38-16")));
            addMeasurement(shell, ASC);
            awaitInventory(service, "134817");
            associate(service, "134817", "2.dbc");
            int runs = service.persistedProcessingRuns().size();
            boolean started = startProcessing(shell);
            assertTrue(started, "reprocess Start Processing disabled");
            await("asc restored", () -> {
                ProjectProcessingStatus status = service.workspace().processingStatus();
                boolean present = physicalEntries(service).stream()
                        .anyMatch(entry -> "DCDC_OUT_VOLTAGE".equals(entry.signalName())
                                && entry.sourceFile() != null && entry.sourceFile().contains("134817")
                                && entry.sampleCount() == before);
                return present && status != ProjectProcessingStatus.RUNNING && service.persistedProcessingRuns().size() > runs;
            }, 300);
            long after = entry(service, "134817", "DCDC_OUT_VOLTAGE").sampleCount();
            long blfAfter = entry(service, "13-38-16", "DCDC_OUT_VOLTAGE").sampleCount();
            note("lifecycle after asc=" + after + " blf=" + blfAfter + " runs=" + service.persistedProcessingRuns().size());
            assertEquals(before, after);
            assertEquals(blf, blfAfter);
            assertEquals(1, physicalEntries(service).stream()
                    .filter(entry -> "DCDC_OUT_VOLTAGE".equals(entry.signalName())
                            && entry.sourceFile() != null && entry.sourceFile().contains("134817")).count());
            save(shell, service);
        } finally {
            close(shell);
            service.shutdown();
        }
    }

    private static void removeRow(ShellFx shell, DesktopAppService service, String namePart) {
        WorkspaceFile file = measurement(service, namePart);
        var list = shell.controller.projectFilesControllerForTest().measurementSourceListForTest();
        list.setMinHeight(480);
        list.setPrefHeight(480);
        list.scrollTo(file);
        list.getSelectionModel().select(file);
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
        // Virtualized cells are not always realized after reopen. This is the same
        // project-scoped removal the Remove from Project button invokes.
        service.removeFile(file.projectSourceId().value());
        shell.controller.projectFilesControllerForTest().refresh();
    }

    private static String text(Node node) {
        StringBuilder text = new StringBuilder();
        if (node instanceof javafx.scene.control.Labeled labeled && labeled.getText() != null) {
            text.append(labeled.getText());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                text.append(' ').append(text(child));
            }
        }
        return text.toString();
    }

    private static ProjectProcessingStatus awaitDirect(DesktopAppService service, int runsBefore, int minutes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(minutes);
        int stable = 0;
        int last = -1;
        while (System.nanoTime() < deadline) {
            List<SignalCatalogEntry> physical = physicalEntries(service);
            boolean dat = physical.stream().anyMatch(entry -> "IN_Vehicle_speed".equals(entry.signalName())
                    && entry.sourceFile() != null && entry.sourceFile().contains("12-03-25")
                    && entry.sampleCount() == 9638);
            boolean log = physical.stream().anyMatch(entry -> "IN_Vehicle_speed".equals(entry.signalName())
                    && entry.sourceFile() != null && entry.sourceFile().contains("13-21-33")
                    && entry.sampleCount() == 9638);
            ProjectProcessingStatus status = service.workspace().processingStatus();
            int size = physical.size();
            if (dat && log && service.persistedProcessingRuns().size() > runsBefore
                    && status != ProjectProcessingStatus.RUNNING) {
                note("directPhysical=" + size + " sources=" + physical.stream()
                        .map(SignalCatalogEntry::sourceFile).distinct().toList());
                return status;
            }
            if (status == ProjectProcessingStatus.COMPLETED && size == last && size > 0
                    && service.persistedProcessingRuns().size() > runsBefore) {
                stable++;
                if (stable >= 4) {
                    note("directPhysical=" + size + " dat=" + dat + " log=" + log + " sources="
                            + physical.stream().map(entry -> entry.sourceFile() + ":" + entry.signalName()
                            + ":" + entry.sampleCount()).limit(8).toList());
                    return status;
                }
            } else {
                stable = 0;
                last = size;
            }
            Thread.sleep(400);
        }
        note("directWait status=" + service.workspace().processingStatus()
                + " runs=" + service.persistedProcessingRuns().size()
                + " physical=" + physicalEntries(service).size());
        return service.workspace().processingStatus();
    }

    private static List<SignalCatalogEntry> physicalEntries(DesktopAppService service) {
        if (service.signalsModel().catalogStore() instanceof ProjectMergedSignalStore merged) {
            return merged.physicalCatalogAll();
        }
        return service.signalsModel().catalog().entries();
    }

    private static ProjectProcessingStatus awaitTerminal(DesktopAppService service, int minutes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(minutes);
        while (System.nanoTime() < deadline) {
            ProjectProcessingStatus status = service.workspace().processingStatus();
            if (status != null && status != ProjectProcessingStatus.RUNNING && status != ProjectProcessingStatus.IDLE
                    && service.projectJourneyState() != null && service.projectJourneyState().analysisAvailable()
                    && !service.signalsModel().catalog().entries().isEmpty()) {
                return status;
            }
            Thread.sleep(400);
        }
        return service.workspace().processingStatus();
    }

    private static ProjectProcessingStatus awaitSignal(DesktopAppService service, String source, String name, int minutes)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(minutes);
        while (System.nanoTime() < deadline) {
            boolean present = physicalEntries(service).stream()
                    .anyMatch(entry -> name.equals(entry.signalName())
                            && entry.sourceFile() != null && entry.sourceFile().contains(source)
                            && entry.sampleCount() > 0);
            ProjectProcessingStatus status = service.workspace().processingStatus();
            if (present && status != ProjectProcessingStatus.RUNNING) {
                Thread.sleep(800);
                if (service.workspace().processingStatus() != ProjectProcessingStatus.RUNNING) {
                    return service.workspace().processingStatus();
                }
            }
            Thread.sleep(500);
        }
        return service.workspace().processingStatus();
    }

    private static void awaitInventory(DesktopAppService service, String namePart) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(6);
        while (System.nanoTime() < deadline) {
            WorkspaceFile file = service.workspace().measurementFiles().stream()
                    .filter(item -> item.fileName().contains(namePart))
                    .findFirst()
                    .orElse(null);
            if (file != null && service.inventoryJobState(file.projectSourceId()) == ProjectInventoryJobState.READY) {
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("inventory not ready " + namePart);
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
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

    private static SignalCatalogEntry entry(DesktopAppService service, String sourcePart, String name) {
        List<SignalCatalogEntry> matches = physicalEntries(service).stream()
                .filter(item -> name.equals(item.signalName()))
                .filter(item -> item.sourceFile() != null && item.sourceFile().contains(sourcePart))
                .toList();
        assertEquals(1, matches.size(), name + " in " + sourcePart + " matches=" + matches.size());
        return matches.getFirst();
    }

    private static com.stellar.platform.processing.graph.GraphSeriesQuery query(DesktopAppService service) {
        assertTrue(service.signalsModel().catalogStore() instanceof ProjectMergedSignalStore);
        return ((GraphQueryProvider) service.signalsModel().catalogStore()).graphQuery();
    }

    private static WorkspaceFile measurement(DesktopAppService service, String part) {
        return service.workspace().measurementFiles().stream()
                .filter(file -> file.fileName().contains(part))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + part));
    }

    private static void assertNotEqualsRunning(DesktopAppService service) {
        assertTrue(service.workspace().processingStatus() != ProjectProcessingStatus.RUNNING, "still running");
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
        Thread.sleep(200);
    }

    private static void addDefinition(ShellFx shell, Path file) throws Exception {
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.SOURCES);
            shell.root.applyCss();
            shell.root.layout();
            shell.controller.projectFilesControllerForTest().setOpenDialogForTest(chooser -> List.of(file.toFile()));
            Button add = button(shell.root, "+ Add DBC");
            assertNotNull(add, "Add DBC");
            add.fire();
        });
        Thread.sleep(200);
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
        await("project", () -> {
            try {
                return name.equals(shell.service.workspace().projectName());
            } catch (RuntimeException ex) {
                return false;
            }
        }, 20);
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

    private static boolean htmlReady(Path reports) {
        try (var files = Files.list(reports)) {
            return files.anyMatch(path -> {
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
        assertTrue(booted.await(180, TimeUnit.SECONDS), "shell did not open");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return new ShellFx(stage.get(), controller.get(), root.get(), service);
    }

    private static Button button(Parent root, String label) {
        Button hidden = null;
        java.util.ArrayList<Node> nodes = new java.util.ArrayList<>();
        nodes.addAll(root.lookupAll(".button"));
        nodes.addAll(root.lookupAll(".toggle-button"));
        for (Node node : nodes) {
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
            Thread.sleep(150);
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
        assertTrue(done.await(300, TimeUnit.SECONDS), "FX work timed out");
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

    private record Sample(String name, long timestampNanos, double physical, Long raw, String unit, long expectedCount) {
    }

    private record ShellFx(Stage stage, MainShellController controller, Parent root, DesktopAppService service) {
    }
}
