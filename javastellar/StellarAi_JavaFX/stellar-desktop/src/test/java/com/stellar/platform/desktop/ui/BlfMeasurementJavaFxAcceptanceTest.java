package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.model.AnalysisRequest;
import com.stellar.platform.analysis.model.AnalysisResult;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.AnalysisType;
import com.stellar.platform.analysis.model.SignalStatistics;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ProcessingRunStatuses;
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
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real NEDC BLF recordings through the desktop. {@code 2.dbc} covers 61 of 68 CAN IDs.
 * Physical expectations come from python-can plus cantools. GPS is not present in this DBC.
 */
class BlfMeasurementJavaFxAcceptanceTest {

    private static final Path DIR = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\BLF");
    private static final Path DBC = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\dbcfiles\\2.dbc");
    private static final Path EVIDENCE = Path.of("D:\\DesktopApp\\DriveT\\docs\\qa-handoff\\blf-javafx-acceptance.txt");
    private static final String SIGNAL = "DCDC_OUT_VOLTAGE";
    private static final String FOCUS = "13-50-56";
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
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    void realBlfFilesThroughTheDesktop() throws Exception {
        note("started=" + Instant.now());
        note("directory=" + DIR);
        List<Path> files = Files.list(DIR).filter(path -> path.getFileName().toString().toLowerCase().endsWith(".blf"))
                .sorted().toList();
        assertEquals(4, files.size(), "BLF directory contents changed");
        for (Path file : files) {
            note("source name=" + file.getFileName() + " size=" + Files.size(file) + " sha256=" + sha256(file));
        }
        note("dbc name=" + DBC.getFileName() + " size=" + Files.size(DBC) + " sha256=" + sha256(DBC));
        Path home = Files.createTempDirectory("stellar-blf-javafx");
        Path db = home.resolve("meta.db");
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        System.setProperty("stellarai.metadata.db", db.toString());
        note("isolatedHome=" + home);
        DesktopAppService service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
        ShellFx shell = null;
        try {
            shell = boot(service);
            createProject(shell, service, "BLF NEDC");
            for (Path file : files) {
                addFile(shell, file, "+ Add Measurement Files", "Browse Files");
            }
            awaitInventory(service, 15);
            for (WorkspaceFile file : service.workspace().measurementFiles()) {
                var inventory = service.sourceInventoryModel().inventoryFor(file.id()).orElse(null);
                note("import name=" + file.fileName()
                        + " kind=" + file.kind()
                        + " status=" + file.status()
                        + " inventory=" + service.inventoryJobState(file.projectSourceId())
                        + " channels=" + (inventory == null ? -1 : inventory.totalCanChannels())
                        + " frames=" + (inventory == null ? -1 : inventory.totalCanFrames())
                        + " canIds=" + (inventory == null ? -1 : inventory.globalUniqueCanIdentityCount()));
                assertEquals("MEASUREMENT_BLF", file.kind().name(), file.fileName());
                assertEquals(ProjectInventoryJobState.READY, service.inventoryJobState(file.projectSourceId()), file.fileName());
            }
            addFile(shell, DBC, "+ Add DBC", "+ Add DBC");
            await("definition", () -> service.workspace().definitionFiles().size() == 1, 60);
            WorkspaceFile definition = service.workspace().definitionFiles().getFirst();
            note("definition=" + definition.fileName() + " kind=" + definition.kind() + " status=" + definition.status());
            runOnFx(() -> service.selectDefinition(definition.id()));
            int runsBefore = service.persistedProcessingRuns().size();
            boolean started = startProcessing(shell);
            note("processingStarted=" + started);
            assertTrue(started, "Start Processing was not available");
            ProjectProcessingStatus status = awaitProcessing(service, 30, runsBefore);
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
            note("voltageCounts=" + counts(service));
            compare(service, "13-38-16", 12639, 12.5, 1250, 3_736_300L);
            compare(service, "13-42-29", 12638, 12.5, 1250, 17_100_775L);
            compare(service, "13-46-42", 12638, 12.5, 1250, 13_080_375L);
            compare(service, FOCUS, 5163, 12.4, 1240, 2_160_275L);
            graph(shell, service);
            analytics(service);
            html(shell, home);
            removeAndRestore(shell, service);
            saveAndReopen(shell, service, db, home);
            shell = null;
            assertTrue(lifecycleRestorePassed, "re-added BLF was not decoded again");
            note("decision=PASS");
        } finally {
            if (shell != null) {
                close(shell);
            }
            service.shutdown();
            for (Path file : files) {
                note("hashUnchanged " + file.getFileName() + "=" + sha256(file));
            }
            note("dbcHashUnchanged=" + sha256(DBC));
            note("finished=" + Instant.now());
            Files.createDirectories(EVIDENCE.getParent());
            Files.writeString(EVIDENCE, NOTES.toString());
        }
    }

    private static void compare(DesktopAppService service, String filePart, long expectedCount,
                                double physical, long raw, long timestampNanos) {
        SignalCatalogEntry entry = physical(service, filePart);
        AtomicReference<SignalSamplePoint> first = new AtomicReference<>();
        service.signalsModel().catalogStore().forEachSample(entry.key(), sample -> {
            if (first.get() == null) {
                first.set(sample);
            }
        });
        SignalSamplePoint sample = first.get();
        note("sample file=" + filePart
                + " count=" + entry.sampleCount()
                + " unit=" + entry.unit()
                + " canId=" + entry.canId()
                + " source=" + entry.sourceFile()
                + " t0=" + (sample == null ? null : sample.timestampNanos())
                + " raw=" + (sample == null ? null : sample.rawValue())
                + " physical=" + (sample == null ? null : sample.physicalValue())
                + " validity=" + (sample == null ? null : sample.validity()));
        assertNotNull(sample, filePart);
        assertEquals(expectedCount, entry.sampleCount(), filePart);
        assertEquals(643L, entry.canId(), filePart);
        assertEquals("V", entry.unit(), filePart);
        assertTrue(entry.sourceFile().contains(filePart), entry.sourceFile());
        assertEquals(timestampNanos, sample.timestampNanos(), filePart);
        assertEquals(raw, sample.rawValue(), filePart);
        assertEquals(physical, sample.physicalValue(), 1e-9, filePart);
        assertEquals("VALID", String.valueOf(sample.validity()), filePart);
    }

    private static SignalCatalogEntry physical(DesktopAppService service, String filePart) {
        ProjectMergedSignalStore merged = (ProjectMergedSignalStore) service.signalsModel().catalogStore();
        return merged.physicalCatalogAll().stream()
                .filter(item -> SIGNAL.equals(item.signalName()) && item.sourceFile().contains(filePart))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + SIGNAL + " in " + filePart
                        + " counts=" + counts(service)));
    }

    private static long count(DesktopAppService service, String filePart) {
        ProjectMergedSignalStore merged = (ProjectMergedSignalStore) service.signalsModel().catalogStore();
        if (merged == null) {
            return 0L;
        }
        return merged.physicalCatalogAll().stream()
                .filter(item -> SIGNAL.equals(item.signalName()) && item.sourceFile().contains(filePart))
                .mapToLong(SignalCatalogEntry::sampleCount)
                .findFirst()
                .orElse(0L);
    }

    private static String counts(DesktopAppService service) {
        ProjectMergedSignalStore merged = (ProjectMergedSignalStore) service.signalsModel().catalogStore();
        if (merged == null) {
            return "[]";
        }
        return merged.physicalCatalogAll().stream()
                .filter(item -> SIGNAL.equals(item.signalName()))
                .map(item -> item.sourceFile() + "=" + item.sampleCount())
                .toList()
                .toString();
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
            graph.graphCatalogForTest().stream()
                    .filter(entry -> SIGNAL.equals(entry.signalName()))
                    .findFirst()
                    .ifPresent(entry -> {
                        if (!graph.isSelectedForTest(entry.key())) {
                            graph.fireExplorerCheckboxForTest(entry.key());
                        }
                    });
            graph.setLayoutModeForTest(true);
        });
        await("stacked", () -> "STACKED".equals(graph.layoutModeForTest())
                && graph.selectedSignalCountForTest() > 0
                && !graph.graphStatusForTest().startsWith("Loading"), 180);
        String stacked = colours(graph);
        String hover = hover(graph, SIGNAL);
        runOnFx(() -> graph.setLayoutModeForTest(false));
        await("overlay", () -> graph.layoutModeForTest().contains("OVERLAY")
                && !graph.graphStatusForTest().startsWith("Loading"), 120);
        String overlay = colours(graph);
        long before = graph.viewportEndForTest() - graph.viewportStartForTest();
        runOnFx(() -> fireTooltip(shell.root, "Zoom in"));
        await("zoom", () -> graph.viewportEndForTest() - graph.viewportStartForTest() < before
                && !graph.graphStatusForTest().startsWith("Loading"), 90);
        long zoomed = graph.viewportEndForTest() - graph.viewportStartForTest();
        long startBeforePan = graph.viewportStartForTest();
        runOnFx(() -> fireTooltip(shell.root, "Pan left"));
        long panned = graph.viewportStartForTest();
        runOnFx(() -> graph.fitForTest());
        await("fit", () -> !graph.graphStatusForTest().startsWith("Loading"), 90);
        long fitted = graph.viewportEndForTest() - graph.viewportStartForTest();
        runOnFx(() -> graph.resetViewForTest());
        await("reset", () -> !graph.graphStatusForTest().startsWith("Loading"), 90);
        long resetSpan = graph.viewportEndForTest() - graph.viewportStartForTest();
        note("graph stacked=" + stacked);
        note("graph overlay=" + overlay);
        note("graph hover=" + one(hover));
        note("graph zoomSpan=" + before + "->" + zoomed
                + " panStart=" + startBeforePan + "->" + panned
                + " fitSpan=" + fitted
                + " resetSpan=" + resetSpan
                + " timeline=" + graph.timelineGroupForTest()
                + " status=" + one(graph.graphStatusForTest()));
        assertTrue(stacked.contains(SIGNAL) && stacked.contains("unit=V"), stacked);
        assertTrue(overlay.contains(SIGNAL), overlay);
        assertTrue(hover.contains(SIGNAL) && hover.contains(FOCUS), hover);
        assertTrue(zoomed < before, "zoom did not shrink the span");
        assertTrue(panned != startBeforePan, "pan did not move the start");
    }

    private static void analytics(DesktopAppService service) {
        SignalCatalogEntry voltage = physical(service, FOCUS);
        AnalysisResult result = service.runEngineeringAnalysis(AnalysisRequest.builder(AnalysisType.STATISTICS)
                .projectId(service.currentProjectId())
                .addSignal(voltage.key())
                .build());
        note("analytics status=" + result.status() + " message=" + result.message());
        assertEquals(AnalysisStatus.COMPLETED, result.status(), result.message());
        SignalStatistics stats = result.statistics().getFirst();
        note("analytics samples=" + stats.sampleCount() + " min=" + stats.min() + " max=" + stats.max());
        assertEquals(voltage.sampleCount(), stats.sampleCount());
        assertTrue(stats.min() <= 12.4 && stats.max() >= 12.4, stats.min() + ".." + stats.max());
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
            shell.controller.investigationControllerForTest().setReportDestinationForTest("BLF NEDC", reports);
            button(shell.root, "Generate HTML Report").fire();
        });
        await("html", () -> htmlReady(reports), 120);
        Path html = Files.list(reports).filter(path -> path.getFileName().toString().endsWith(".html")).findFirst().orElseThrow();
        String text = Files.readString(html);
        note("html bytes=" + Files.size(html)
                + " project=" + text.contains("BLF NEDC")
                + " file=" + text.contains(FOCUS)
                + " signal=" + text.contains(SIGNAL)
                + " unit=" + text.contains("V"));
        assertTrue(text.contains(FOCUS) && text.contains(SIGNAL), "report omitted the BLF signal");
    }

    private static void removeAndRestore(ShellFx shell, DesktopAppService service) throws Exception {
        long beforeFocus = count(service, FOCUS);
        long before3816 = count(service, "13-38-16");
        long before4229 = count(service, "13-42-29");
        long before4642 = count(service, "13-46-42");
        note("lifecycle beforeRemoval focus=" + beforeFocus
                + " 13-38-16=" + before3816
                + " 13-42-29=" + before4229
                + " 13-46-42=" + before4642);
        Path original = Files.list(DIR).filter(path -> path.getFileName().toString().contains(FOCUS)).findFirst().orElseThrow();
        String hash = sha256(original);
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.SOURCES);
            shell.root.applyCss();
            shell.root.layout();
            removeRow(shell, FOCUS);
        });
        await("removed", () -> service.workspace().measurementFiles().stream()
                .noneMatch(file -> file.fileName().contains(FOCUS)), 20);
        note("lifecycle removedCount=" + count(service, FOCUS)
                + " 13-38-16=" + count(service, "13-38-16")
                + " 13-42-29=" + count(service, "13-42-29")
                + " 13-46-42=" + count(service, "13-46-42"));
        assertEquals(0L, count(service, FOCUS));
        assertEquals(before3816, count(service, "13-38-16"));
        assertEquals(before4229, count(service, "13-42-29"));
        assertEquals(before4642, count(service, "13-46-42"));
        addFile(shell, original, "+ Add Measurement Files", "Browse Files");
        awaitInventory(service, 10);
        note("readd files=" + service.workspace().measurementFiles().stream()
                .map(file -> file.fileName() + ":" + service.inventoryJobState(file.projectSourceId()))
                .toList());
        int runsBefore = service.persistedProcessingRuns().size();
        boolean started = startProcessing(shell);
        note("reprocessStarted=" + started);
        assertTrue(started, "reprocess was not available");
        ProjectProcessingStatus status = awaitProcessing(service, 30, runsBefore);
        note("lifecycle restoredCount=" + count(service, FOCUS)
                + " 13-38-16=" + count(service, "13-38-16")
                + " 13-42-29=" + count(service, "13-42-29")
                + " 13-46-42=" + count(service, "13-46-42")
                + " status=" + status
                + " message=" + service.workspace().latestSummary().map(summary -> summary.userMessage()).orElse(""));
        boolean restored = count(service, FOCUS) == beforeFocus
                && count(service, "13-38-16") == before3816
                && count(service, "13-42-29") == before4229
                && count(service, "13-46-42") == before4642
                && sha256(original).equals(hash);
        note("lifecycleRestore=" + (restored ? "PASS" : "FAIL"));
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
            await("reopen", () -> reopened.workspace().measurementFiles().size() == files, 90);
            note("reopen files=" + reopened.workspace().measurementFiles().size()
                    + " kinds=" + reopened.workspace().measurementFiles().stream().map(file -> file.kind().name()).distinct().toList()
                    + " definitions=" + reopened.workspace().definitionFiles().stream().map(WorkspaceFile::fileName).toList()
                    + " counts=" + counts(reopened)
                    + " graphKeys=" + reopened.signalsModel().workspaceKeys().size()
                    + " status=" + reopened.workspace().processingStatus());
            assertEquals(4, reopened.workspace().measurementFiles().size());
            assertTrue(reopened.workspace().measurementFiles().stream().allMatch(file -> "MEASUREMENT_BLF".equals(file.kind().name())));
            assertTrue(reopened.workspace().definitionFiles().stream().anyMatch(file -> file.fileName().equals("2.dbc")));
            assertEquals(5163L, count(reopened, FOCUS));
            assertEquals(12639L, count(reopened, "13-38-16"));
            assertEquals(12638L, count(reopened, "13-42-29"));
            assertEquals(12638L, count(reopened, "13-46-42"));
            assertFalse(reopened.signalsModel().workspaceKeys().isEmpty(), "graph selection was not restored");
            var route = new ProjectRouteReportLoader().load(reopened);
            int segments = route == null || route.route() == null ? 0 : route.route().gpsCapableCount();
            note("reopen gpsCapable=" + segments + " html=" + htmlReady(home.resolve("reports")));
            assertTrue(htmlReady(home.resolve("reports")));
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
        Thread.sleep(800);
        return runOnFxValue(() -> {
            shell.controller.openRoute(WorkspaceRoute.REVIEW_AND_PROCESS);
            shell.root.applyCss();
            shell.root.layout();
            Button run = button(shell.root, "Start Processing");
            note("startLabel=" + (run == null ? "missing" : run.getText())
                    + " disabled=" + (run == null || run.isDisabled()));
            if (run != null && !run.isDisabled()) {
                run.fire();
                return true;
            }
            return false;
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
            var latest = service.latestPersistedProcessingRun().orElse(null);
            boolean terminal = latest != null && ProcessingRunStatuses.isProtectedTerminal(latest.status());
            ProjectProcessingStatus status = service.workspace().processingStatus();
            boolean catalogReady = service.signalsModel().catalog() != null
                    && !service.signalsModel().catalog().entries().isEmpty();
            // The plan path records one durable terminal run after artifacts are committed.
            // Workspace status can remain RUNNING after that, so the run status is the gate.
            if (newRun && terminal && journey != null && journey.analysisAvailable() && catalogReady) {
                if (status == ProjectProcessingStatus.RUNNING || status == ProjectProcessingStatus.IDLE
                        || status == ProjectProcessingStatus.READY) {
                    return journey.readyWithWarnings()
                            ? ProjectProcessingStatus.COMPLETED_WITH_WARNINGS
                            : ProjectProcessingStatus.COMPLETED;
                }
                return status;
            }
            if (newRun && terminal && (status == ProjectProcessingStatus.FAILED
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
        StringBuilder value = new StringBuilder();
        if (node instanceof Labeled labeled && labeled.getText() != null) {
            value.append(labeled.getText());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                value.append(text(child));
            }
        }
        return value.toString();
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
        assertTrue(done.await(60, TimeUnit.SECONDS), "JavaFX work did not return");
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
        assertTrue(done.await(60, TimeUnit.SECONDS), "JavaFX work did not return");
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
        assertTrue(done.await(20, TimeUnit.SECONDS));
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(path));
        return HexFormat.of().formatHex(digest.digest()).toUpperCase();
    }

    private static void note(String line) {
        NOTES.append(line).append(System.lineSeparator());
    }

    private static String one(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private record ShellFx(Stage stage, MainShellController controller, Parent root) {
    }
}
