package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.DesktopLogging;
import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.ui.MainShellController;
import com.stellar.platform.desktop.ui.SignalsController;
import com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane;
import com.stellar.platform.storage.StellarAiPaths;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live JavaFX shell against a dedicated E2E home. Does not point the operator
 * LOCALAPPDATA bootstrap at the E2E folder.
 */
@Tag("live-e2e")
class LiveReleaseE2ETest {

    static final Path APP_HOME = Path.of("D:\\StellarAI-E2E\\AppHome");
    static final Path STORAGE = Path.of("D:\\StellarAI-E2E\\Storage");
    static final Path EVIDENCE = Path.of("D:\\Reports\\StellarAI_1.0.0_E2E\\02_Evidence");
    static final Path SHOTS = EVIDENCE.resolve("Screenshots");
    static final Path RESULTS = Path.of("D:\\Reports\\StellarAI_1.0.0_E2E\\03_TestResults\\LiveE2E\\results.tsv");

    private static final Path MF4 = Path.of(
            "C:\\Users\\DT - User\\Downloads\\OneDrive_2_7-13-2026_MF4 files\\4BE883CD_00000975_00000001-63C7A554.MF4");
    private static final Path MF4_EXTRA = Path.of(
            "C:\\Users\\DT - User\\Downloads\\OneDrive_2_7-13-2026_MF4 files\\4BE883CD_00000984_00000001-63C8C9EC.MF4");
    private static final Path DBC_A = Path.of(
            "C:\\Users\\DT - User\\Downloads\\Multiple DBC's to match\\CSS-Electronics-OBD2-v1_4.dbc");
    private static final Path DBC_B = Path.of(
            "C:\\Users\\DT - User\\Downloads\\Multiple DBC's to match\\11_bit_OBD2 2.dbc");
    private static final Path DBC_C = Path.of(
            "D:\\DesktopApp\\DriveT\\stellar-dbc-core\\src\\test\\resources\\dbc\\common_decoder.dbc");
    private static final Path ASC = Path.of(
            "D:\\DesktopApp\\DriveT\\stellar-desktop\\src\\test\\resources\\asc\\desktop_engine.asc");

    private static final List<String> ROWS = new ArrayList<>();
    private static Stage stage;
    private static Scene scene;
    private static MainShellController shell;
    private static DesktopAppService service;

    static {
        System.setProperty("stellarai.test.isolate", "false");
        System.setProperty("stellarai.home", APP_HOME.toString());
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        System.clearProperty("jpackage.app-path");
    }

    @BeforeAll
    static void startFx() throws Exception {
        Files.createDirectories(SHOTS);
        Files.createDirectories(RESULTS.getParent());
        Files.createDirectories(EVIDENCE.resolve("Logs"));
        Files.createDirectories(EVIDENCE.resolve("StorageAudit"));
        Files.createDirectories(EVIDENCE.resolve("DatabaseAudit"));
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException already) {
            started.countDown();
        }
        assertTrue(started.await(15, TimeUnit.SECONDS));
    }

    @AfterAll
    static void persist() throws Exception {
        try {
            if (shell != null) {
                onFx(() -> shell.dispose());
            }
            if (service != null) {
                service.shutdown();
            }
        } catch (RuntimeException ex) {
            ROWS.add(line("HARNESS", "FAIL", "shutdown " + ex.getMessage()));
        }
        DesktopLogging.closeForTests();
        AnalysisStorageRoots.clear();
        Files.writeString(RESULTS, String.join(System.lineSeparator(), ROWS) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void liveShellWorkflow() throws Exception {
        System.setProperty("stellarai.test.isolate", "false");
        System.setProperty("stellarai.home", APP_HOME.toString());
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        AnalysisStorageRoots.clear();
        row("ENV", "INFO", "version=" + DesktopVersion.displayVersion()
                + " java=" + System.getProperty("java.version")
                + " home=" + StellarAiPaths.homeRoot());
        String operatorProps = operatorPropertiesHash();
        launchShell();
        row("001", scene == null ? "FAIL" : "PASS", "shell=" + (scene != null) + " title=" + stage.getTitle());
        shot("001-workspace");

        open(WorkspaceRoute.CONFIGURATION);
        TextField pathField = (TextField) scene.lookup("#storagePathField");
        Button save = (Button) scene.lookup("#saveButton");
        Label message = (Label) scene.lookup("#messageLabel");
        boolean configControls = pathField != null && save != null && message != null;
        row("002-controls", configControls ? "PASS" : "FAIL", "configuration fields present");
        if (configControls) {
            onFx(() -> pathField.setText(STORAGE.toString()));
            onFx(save::fire);
            boolean saved = waitUntil(20, () -> AnalysisStorageRoots.isConfigured()
                    && Files.isRegularFile(STORAGE.resolve("StellarAIAnalysis\\database\\stellar.db")));
            String db = saved ? AnalysisStorageRoots.userSelectedRoot().map(Path::toString).orElse("") : "";
            boolean layout = saved
                    && Files.isDirectory(STORAGE.resolve("StellarAIAnalysis\\database"))
                    && Files.isDirectory(STORAGE.resolve("StellarAIAnalysis\\projects"))
                    && Files.isDirectory(STORAGE.resolve("StellarAIAnalysis\\logs"))
                    && Files.isDirectory(STORAGE.resolve("StellarAIAnalysis\\cache"))
                    && Files.isDirectory(STORAGE.resolve("StellarAIAnalysis\\temp"))
                    && Files.isDirectory(STORAGE.resolve("StellarAIAnalysis\\config"));
            row("002", saved && layout ? "PASS" : "FAIL",
                    "saved=" + saved + " layout=" + layout + " root=" + db
                            + " msg=" + text(message));
            shot("002-configuration");

            String before = service.activeDatabasePath().map(Path::toString).orElse("");
            onFx(() -> pathField.setText("Z:\\StellarAI-E2E-Missing"));
            onFx(save::fire);
            Thread.sleep(1500);
            String after = service.activeDatabasePath().map(Path::toString).orElse("");
            boolean still = before.equals(after) && after.contains("StellarAI-E2E");
            row("003", still ? "PASS" : "FAIL",
                    "before=" + before + " after=" + after + " msg=" + text(message));
            shot("003-invalid-storage");
        } else {
            row("002", "FAIL", "configuration page did not load");
            row("003", "BLOCKED", "configuration controls missing");
        }

        onFx(() -> shell.applyCreatedProject("E2E_Project_A"));
        boolean project = shell.projectNameChromeForTest().contains("E2E_Project_A");
        Path artifacts = project
                ? AnalysisStorageRoots.projectArtifactsRoot(service.currentProjectId())
                : null;
        boolean under = artifacts != null && artifacts.startsWith(STORAGE.resolve("StellarAIAnalysis"));
        row("004", project && under ? "PASS" : "FAIL",
                "name=" + shell.projectNameChromeForTest() + " id=" + service.currentProjectId()
                        + " artifacts=" + artifacts
                        + " lifecycle=" + shell.lastLifecycleErrorKindForTest() + " " + shell.lastLifecycleErrorForTest());
        shot("004-project");

        if (!project) {
            row("005", "BLOCKED", "no project");
            finishOperatorCheck(operatorProps);
            return;
        }
        var added = service.addMeasurementPaths(List.of(MF4, ASC), Platform::runLater);
        var defs = service.addDefinitionPaths(List.of(DBC_A, DBC_B, DBC_C));
        int measurements = service.workspace().measurementFiles().size();
        int definitions = service.workspace().definitionFiles().size();
        row("005", measurements >= 1 && definitions >= 2 ? "PASS" : "FAIL",
                "measurements=" + measurements + " definitions=" + definitions
                        + " added=" + added.added().size() + " skipped=" + added.skippedCount()
                        + " defsAdded=" + defs.added().size());
        open(WorkspaceRoute.SOURCES);
        shot("005-sources");

        var extra = service.addMeasurementPaths(List.of(MF4_EXTRA), Platform::runLater);
        int withExtra = service.workspace().measurementFiles().size();
        String extraId = extra.added().isEmpty() ? "" : extra.added().getFirst().id();
        boolean removed = !extraId.isBlank() && service.removeFile(extraId);
        int afterRemove = service.workspace().measurementFiles().size();
        boolean extraGone = service.workspace().measurementFiles().stream()
                .noneMatch(file -> file.fileName().contains("4BE883CD_00000984"));
        row("006", removed && extraGone && afterRemove == withExtra - 1 ? "PASS" : "FAIL",
                "withExtra=" + withExtra + " after=" + afterRemove + " removed=" + removed);
        shot("006-sources-after-remove");

        open(WorkspaceRoute.DECODE);
        shot("007-decode");
        boolean canProcess = waitUntil(45, () -> {
            try {
                return service.canStartLocalProcessing();
            } catch (RuntimeException ex) {
                return false;
            }
        });
        String hint = safe(() -> service.processingSelectionHint());
        row("007", "INFO", "canProcess=" + canProcess + " hint=" + hint
                + " files=" + service.workspace().measurementFiles().size());
        row("008", "INFO", "definitions=" + service.workspace().definitionFiles().stream()
                .map(file -> file.fileName()).toList());
        row("009", "INFO", "nonMatchingDbcPresent=" + service.workspace().definitionFiles().stream()
                .anyMatch(file -> file.fileName().contains("common_decoder")));

        long started = System.nanoTime();
        if (canProcess) {
            onFx(() -> service.startLocalProcessing(Platform::runLater, () -> { }));
            boolean done = waitUntil(180, () -> {
                try {
                    return !service.isPlanExecutionActive() && service.workspace().latestSummary().isPresent();
                } catch (RuntimeException ex) {
                    return false;
                }
            });
            long seconds = (System.nanoTime() - started) / 1_000_000_000L;
            row("010", done ? "PASS" : "FAIL", "done=" + done + " seconds=" + seconds
                    + " failed=" + safe(() -> String.valueOf(service.processingRunFailed()))
                    + " summary=" + service.workspace().latestSummary()
                    .map(summary -> summary.completionStatus() + " " + summary.userMessage()).orElse("none"));
        } else {
            row("010", "BLOCKED", "processing was not startable: " + hint);
        }
        open(WorkspaceRoute.PROCESSING_RESULT);
        shot("010-processing");
        row("011", "INFO", "elapsed=" + service.workspace().latestSummary().map(s -> String.valueOf(s.elapsed())).orElse("none"));
        row("012", "BLOCKED", "Details dialog was not opened in this live pass");
        row("013", "INFO", "processing page captured at " + (int) scene.getWidth() + "x" + (int) scene.getHeight());

        open(WorkspaceRoute.SIGNALS);
        Object signalsController = shell.controllersForTest().get(NavigationPage.SIGNALS);
        ActiveSignalWorkspacePane signals = signalsController instanceof SignalsController controller
                ? controller.workspacePaneForTest() : null;
        if (signals == null) {
            row("014", "FAIL", "signals workspace missing");
            row("015", "BLOCKED", "signals workspace missing");
            row("016", "BLOCKED", "signals workspace missing");
        } else {
            String structure = signals.toolbarStructureForTest();
            int rowsInTable = signals.table() == null ? -1 : signals.table().getItems().size();
            row("014", rowsInTable > 0 ? "PASS" : "FAIL", "rows=" + rowsInTable + " toolbar=" + structure);
            boolean navigate = structure.contains("Navigate") && !structure.contains("Go to Index")
                    && !structure.contains("Go to Time");
            row("015", navigate ? "PASS" : "FAIL", structure);
            shot("015-signals");
            if (rowsInTable > 0) {
                onFx(() -> {
                    signals.indexFieldForTest().setText("0");
                    signals.indexGoButtonForTest().fire();
                });
                onFx(() -> signals.indexFieldForTest().setText("-1"));
                onFx(() -> signals.indexGoButtonForTest().fire());
                onFx(() -> signals.indexFieldForTest().setText("abc"));
                onFx(() -> signals.indexGoButtonForTest().fire());
                onFx(signals::selectNavigateTimeForTest);
                onFx(() -> signals.indexFieldForTest().setText("not-a-time"));
                onFx(() -> signals.indexGoButtonForTest().fire());
                row("016", "PASS", "invalid navigation did not throw; prompt=" + signals.navigatePromptForTest()
                        + " status=" + signals.statusSelectedLabelForTest().getText());
            } else {
                row("016", "BLOCKED", "no sample rows");
            }
        }
        int reloadBefore = signals == null ? -1 : signals.reloadCountForTest();
        open(WorkspaceRoute.GRAPHS);
        shot("018-graphs");
        open(WorkspaceRoute.SIGNALS);
        int reloadAfter = signals == null ? -1 : signals.reloadCountForTest();
        row("017", signals == null ? "BLOCKED" : "INFO",
                "reloadBefore=" + reloadBefore + " reloadAfter=" + reloadAfter);
        row("018", "INFO", "graphs opened after signals");
        row("019", "INFO", "overlay not switched in this pass");
        row("020", "INFO", "stacked not switched in this pass");
        row("021", "INFO", "graph control labels captured in screenshot");

        open(WorkspaceRoute.GRAPHS);
        shot("022-graphs-again");
        row("022", "BLOCKED", "Engineering diagnostics tab was not exercised beyond the graph host");
        row("023", "BLOCKED", "no GPS route was confirmed on the available OBD2 measurement");

        var investigation = service.createInvestigation("E2E_Investigation_A", "live e2e", "CURRENT");
        boolean savedInv = investigation != null && investigation.investigationId() != null;
        row("024", savedInv ? "PASS" : "FAIL",
                savedInv ? investigation.investigationId() + " evidence=" + investigation.evidence().size() : "null");
        if (savedInv) {
            Path html = service.engineeringInvestigations().generateHtmlReport(
                    service.metadataStore(), investigation, "e2e", null);
            String page = Files.readString(html);
            boolean map = page.contains("Route Map");
            row("025", Files.isRegularFile(html) ? "PASS" : "FAIL", html.toString());
            row("026", map ? "FAIL" : "PASS", map
                    ? "map section present without route evidence"
                    : "no map section, report has no route evidence");
            row("027", page.contains("Route Map") ? "FAIL" : "PASS", "mapSection=" + map);
            shot("025-investigations");
        } else {
            row("025", "BLOCKED", "investigation was not created");
            row("026", "BLOCKED", "no report");
            row("027", "BLOCKED", "no report");
        }
        row("028", "BLOCKED", "map render failure needs a route-evidence report");
        row("029", "BLOCKED", "rename/delete not exercised without a map asset");

        resizeAndShot(1280, 720, "035-1280");
        resizeAndShot(1366, 768, "035-1366");
        resizeAndShot(1920, 1080, "035-1920");
        row("034", "INFO", "long-name layout not given a separate project in this pass");
        row("035", "INFO", "screenshots written for 1280, 1366 and 1920");
        row("036", "INFO", fontProbe());

        finishOperatorCheck(operatorProps);
        int schema = SqliteProjectMetadataStore.currentSchemaVersion();
        row("038-schema", schema == 20 ? "PASS" : "FAIL", "schema=" + schema);
    }

    private static void finishOperatorCheck(String before) throws Exception {
        String after = operatorPropertiesHash();
        row("037-operator", before.equals(after) ? "PASS" : "FAIL",
                "operator analysis-storage.properties unchanged=" + before.equals(after));
    }

    private static void launchShell() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                service = DesktopAppService.forLocalProduction(new DesktopSession());
                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                Parent root = loader.load();
                shell = loader.getController();
                shell.init(service);
                shell.setNewProjectNameForTest("E2E_Project_A");
                shell.setUnsavedLeaveDecisionForTest(intent -> com.stellar.platform.desktop.presentation.UnsavedLeaveChoice.DISCARD);
                scene = new Scene(root, 1366, 768);
                scene.getStylesheets().add(MainShellController.class.getResource("/css/stellar-desktop.css").toExternalForm());
                stage = new Stage();
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
        pulse();
    }

    private static void open(WorkspaceRoute route) throws Exception {
        onFx(() -> shell.openRoute(route));
        pulse();
    }

    private static void shot(String name) throws Exception {
        AtomicReference<WritableImage> image = new AtomicReference<>();
        onFx(() -> image.set(scene.snapshot(null)));
        if (image.get() != null) {
            ImageIO.write(SwingFXUtils.fromFXImage(image.get(), null), "png", SHOTS.resolve(name + ".png").toFile());
        }
    }

    private static void resizeAndShot(double width, double height, String name) throws Exception {
        onFx(() -> {
            stage.setWidth(width);
            stage.setHeight(height);
        });
        pulse();
        shot(name);
        row("035-" + (int) width, "INFO", clippedButtons());
    }

    private static String clippedButtons() {
        StringBuilder out = new StringBuilder();
        for (Button button : scene.getRoot().lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast).toList()) {
            if (!button.isVisible() || !button.isManaged()) {
                continue;
            }
            var bounds = button.localToScene(button.getBoundsInLocal());
            if (bounds.getMaxX() > scene.getWidth() + 4 || bounds.getMaxY() > scene.getHeight() + 4) {
                out.append(button.getText()).append("@").append((int) bounds.getMaxX()).append(",").append((int) bounds.getMaxY()).append("; ");
            }
        }
        return out.isEmpty() ? "no button past scene edge" : out.toString();
    }

    private static String fontProbe() {
        Label label = (Label) scene.lookup(".section-title");
        if (label == null) {
            label = scene.getRoot().lookupAll(".label").stream().filter(Label.class::isInstance).map(Label.class::cast).findFirst().orElse(null);
        }
        if (label == null) {
            return "no label";
        }
        var font = label.getFont();
        var fill = label.getTextFill();
        return "family=" + font.getFamily() + " size=" + font.getSize() + " fill=" + fill + " text=" + label.getText();
    }

    private static Button buttonByText(String text) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElse(null);
    }

    private static String text(Labeled labeled) {
        return labeled == null || labeled.getText() == null ? "" : labeled.getText().replace('\n', ' ');
    }

    private static String safe(java.util.function.Supplier<String> call) {
        try {
            return call.get();
        } catch (RuntimeException ex) {
            return ex.getClass().getSimpleName() + ":" + ex.getMessage();
        }
    }

    private static boolean waitUntil(int seconds, java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) {
            AtomicReference<Boolean> value = new AtomicReference<>(false);
            onFx(() -> value.set(condition.getAsBoolean()));
            if (Boolean.TRUE.equals(value.get())) {
                return true;
            }
            Thread.sleep(400);
        }
        return false;
    }

    private static void pulse() throws Exception {
        onFx(() -> { });
        Thread.sleep(250);
        onFx(() -> { });
    }

    private static void onFx(Runnable action) throws Exception {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable ex) {
                error.set(ex);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(30, TimeUnit.SECONDS), "FX action timed out");
        if (error.get() != null) {
            throw new IllegalStateException(error.get());
        }
    }

    private static void row(String id, String status, String detail) {
        String clean = detail == null ? "" : detail.replace('\t', ' ').replace('\n', ' ');
        ROWS.add(id + "\t" + status + "\t" + clean);
        System.out.println("E2E " + id + " " + status + " " + clean);
        try {
            Files.createDirectories(RESULTS.getParent());
            Files.writeString(RESULTS, String.join(System.lineSeparator(), ROWS) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (java.io.IOException ignored) {
            // The in-memory rows are still written from AfterAll.
        }
    }

    private static String line(String id, String status, String detail) {
        return id + "\t" + status + "\t" + detail;
    }

    private static String operatorPropertiesHash() throws Exception {
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) {
            return "no-localappdata";
        }
        Path file = Path.of(local, "StellarAI", "config", "analysis-storage.properties");
        if (!Files.isRegularFile(file)) {
            return "missing";
        }
        return Integer.toHexString(Files.readString(file).hashCode()) + ":" + Files.size(file);
    }
}
