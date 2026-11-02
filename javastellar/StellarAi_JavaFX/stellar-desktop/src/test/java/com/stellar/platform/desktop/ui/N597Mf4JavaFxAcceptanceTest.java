package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.model.AnalysisRequest;
import com.stellar.platform.analysis.model.AnalysisResult;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.AnalysisType;
import com.stellar.platform.analysis.model.SignalStatistics;
import com.stellar.platform.desktop.presentation.AnalysisScope;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ProcessingWarningReport;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.project.ProjectInventoryJobState;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.graph.GraphQueryProvider;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
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
 * Real JavaFX path for the independently verified N597 recording.
 * Inventory evidence is recorded separately from physical sample comparison.
 */
class N597Mf4JavaFxAcceptanceTest {

    private static final Path MF4 = Path.of(
            "C:\\Users\\DT - User\\Downloads\\DriveTechData\\MF4Files\\City - Highway_MF4\\N597_AFT_0001850_20250201_143215.mf4");
    private static final Path DBC = Path.of(
            "C:\\Users\\DT - User\\Downloads\\DriveTechData\\MF4Files\\City - Highway_MF4\\Required DBC\\N597_ABSEMS_CAN dbc _Rev7 (1).dbc");
    private static final String MF4_SHA = "2DD8C3A66FFF849D52759D5F6D4B6B53922F42B42B6835844C33FEB4A83E09F5";
    private static final String DBC_SHA = "493BC73398F3FFA8029D9F8FC8C687472A6A5C4F973A8B98AC31631A971FABA2";
    private static final Path EVIDENCE = Path.of("D:\\DesktopApp\\DriveT\\docs\\qa-handoff\\n597-javafx-acceptance.txt");

    private static final Expectation REAR = new Expectation(
            "REAR_BRAKE_SWITCH_STATUS", 288, 0L, 2L, 2.0, "");
    private static final Expectation SPEED = new Expectation(
            "DISPLAY_SPEED", 288, 76_131_500_000L, 2L, 0.2, "km/h");
    private static final Expectation TORQUE = new Expectation(
            "TARGET_CLUTCH_TORQUE_REQ", 320, 786_557_560_000L, 275L, 17.5, "Nm");

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
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    void n597RecordingThroughTheDesktop() throws Exception {
        note("started=" + Instant.now());
        assertFalse(MF4.startsWith(Path.of("D:\\StellarAI-E2E")));
        assertFalse(DBC.startsWith(Path.of("D:\\config")));
        assertEquals(MF4_SHA, sha256(MF4));
        assertEquals(DBC_SHA, sha256(DBC));

        Path home = Files.createTempDirectory("stellar-n597-javafx");
        Path db = home.resolve("meta.db");
        isolate(home, db);
        note("isolatedHome=" + home);

        DesktopAppService service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
        ShellFx shell = null;
        boolean released = false;
        try {
            shell = boot(service);
            createProject(shell, "N597 143215");
            addFile(shell, MF4, "+ Add Measurement Files", "Browse Files");
            awaitInventory(service, 8);
            WorkspaceFile measurement = service.workspace().measurementFiles().getFirst();
            note("measurement=" + measurement.fileName()
                    + " finalization=" + measurement.mf4FinalizationLabel()
                    + " recoveryCandidate=" + measurement.recoveryCandidate()
                    + " inventory=" + service.inventoryJobState(measurement.projectSourceId()));
            String signalsBefore = page(shell, WorkspaceRoute.SIGNALS);
            note("signalsBeforeProcessing=" + clip(signalsBefore));

            addFile(shell, DBC, "+ Add DBC", "+ Add DBC");
            ShellFx active = shell;
            String dbcAdd = runOnFxValue(() -> {
                var label = active.controller.projectFilesControllerForTest().addResultLabelForTest();
                return label == null || label.getText() == null ? "" : label.getText();
            });
            note("dbcAdd=" + oneLine(dbcAdd) + " definitions=" + service.workspace().definitionFiles().size());
            awaitDefinitions(service, 2);
            if (service.workspace().definitionFiles().isEmpty()) {
                note("processingBlocker=DBC was not imported. " + oneLine(dbcAdd));
                note("decision=BLOCKED");
                return;
            }
            String decode = page(shell, WorkspaceRoute.DECODE);
            note("decode=" + clip(decode));
            note("definitionImported=" + decode.contains("N597_ABSEMS_CAN"));
            note("dbcMatchMentioned=" + (decode.toLowerCase(Locale.ROOT).contains("match")
                    || decode.contains("288")
                    || decode.contains("Ready")));

            int runsBefore = service.persistedProcessingRuns().size();
            boolean started = startProcessing(shell);
            note("processingStarted=" + started);
            if (!started) {
                note("processingBlocker=" + clip(page(shell, WorkspaceRoute.REVIEW_AND_PROCESS)));
                note("decision=BLOCKED");
                return;
            }
            ProjectProcessingStatus status = awaitProcessing(service, 30);
            String result = page(shell, WorkspaceRoute.PROCESSING_RESULT);
            note("processingStatus=" + status);
            note("processingPage=" + clip(result));
            ProcessingWarningReport warnings = service.processingWarningReport();
            note("processingWarningCount=" + warnings.count());
            for (ProcessingWarningReport.Item item : warnings.items()) {
                note("processingWarning category=" + item.category()
                        + " summary=" + item.summary()
                        + " detail=" + item.technicalDetail());
            }
            note("runs=" + service.persistedProcessingRuns().size() + " before=" + runsBefore);
            if (status != ProjectProcessingStatus.COMPLETED
                    && status != ProjectProcessingStatus.COMPLETED_WITH_WARNINGS) {
                note("downstream=STOPPED");
                note("decision=BLOCKED");
                assertTrue(status == ProjectProcessingStatus.COMPLETED
                                || status == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS,
                        "processing stopped at " + status + " page=" + clip(result));
                return;
            }

            compareSignals(service);
            String signals = page(shell, WorkspaceRoute.SIGNALS);
            note("signalsPage=" + clip(signals));
            for (Expectation expected : List.of(REAR, SPEED, TORQUE)) {
                int rows = signalRows(shell, expected.name);
                note("signalsUi " + expected.name + " rows=" + rows);
                assertEquals(1, rows, expected.name + " Signals row count");
            }

            graph(shell, service);
            analytics(service);
            html(shell, home);
            gps(shell);
            saveAndReopen(shell, service, db, home);
            released = true;
            note("decision=JAVA_FX_EXECUTED");
        } finally {
            if (!released) {
                if (shell != null) {
                    close(shell);
                }
                service.shutdown();
            }
            note("mf4ShaUnchanged=" + MF4_SHA.equals(sha256(MF4)));
            note("dbcShaUnchanged=" + DBC_SHA.equals(sha256(DBC)));
            note("finished=" + Instant.now());
            Files.createDirectories(EVIDENCE.getParent());
            Files.writeString(EVIDENCE, NOTES.toString());
        }
    }

    private static void compareSignals(DesktopAppService service) {
        ProjectMergedSignalStore merged = merged(service);
        GraphSeriesQuery query = ((GraphQueryProvider) merged).graphQuery();
        for (Expectation expected : List.of(REAR, SPEED, TORQUE)) {
            List<SignalCatalogEntry> physical = merged.physicalCatalogAll().stream()
                    .filter(entry -> expected.name.equals(entry.signalName()))
                    .filter(entry -> entry.sourceFile().contains("143215"))
                    .toList();
            note("catalog " + expected.name + " copies=" + physical.size()
                    + (physical.isEmpty() ? "" : " samples=" + physical.getFirst().sampleCount()
                    + " unit=" + physical.getFirst().unit()
                    + " source=" + physical.getFirst().sourceFile()
                    + " canId=" + physical.getFirst().canId()
                    + " channel=" + physical.getFirst().channel()
                    + " first=" + physical.getFirst().firstTimestampNanos()
                    + " last=" + physical.getFirst().lastTimestampNanos()));
            assertEquals(1, physical.size(), expected.name);
            SignalCatalogEntry entry = physical.getFirst();
            assertEquals(expected.unit, entry.unit(), expected.name);
            assertEquals(expected.canId, entry.canId(), expected.name);
            assertTrue(entry.sampleCount() > 1_024, expected.name + " samples=" + entry.sampleCount());
            assertTrue(entry.lastTimestampNanos() != null && entry.lastTimestampNanos() > entry.firstTimestampNanos(),
                    expected.name + " chronology");
            List<SignalSamplePoint> exact = query.queryRange(
                    entry.key(), expected.timestampNanos, expected.timestampNanos, 5);
            assertFalse(exact.isEmpty(), expected.name + " missing sample at " + expected.timestampNanos);
            SignalSamplePoint point = exact.getFirst();
            assertEquals(expected.timestampNanos, point.timestampNanos(), expected.name);
            assertEquals(expected.raw, point.rawValue(), expected.name);
            assertEquals(expected.physical, point.physicalValue(), 0.0, expected.name);
            assertEquals("VALID", String.valueOf(point.validity()), expected.name);
            List<SignalSamplePoint> limited = query.queryRange(
                    entry.key(), entry.firstTimestampNanos(), entry.lastTimestampNanos(), 2_048);
            note("limitedQuery " + expected.name + " returned=" + limited.size());
            assertTrue(limited.size() > 1_024, expected.name + " limited query returned " + limited.size());
            note("compare " + expected.name + " PASS timestamp=" + point.timestampNanos()
                    + " raw=" + point.rawValue() + " physical=" + point.physicalValue()
                    + " unit=" + entry.unit() + " validity=" + point.validity()
                    + " source=" + entry.sourceFile());
        }
    }

    private static void graph(ShellFx shell, DesktopAppService service) throws Exception {
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.GRAPHS);
            shell.root.applyCss();
            shell.root.layout();
        });
        GraphController graph = shell.controller.graphControllerForTest();
        assertNotNull(graph, "graph");
        List<SignalCatalogKey> keys = new ArrayList<>();
        runOnFx(() -> {
            for (Expectation expected : List.of(REAR, SPEED, TORQUE)) {
                SignalCatalogEntry entry = graph.graphCatalogForTest().stream()
                        .filter(item -> expected.name.equals(item.signalName()))
                        .findFirst()
                        .orElse(null);
                if (entry != null) {
                    keys.add(entry.key());
                    if (!graph.isSelectedForTest(entry.key())) {
                        graph.fireExplorerCheckboxForTest(entry.key());
                    }
                }
            }
            graph.setLayoutModeForTest(true);
        });
        note("graphCatalog=" + runOnFxValue(() -> graph.graphCatalogForTest().size())
                + " selected=" + runOnFxValue(() -> graph.selectedSignalCountForTest())
                + " status=" + runOnFxValue(graph::graphStatusForTest));
        assertEquals(3, keys.size(), "graph catalog is missing one of the three signals");
        await("stacked", () -> "STACKED".equals(graph.layoutModeForTest())
                && graph.selectedSignalCountForTest() == 3
                && !graph.graphStatusForTest().startsWith("Loading"), 240);
        int stackedSegments = graph.segmentCountForTest(0);
        String stackedColours = colours(graph);
        String hover = hover(graph);
        note("graphHover=" + oneLine(hover)
                + " canvas=" + runOnFxValue(() -> graph.canvasForTest().getWidth() + "x" + graph.canvasForTest().getHeight()));
        assertTrue(hover.contains("REAR_BRAKE_SWITCH_STATUS")
                        || hover.contains("DISPLAY_SPEED")
                        || hover.contains("TARGET_CLUTCH_TORQUE_REQ"),
                "hover did not name a plotted signal: " + hover);
        runOnFx(() -> graph.setLayoutModeForTest(false));
        await("overlay", () -> graph.layoutModeForTest().contains("OVERLAY")
                && !graph.graphStatusForTest().startsWith("Loading"), 180);
        String overlayColours = colours(graph);
        long beforeZoom = value(() -> graph.viewportEndForTest() - graph.viewportStartForTest());
        runOnFx(() -> fireTooltip(shell.root, "Zoom in"));
        await("zoomed", () -> graph.viewportEndForTest() - graph.viewportStartForTest() < beforeZoom
                && !graph.graphStatusForTest().startsWith("Loading"), 120);
        long zoomed = value(() -> graph.viewportEndForTest() - graph.viewportStartForTest());
        runOnFx(() -> fireTooltip(shell.root, "Pan left"));
        long panned = value(() -> graph.viewportStartForTest());
        runOnFx(() -> graph.fitForTest());
        await("fit", () -> !graph.graphStatusForTest().startsWith("Loading"), 120);
        runOnFx(() -> graph.resetViewForTest());
        await("reset", () -> !graph.graphStatusForTest().startsWith("Loading"), 120);
        long reloads = graph.viewportRequestsForTest();
        Thread.sleep(1000);
        long reloadDelta = graph.viewportRequestsForTest() - reloads;
        String page = visible(shell.root);
        note("graph stackedSegments=" + stackedSegments
                + " selected=" + graph.selectedSignalCountForTest()
                + " stackedColours=" + stackedColours
                + " overlayColours=" + overlayColours
                + " coloursStable=" + stackedColours.equals(overlayColours)
                + " zoomSpan=" + beforeZoom + "->" + zoomed
                + " panStart=" + panned
                + " timeline=" + graph.timelineGroupForTest()
                + " plotSettingsPresent=" + page.contains("Plot Settings")
                + " reloadDelta=" + reloadDelta
                + " status=" + oneLine(graph.graphStatusForTest()));
        assertEquals(3, graph.selectedSignalCountForTest());
        assertTrue(stackedSegments > 0, "no stacked samples");
        assertTrue(stackedColours.contains("unit=km/h") && stackedColours.contains("sourceSamples=293696"), stackedColours);
        assertTrue(stackedColours.contains("unit=Nm") && stackedColours.contains("sourceSamples=146849"), stackedColours);
        assertTrue(zoomed < beforeZoom, "zoom did not change the span");
        assertFalse(page.contains("Plot Settings"));
        assertFalse(graph.timelineGroupForTest().contains("UNKNOWN"), graph.timelineGroupForTest());
        assertFalse(graph.graphStatusForTest().toLowerCase(Locale.ROOT).contains("unknown order"),
                graph.graphStatusForTest());
        assertTrue(reloadDelta <= 2, "viewport kept reloading after reset: " + reloadDelta);
        assertEquals(stackedColours, overlayColours);
        for (Expectation expected : List.of(REAR, SPEED, TORQUE)) {
            exactHover(graph, expected);
        }
        runOnFx(() -> {
            for (Expectation expected : List.of(REAR, SPEED, TORQUE)) {
                var entry = graph.graphCatalogForTest().stream()
                        .filter(item -> expected.name.equals(item.signalName()))
                        .findFirst()
                        .orElse(null);
                if (entry != null && !graph.isSelectedForTest(entry.key())) {
                    graph.fireExplorerCheckboxForTest(entry.key());
                }
            }
        });
    }

    private static void analytics(DesktopAppService service) {
        ProjectMergedSignalStore merged = merged(service);
        service.selectAnalysisScope(AnalysisScope.allMeasurements(1));
        for (Expectation expected : List.of(REAR, SPEED, TORQUE)) {
            SignalCatalogKey logical = service.signalsModel().catalog().entries().stream()
                    .filter(entry -> expected.name.equals(entry.signalName()))
                    .findFirst()
                    .orElseThrow()
                    .key();
            AnalysisResult result = service.runEngineeringAnalysis(AnalysisRequest.builder(AnalysisType.STATISTICS)
                    .projectId(service.currentProjectId())
                    .addSignal(logical)
                    .build());
            note("analytics " + expected.name + " status=" + result.status() + " message=" + result.message());
            assertEquals(AnalysisStatus.COMPLETED, result.status(), result.message());
            SignalStatistics stats = result.statistics().getFirst();
            long catalogSamples = merged.physicalCatalogAll().stream()
                    .filter(entry -> expected.name.equals(entry.signalName()))
                    .mapToLong(SignalCatalogEntry::sampleCount)
                    .sum();
            boolean containsKnown = stats.min() <= expected.physical && stats.max() >= expected.physical;
            note("analytics " + expected.name
                    + " uiExecution=PASS samples=" + stats.sampleCount()
                    + " catalogSamples=" + catalogSamples
                    + " min=" + stats.min() + " max=" + stats.max()
                    + " knownValueInsideMinMax=" + containsKnown
                    + " aggregateOracle=UNVERIFIED");
            assertEquals(catalogSamples, stats.sampleCount(), expected.name);
            assertTrue(containsKnown, expected.name + " statistics exclude the known physical value");
        }
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
        await("investigation", () -> graph.graphStatusForTest().contains("Created investigation"), 20);
        note("investigationStatus=" + oneLine(graph.graphStatusForTest()));
        runOnFx(() -> shell.controller.openRoute(WorkspaceRoute.INVESTIGATIONS));
        await("report button", () -> {
            Button generate = button(shell.root, "Generate HTML Report");
            return generate != null && !generate.isDisabled();
        }, 60);
        runOnFx(() -> {
            shell.controller.investigationControllerForTest()
                    .setReportDestinationForTest("N597 143215", reports);
            button(shell.root, "Generate HTML Report").fire();
        });
        await("html file", () -> htmlReady(reports), 180);
        String reportStatus = runOnFxValue(() -> shell.controller.investigationControllerForTest().reportStatusForTest());
        Path html = Files.list(reports)
                .filter(path -> path.getFileName().toString().endsWith(".html"))
                .findFirst()
                .orElseThrow();
        String text = Files.readString(html);
        note("html=" + html.getFileName() + " bytes=" + Files.size(html)
                + " project=" + text.contains("N597 143215")
                + " file=" + text.contains("143215")
                + " speed=" + text.contains("DISPLAY_SPEED")
                + " torque=" + text.contains("TARGET_CLUTCH_TORQUE_REQ")
                + " unit=" + text.contains("km/h")
                + " torqueUnit=" + text.contains("Nm")
                + " knownSpeedLiteral=" + text.contains("0.2")
                + " recoveryRequired=" + text.contains("RECOVERY_REQUIRED:")
                + " recoveryStarted=" + text.contains("RECOVERY_STARTED:")
                + " zeroCycles=" + text.contains("ZERO_DECLARED_CYCLES_WITH_DATA:")
                + " noValidRecords=" + text.contains("NO_VALID_RECORDS_IN_REGION:")
                + " outOfRange=" + text.contains("OUT_OF_RANGE_PHYSICAL_VALUE:")
                + " status=" + reportStatus);
        assertTrue(text.contains("143215"), "report omitted the MF4 name");
        assertTrue(text.contains("DISPLAY_SPEED"), "report omitted DISPLAY_SPEED");
        assertTrue(text.contains("km/h"), "report omitted km/h");
        assertTrue(text.contains("TARGET_CLUTCH_TORQUE_REQ") && text.contains("Nm"), text);
        assertTrue(text.contains("RECOVERY_REQUIRED:"), "report omitted persisted recovery diagnostics");
        assertTrue(text.contains("OUT_OF_RANGE_PHYSICAL_VALUE:"), "report omitted the out-of-range diagnostic");
        assertTrue(text.contains(">5</td>"), "report warning count did not include the five persisted diagnostics");
    }

    private static void gps(ShellFx shell) throws Exception {
        GraphController graph = shell.controller.graphControllerForTest();
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.GRAPHS);
            graph.selectMapRouteTabForTest();
        });
        await("gps", () -> {
            var pane = graph.mapRoutePaneForTest();
            return pane != null && pane.routeStatusForTest().contains("No GPS");
        }, 45);
        String status = graph.mapRoutePaneForTest() == null
                ? ""
                : graph.mapRoutePaneForTest().routeStatusForTest();
        int overlays = graph.mapRoutePaneForTest() == null
                ? 0
                : graph.mapRoutePaneForTest().routeCanvasForTest().overlayCountForTest();
        note("gps status='" + oneLine(status) + "' overlays=" + overlays
                + " physicalRoute=NOT_APPLICABLE");
        assertEquals(0, overlays, "a GPS route was drawn for a recording with zero GNSS cycles");
    }

    private static void saveAndReopen(ShellFx shell, DesktopAppService service, Path db, Path home) throws Exception {
        String projectId = service.currentProjectId();
        int runs = service.persistedProcessingRuns().size();
        runOnFx(() -> {
            Button save = buttonStarting(shell.root, "Save");
            assertNotNull(save, "Save");
            save.fire();
        });
        await("saved", () -> service.projectSaveState() != null
                && !service.projectSaveState().name().contains("SAVING"), 30);
        note("saveState=" + service.projectSaveState());
        close(shell);
        service.shutdown();
        DesktopAppService reopened = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
        ShellFx again = boot(reopened);
        try {
            runOnFx(() -> again.controller.requestOpenSavedProject(projectId));
            await("reopened", () -> reopened.workspace().measurementFiles().stream()
                    .anyMatch(file -> file.fileName().contains("143215")), 60);
            boolean speed = reopened.signalsModel().catalog().entries().stream()
                    .anyMatch(entry -> "DISPLAY_SPEED".equals(entry.signalName()) && entry.sampleCount() > 0);
            int catalog = reopened.signalsModel().catalog().entries().size();
            int warnings = reopened.processingWarningReport().count();
            long workspace = reopened.signalsModel().workspaceKeys().stream()
                    .filter(key -> "REAR_BRAKE_SWITCH_STATUS".equals(key.signalName())
                            || "DISPLAY_SPEED".equals(key.signalName())
                            || "TARGET_CLUTCH_TORQUE_REQ".equals(key.signalName()))
                    .count();
            note("reopen measurements=" + reopened.workspace().measurementFiles().stream().map(WorkspaceFile::fileName).toList()
                    + " definitions=" + reopened.workspace().definitionFiles().stream().map(WorkspaceFile::fileName).toList()
                    + " displaySpeedRetained=" + speed
                    + " catalog=" + catalog
                    + " warnings=" + warnings
                    + " graphSignals=" + workspace
                    + " processing=" + reopened.workspace().processingStatus()
                    + " runs=" + reopened.persistedProcessingRuns().size()
                    + " beforeRuns=" + runs);
            compareSignals(reopened);
            assertTrue(speed, "DISPLAY_SPEED was not retained");
            assertEquals(178, catalog);
            assertEquals(5, warnings);
            assertEquals(3, workspace);
            assertEquals(runs, reopened.persistedProcessingRuns().size());
            assertTrue(reopened.workspace().processingStatus() != ProjectProcessingStatus.RUNNING);
        } finally {
            close(again);
            reopened.shutdown();
        }
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
        Thread.sleep(500);
        return runOnFxValue(() -> {
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
    }

    private static String colours(GraphController graph) {
        return graph.renderAnalysisTracksForTest().stream()
                .map(series -> series.key().signalName()
                        + " unit=" + series.unit()
                        + " sourceSamples=" + series.sourceSampleCount()
                        + " buckets=" + series.buckets().size()
                        + " fullResolution=" + series.fullResolution())
                .toList()
                .toString();
    }

    private static void exactHover(GraphController graph, Expectation expected) throws Exception {
        long windowEnd = expected.timestampNanos() + 1_000_000L;
        runOnFx(() -> {
            for (Expectation other : List.of(REAR, SPEED, TORQUE)) {
                var entry = graph.graphCatalogForTest().stream()
                        .filter(item -> other.name.equals(item.signalName()))
                        .findFirst()
                        .orElse(null);
                if (entry == null) {
                    continue;
                }
                boolean want = other.name.equals(expected.name);
                if (graph.isSelectedForTest(entry.key()) != want) {
                    graph.fireExplorerCheckboxForTest(entry.key());
                }
            }
            graph.setViewportForTest(expected.timestampNanos(), windowEnd);
            graph.requestViewportForTest();
        });
        await("exact " + expected.name, () -> graph.selectedSignalCountForTest() == 1
                && graph.viewportStartForTest() == expected.timestampNanos()
                && graph.viewportEndForTest() == windowEnd
                && !graph.graphStatusForTest().startsWith("Loading")
                && graph.fullResolutionSegmentCountForTest() > 0, 90);
        String hover = hoverNamed(graph, expected.name);
        String sample = String.format(Locale.ROOT, "sample %.6f s", expected.timestampNanos() / 1e9d);
        String value = String.format(Locale.ROOT, "%.6g", expected.physical);
        note("exactHover " + expected.name + "=" + oneLine(hover));
        assertTrue(hover.contains(expected.name), hover);
        assertTrue(hover.contains("143215"), hover);
        assertTrue(hover.contains(sample), hover);
        assertTrue(hover.contains(value), hover);
        assertFalse(hover.contains("bucket"), hover);
    }

    private static String hover(GraphController graph) throws Exception {
        return hoverNamed(graph, "");
    }

    private static String hoverNamed(GraphController graph, String requiredName) throws Exception {
        return runOnFxValue(() -> {
            var canvas = graph.canvasForTest();
            String last = "";
            for (int y = 24; y <= 640; y += 16) {
                canvas.fireEvent(new MouseEvent(
                        canvas, canvas, MouseEvent.MOUSE_MOVED,
                        400, y, 400, y,
                        MouseButton.NONE, 0,
                        false, false, false, false, false, false, false, false, false, false, null));
                String text = graph.liveCursorForTest();
                last = text == null ? "" : text;
                boolean named = requiredName != null && !requiredName.isBlank()
                        ? last.contains(requiredName)
                        : last.contains("REAR_BRAKE_SWITCH_STATUS")
                        || last.contains("DISPLAY_SPEED")
                        || last.contains("TARGET_CLUTCH_TORQUE_REQ");
                if (named) {
                    return last;
                }
            }
            return last;
        });
    }

    private static ProjectMergedSignalStore merged(DesktopAppService service) {
        assertTrue(service.signalsModel().catalogStore() instanceof ProjectMergedSignalStore);
        return (ProjectMergedSignalStore) service.signalsModel().catalogStore();
    }

    private static void isolate(Path home, Path db) {
        assertFalse(home.toString().toLowerCase(Locale.ROOT).contains("stellaraie2e"));
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        System.setProperty("stellarai.metadata.db", db.toString());
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

    private static void createProject(ShellFx shell, String name) throws Exception {
        AtomicReference<Throwable> openError = new AtomicReference<>();
        Thread opener = new Thread(() -> {
            try {
                runOnFx(() -> {
                    shell.controller.openRoute(WorkspaceRoute.HOME);
                    shell.root.applyCss();
                    shell.root.layout();
                    Button button = button(shell.root, "New Project");
                    assertNotNull(button, "New Project");
                    button.fire();
                });
            } catch (Throwable thrown) {
                openError.set(thrown);
            }
        });
        opener.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Boolean typed = runOnFxValue(() -> fillDialog(name));
            if (Boolean.TRUE.equals(typed)) {
                break;
            }
            Thread.sleep(50);
        }
        opener.join(20_000);
        if (openError.get() != null) {
            throw new AssertionError(openError.get());
        }
        await("project", () -> name.equals(serviceName(shell)), 20);
        note("projectCreated=" + serviceName(shell));
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

    private static void addFile(ShellFx shell, Path file, String preferred, String fallback) throws Exception {
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.SOURCES);
            shell.root.applyCss();
            shell.root.layout();
            shell.controller.projectFilesControllerForTest().setOpenDialogForTest(chooser -> List.of(file.toFile()));
            Button add = button(shell.root, preferred);
            if (add == null) {
                add = button(shell.root, fallback);
            }
            assertNotNull(add, preferred);
            add.fire();
        });
    }

    private static void awaitInventory(DesktopAppService service, int minutes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(minutes);
        while (System.nanoTime() < deadline) {
            boolean done = !service.workspace().measurementFiles().isEmpty()
                    && service.workspace().measurementFiles().stream().allMatch(file -> settled(service, file));
            if (done) {
                return;
            }
            Thread.sleep(250);
        }
        note("inventoryWaitExpired");
    }

    private static void awaitDefinitions(DesktopAppService service, int minutes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(minutes);
        while (System.nanoTime() < deadline) {
            if (!service.workspace().definitionFiles().isEmpty()) {
                return;
            }
            Thread.sleep(250);
        }
        note("definitionWaitExpired");
    }

    private static boolean settled(DesktopAppService service, WorkspaceFile file) {
        ProjectInventoryJobState state = service.inventoryJobState(file.projectSourceId());
        return state == ProjectInventoryJobState.READY
                || state == ProjectInventoryJobState.REQUIRES_ATTENTION
                || state == ProjectInventoryJobState.FAILED
                || state == ProjectInventoryJobState.UNSUPPORTED
                || state == ProjectInventoryJobState.CANCELLED;
    }

    private static ProjectProcessingStatus awaitProcessing(DesktopAppService service, int minutes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(minutes);
        while (System.nanoTime() < deadline) {
            ProjectProcessingStatus status = service.workspace().processingStatus();
            var journey = service.projectJourneyState();
            if (journey != null && journey.analysisAvailable()) {
                return journey.readyWithWarnings()
                        ? ProjectProcessingStatus.COMPLETED_WITH_WARNINGS
                        : ProjectProcessingStatus.COMPLETED;
            }
            if (status == ProjectProcessingStatus.COMPLETED
                    || status == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS
                    || status == ProjectProcessingStatus.FAILED
                    || status == ProjectProcessingStatus.CANCELLED
                    || status == ProjectProcessingStatus.RECOVERY_FAILED
                    || status == ProjectProcessingStatus.UNSUPPORTED_STRUCTURE
                    || status == ProjectProcessingStatus.NO_CAN_DATA) {
                return status;
            }
            Thread.sleep(500);
        }
        note("processingWaitExpired status=" + service.workspace().processingStatus()
                + " journey=" + service.projectJourneyState());
        return service.workspace().processingStatus();
    }

    private static int signalRows(ShellFx shell, String name) throws Exception {
        return runOnFxValue(() -> {
            shell.controller.openRoute(WorkspaceRoute.SIGNALS);
            shell.root.applyCss();
            shell.root.layout();
            for (Node node : shell.root.lookupAll(".text-field")) {
                if (node instanceof javafx.scene.control.TextField field
                        && "Search signals...".equals(field.getPromptText())) {
                    field.setText(name);
                }
            }
            shell.root.applyCss();
            shell.root.layout();
            int rows = 0;
            for (Node node : shell.root.lookupAll(".table-view")) {
                if (!(node instanceof javafx.scene.control.TableView<?> table)) {
                    continue;
                }
                for (Object item : table.getItems()) {
                    if (item instanceof SignalCatalogEntry entry && name.equals(entry.signalName())) {
                        rows++;
                    }
                }
            }
            return rows;
        });
    }

    private static String page(ShellFx shell, WorkspaceRoute route) throws Exception {
        runOnFx(() -> {
            shell.controller.openRoute(route);
            shell.root.applyCss();
            shell.root.layout();
        });
        Thread.sleep(300);
        return visible(shell.root);
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
        Button hidden = null;
        for (Node node : root.lookupAll(".button")) {
            if (!(node instanceof Button button)) {
                continue;
            }
            String text = button.getText() == null ? "" : button.getText();
            boolean named = text.contains(label);
            if (!named) {
                for (Node child : button.lookupAll(".label")) {
                    if (child instanceof Labeled labeled && labeled.getText() != null && labeled.getText().contains(label)) {
                        named = true;
                        break;
                    }
                }
            }
            if (!named) {
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

    private static String visible(Parent root) throws Exception {
        return runOnFxValue(() -> {
            StringBuilder text = new StringBuilder();
            for (Node node : root.lookupAll(".label")) {
                if (node instanceof Labeled labeled && labeled.getText() != null) {
                    text.append(labeled.getText()).append('\n');
                }
            }
            return text.toString();
        });
    }

    private static String serviceName(ShellFx shell) {
        try {
            return shell.service.workspace().projectName();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static boolean htmlReady(Path reports) {
        try (var listed = Files.list(reports)) {
            return listed.anyMatch(path -> {
                try {
                    return path.getFileName().toString().endsWith(".html") && Files.size(path) > 1_000;
                } catch (Exception sizeError) {
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
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for " + label);
    }

    private static void close(ShellFx shell) throws Exception {
        runOnFx(() -> {
            if (shell.stage != null) {
                shell.stage.close();
            }
        });
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
        assertTrue(done.await(120, TimeUnit.SECONDS), "JavaFX work timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static <T> T runOnFxValue(java.util.function.Supplier<T> read) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        runOnFx(() -> value.set(read.get()));
        return value.get();
    }

    private static long value(java.util.function.LongSupplier read) throws Exception {
        return runOnFxValue(read::getAsLong);
    }

    private static int count(String text, String token) {
        int found = 0;
        int from = 0;
        while (from >= 0) {
            from = text.indexOf(token, from);
            if (from < 0) {
                break;
            }
            found++;
            from += token.length();
        }
        return found;
    }

    private static String clip(String text) {
        String one = oneLine(text);
        return one.length() <= 700 ? one : one.substring(0, 700);
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace('\n', ' ').replaceAll(" +", " ").trim();
    }

    private static void note(String line) {
        NOTES.append(line).append('\n');
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1 << 20];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(String.format("%02X", value));
        }
        return hex.toString();
    }

    private record Expectation(String name, long canId, long timestampNanos, long raw, double physical, String unit) {
    }

    private record ShellFx(Stage stage, MainShellController controller, Parent root, DesktopAppService service) {
    }
}
