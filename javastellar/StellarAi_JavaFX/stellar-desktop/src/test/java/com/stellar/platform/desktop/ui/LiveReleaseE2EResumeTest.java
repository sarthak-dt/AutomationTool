package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.DesktopLogging;
import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.UnsavedLeaveChoice;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.InvestigationReportRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fresh JVM after the completed E2E run. Confirms restore, isolation, and the
 * controls that are actually available. Does not recreate Analysis Storage.
 */
@Tag("live-e2e")
class LiveReleaseE2EResumeTest {
    private static final Path SHOTS = Path.of("D:/Reports/StellarAI_1.0.0_E2E/02_Evidence/Screenshots");
    private static final Path RESULTS = Path.of("D:/Reports/StellarAI_1.0.0_E2E/03_TestResults/LiveE2E/resume.tsv");
    private static final Path DB = Path.of("D:/StellarAI-E2E/Storage/StellarAIAnalysis/database/stellar.db");
    private static final Path EXTRA = Path.of(
            "C:/Users/DT - User/Downloads/OneDrive_2_7-13-2026_MF4 files/4BE883CD_00000984_00000001-63C8C9EC.MF4");
    private static final Path OPERATOR = Path.of(System.getenv("LOCALAPPDATA"),
            "StellarAI", "config", "analysis-storage.properties");
    private static final List<String> ROWS = new ArrayList<>();
    private static DesktopAppService service;
    private static MainShellController shell;
    private static Stage stage;
    private static Scene scene;

    static { pin(); }

    @BeforeAll
    static void startFx() throws Exception {
        Files.createDirectories(SHOTS);
        Files.createDirectories(RESULTS.getParent());
        CountDownLatch started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException already) { started.countDown(); }
        assertTrue(started.await(15, TimeUnit.SECONDS));
    }

    @AfterAll
    static void shutdown() {
        try {
            if (shell != null) onFx(shell::dispose);
            if (service != null) service.shutdown();
        } catch (RuntimeException ignored) { }
        DesktopLogging.closeForTests();
        AnalysisStorageRoots.clear();
    }

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void resumeIsolationReprocessAndNavigation() throws Exception {
        pin();
        String operatorBefore = hash(OPERATOR);
        launch();
        String project = shell.projectNameChromeForTest();
        int catalog = service.signalsModel().catalog().size();
        long signalStore = countSignalStore(Path.of("D:/StellarAI-E2E/Storage/StellarAIAnalysis/projects/1001"));
        open(WorkspaceRoute.PROCESSING_RESULT);
        shot("030-restart");
        String title = processingTitle();
        record("030", "project=" + project + " id=" + service.currentProjectId()
                + " projects=" + service.savedProjects().size()
                + " sources=" + service.workspace().measurementFiles().size()
                + " catalog=" + catalog + " signalStoreEntries=" + signalStore
                + " db=" + service.activeDatabasePath().map(Path::toString).orElse("")
                + " processingTitle=" + title
                + " reportExists=" + Files.isRegularFile(Path.of(
                "D:/StellarAI-E2E/Storage/StellarAIAnalysis/projects/1001/investigations/c6c0abcf-8dd6-419b-878a-bd717da4dc18/reports/05addade-c9f5-47bb-a316-c553ae4cc259/report.html")));

        shell.setNewProjectNameForTest("E2E_Project_B");
        onFx(() -> shell.applyCreatedProject("E2E_Project_B"));
        pulse();
        String bId = service.currentProjectId();
        var added = service.addMeasurementPaths(List.of(EXTRA), Platform::runLater);
        pulse();
        int bCatalog = service.signalsModel().catalog().size();
        String bNames = service.savedProjects().stream().map(p -> p.name() + ":" + p.projectId()).reduce((a, c) -> a + "," + c).orElse("");
        shot("031-project-b");
        record("031-b", "id=" + bId + " name=" + shell.projectNameChromeForTest()
                + " added=" + added.added().size() + " catalog=" + bCatalog
                + " measurements=" + service.workspace().measurementFiles().size()
                + " projects=" + bNames);

        onFx(() -> shell.requestOpenSavedProject("1001"));
        pulse();
        boolean back = waitUntil(20_000, () -> "1001".equals(service.currentProjectId()));
        shot("031-back-to-a");
        record("031-a", "back=" + back + " id=" + service.currentProjectId()
                + " name=" + shell.projectNameChromeForTest()
                + " measurements=" + service.workspace().measurementFiles().size()
                + " catalog=" + service.signalsModel().catalog().size());

        var asc = service.workspace().measurementFiles().stream()
                .filter(f -> f.fileName() != null && f.fileName().toLowerCase().contains("desktop_engine"))
                .findFirst();
        boolean removed = asc.isPresent() && service.removeFile(asc.get().id());
        String left = service.workspace().measurementFiles().stream().map(f -> f.fileName()).reduce((a, c) -> a + "," + c).orElse("");
        open(WorkspaceRoute.REVIEW_AND_PROCESS);
        Button rerun = button("#processing-rerun-button");
        Button start = button("#processing-run-button");
        record("032-controls", "removedAsc=" + removed + " left=" + left
                + " rerunVisible=" + (rerun != null && rerun.isVisible())
                + " start=" + (start == null ? "missing" : start.getText() + " disabled=" + start.isDisabled()));
        if (start != null && !start.isDisabled()) {
            onFx(start::fire);
            boolean done = waitUntil(4 * 60_000, () -> !service.isPlanExecutionActive()
                    && service.signalsModel().catalog().size() > 0);
            pulse();
            long storeAfter = countSignalStore(Path.of("D:/StellarAI-E2E/Storage/StellarAIAnalysis/projects"));
            record("032-run", "done=" + done + " catalog=" + service.signalsModel().catalog().size()
                    + " active=" + service.isPlanExecutionActive() + " signalStoreEntries=" + storeAfter
                    + " running=" + runningRows());
        }

        open(WorkspaceRoute.SIGNALS);
        SignalsController signals = (SignalsController) shell.controllersForTest().get(NavigationPage.SIGNALS);
        if (signals != null && signals.browserPaneForTest().selectAllButtonForTest() != null
                && service.signalsModel().catalog().size() > 0) {
            onFx(signals.browserPaneForTest().selectAllButtonForTest()::fire);
            pulse();
            var field = signals.workspacePaneForTest().indexFieldForTest();
            onFx(() -> field.setText("abc"));
            onFx(signals.workspacePaneForTest().indexGoButtonForTest()::fire);
            pulse();
            record("017", "feedback=" + feedback() + " toolbar=" + signals.workspacePaneForTest().toolbarStructureForTest());
            shot("017-invalid-index");
        }
        open(WorkspaceRoute.GRAPHS);
        Button showAll = button("#showAllButton");
        if (showAll != null) onFx(showAll::fire);
        pulse();
        ToggleButton overlay = toggle("#overlayLayoutButton");
        if (overlay != null) onFx(overlay::fire);
        pulse();
        shot("018-overlay");
        String status = text(".graph-status");
        ToggleButton stacked = toggle("#stackedLayoutButton");
        if (stacked != null) onFx(stacked::fire);
        pulse();
        shot("019-stacked");
        record("018", "keys=" + service.signalsModel().workspaceKeys().size() + " status=" + status
                + " overlay=" + (overlay != null && overlay.isSelected())
                + " stacked=" + (stacked != null && stacked.isSelected()));

        AtomicInteger failures = new AtomicInteger();
        long before = usedHeap();
        int nodesBefore = nodeCount();
        for (int i = 0; i < 8; i++) {
            try {
                open(i % 2 == 0 ? WorkspaceRoute.SIGNALS : WorkspaceRoute.GRAPHS);
            } catch (RuntimeException ex) {
                failures.incrementAndGet();
            }
        }
        record("033", "switchFailures=" + failures.get() + " nodes=" + nodeCount());
        record("039", "heapBefore=" + before + " heapAfter=" + usedHeap()
                + " nodesBefore=" + nodesBefore + " nodesAfter=" + nodeCount());

        resize(1280, 720, "035-1280");
        resize(1366, 768, "035-1366");
        resize(1920, 1080, "035-1920");
        record("035", "clip1280=" + clipped(1280) + " clip1366=" + clipped(1366) + " clip1920=" + clipped(1920));
        record("034", "sourceLabel=" + firstTruncatedText());

        open(WorkspaceRoute.INVESTIGATIONS);
        shot("029-investigations");
        InvestigationReportRow row = loadReport();
        if (row != null) {
            try {
                InvestigationReportRow renamed = service.renameInvestigationReport(row, "E2E_Report_Renamed", List.of(row.name()));
                boolean file = Files.isRegularFile(Path.of(renamed.destination()));
                record("029-rename", "name=" + renamed.name() + " dest=" + renamed.destination() + " file=" + file);
                service.deleteInvestigationReport(renamed);
                record("029-delete", "fileRemains=" + Files.exists(Path.of(renamed.destination())));
            } catch (Exception ex) {
                record("029-rename", "failed " + ex.getClass().getSimpleName() + " " + ex.getMessage());
            }
        } else {
            record("029", "report row missing");
        }
        record("037", "operatorUnchanged=" + operatorBefore.equals(hash(OPERATOR)));
        record("023", "Missing GPS-bearing measurement fixture.");
        flush();
    }

    private static long countSignalStore(Path root) {
        if (!Files.exists(root)) return 0;
        try (var walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().contains("signal-store")).count();
        } catch (Exception ex) {
            return -1;
        }
    }

    private static String processingTitle() {
        Object controller = shell.controllersForTest().get(NavigationPage.PROCESSING);
        if (controller instanceof ProcessingController processing && processing.workspacePaneForTest() != null) {
            return processing.workspacePaneForTest().titleForTest();
        }
        return "";
    }

    private static InvestigationReportRow loadReport() {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + DB.toString().replace('\\', '/'));
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT report_id, investigation_id, project_id, name, format, status, destination, message, created_at, finished_at FROM investigation_reports WHERE project_id='1001'")) {
            if (!rs.next()) return null;
            return new InvestigationReportRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                    Instant.parse(rs.getString(9)), rs.getString(10) == null ? null : Instant.parse(rs.getString(10)));
        } catch (Exception ex) {
            record("029-load", ex.getClass().getSimpleName() + " " + ex.getMessage());
            return null;
        }
    }

    private static String runningRows() {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + DB.toString().replace('\\', '/'));
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM processing_jobs WHERE UPPER(status)='RUNNING'")) {
            return rs.next() ? Integer.toString(rs.getInt(1)) : "?";
        } catch (Exception ex) {
            return ex.getClass().getSimpleName();
        }
    }

    private static String firstTruncatedText() {
        StringBuilder found = new StringBuilder();
        for (Node node : scene.getRoot().lookupAll(".text")) {
            if (node instanceof javafx.scene.text.Text text && text.getText() != null && text.getText().contains("…")) {
                found.append(text.getText()).append(" | ");
                if (found.length() > 180) break;
            }
        }
        return found.toString();
    }

    private static int clipped(double width) {
        int cut = 0;
        for (Node node : scene.getRoot().lookupAll(".button")) {
            if (!(node instanceof Button button) || !button.isVisible() || button.getText() == null) continue;
            Bounds bounds = button.localToScene(button.getBoundsInLocal());
            if (bounds.getWidth() > 4 && bounds.getMaxX() > width + 8 && bounds.getMinX() < width) cut++;
        }
        return cut;
    }

    private static int nodeCount() {
        AtomicInteger count = new AtomicInteger();
        onFx(() -> count.set(countNodes(scene.getRoot())));
        return count.get();
    }

    private static int countNodes(Node node) {
        int n = 1;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) n += countNodes(child);
        }
        return n;
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void resize(double width, double height, String name) {
        onFx(() -> { stage.setWidth(width); stage.setHeight(height); });
        pulse();
        shot(name);
    }

    private static void pin() {
        System.setProperty("stellarai.test.isolate", "false");
        System.setProperty("stellarai.home", "D:\\StellarAI-E2E\\AppHome");
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        AnalysisStorageRoots.clear();
    }

    private static void launch() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                service = DesktopAppService.forLocalProduction(new DesktopSession());
                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                Parent root = loader.load();
                shell = loader.getController();
                shell.setNewProjectNameForTest("E2E_Project_A");
                shell.setUnsavedLeaveDecisionForTest(intent -> UnsavedLeaveChoice.DISCARD);
                shell.init(service);
                scene = new Scene(root, 1366, 768);
                scene.getStylesheets().add(MainShellController.class.getResource("/css/stellar-desktop.css").toExternalForm());
                stage = new Stage();
                stage.setTitle(DesktopVersion.windowTitle());
                stage.setScene(scene);
                stage.show();
            } catch (Exception ex) { error.set(ex); }
            finally { latch.countDown(); }
        });
        if (!latch.await(60, TimeUnit.SECONDS) || error.get() != null) {
            throw new IllegalStateException("shell failed", error.get());
        }
        pulse();
    }

    private static void open(WorkspaceRoute route) {
        onFx(() -> shell.openRoute(route));
        pulse();
    }

    private static String feedback() {
        StringBuilder text = new StringBuilder();
        for (Node node : scene.getRoot().lookupAll(".nav-feedback")) {
            if (node instanceof Label label && label.getText() != null) text.append(label.getText()).append(' ');
        }
        return text.toString().trim();
    }

    private static Button button(String selector) {
        Node node = scene.lookup(selector);
        return node instanceof Button button ? button : null;
    }

    private static ToggleButton toggle(String selector) {
        Node node = scene.lookup(selector);
        return node instanceof ToggleButton button ? button : null;
    }

    private static String text(String selector) {
        Node node = scene.lookup(selector);
        return node instanceof Label label && label.getText() != null ? label.getText().replace('\n', ' ') : "";
    }

    private static void shot(String name) {
        onFx(() -> {
            try {
                WritableImage image = scene.snapshot(null);
                if (image != null) ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", SHOTS.resolve(name + ".png").toFile());
            } catch (Exception ignored) { }
        });
    }

    private static boolean waitUntil(long timeoutMs, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < end) {
            AtomicReference<Boolean> value = new AtomicReference<>(false);
            CountDownLatch latch = new CountDownLatch(1);
            Platform.runLater(() -> {
                try { value.set(condition.getAsBoolean()); }
                catch (Exception ignored) { value.set(false); }
                finally { latch.countDown(); }
            });
            if (!latch.await(5, TimeUnit.SECONDS)) return false;
            if (Boolean.TRUE.equals(value.get())) return true;
            Thread.sleep(300);
        }
        return false;
    }

    private static void pulse() {
        try { waitUntil(700, () -> true); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    private static void onFx(Runnable action) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try { action.run(); }
            catch (Throwable t) { error.set(t); }
            finally { latch.countDown(); }
        });
        try { latch.await(30, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        if (error.get() != null) throw new IllegalStateException(error.get());
    }

    private static String hash(Path path) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            return "missing";
        }
    }

    private static void record(String id, String evidence) {
        ROWS.add(id + "\t" + Instant.now() + "\t" + evidence);
        flush();
    }

    private static void flush() {
        try {
            Files.writeString(RESULTS, String.join(System.lineSeparator(), ROWS) + System.lineSeparator());
        } catch (Exception ignored) { }
    }
}
