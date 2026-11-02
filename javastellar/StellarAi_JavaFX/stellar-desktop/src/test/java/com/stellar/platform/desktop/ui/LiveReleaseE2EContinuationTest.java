package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.DesktopLogging;
import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.UnsavedLeaveChoice;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import javafx.fxml.FXMLLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
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
 * Continues the 1.0.0 live campaign on the already configured E2E project.
 * It resumes project 1001 and clicks Start Processing. It does not recreate storage.
 */
@Tag("live-e2e")
class LiveReleaseE2EContinuationTest {
    private static final Path SHOTS = Path.of("D:/Reports/StellarAI_1.0.0_E2E/02_Evidence/Screenshots");
    private static final Path RESULTS = Path.of("D:/Reports/StellarAI_1.0.0_E2E/03_TestResults/LiveE2E/continuation.tsv");
    private static final Path DB = Path.of("D:/StellarAI-E2E/Storage/StellarAIAnalysis/database/stellar.db");
    private static final List<String> ROWS = new ArrayList<>();

    private static DesktopAppService service;
    private static MainShellController shell;
    private static Stage stage;

    static {
        pinHome();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void continueProcessingSignalsAndGraphs() throws Exception {
        pinHome();
        Files.createDirectories(SHOTS);
        Files.createDirectories(RESULTS.getParent());
        launchShell();
        record("ENV", "INFO", true, "project=" + text("#projectNameLabel")
                + " id=" + service.currentProjectId()
                + " catalog=" + catalogSize());

        open(WorkspaceRoute.REVIEW_AND_PROCESS);
        boolean planReady = waitUntil(90_000, () -> {
            Button run = button("#processing-run-button");
            return run != null && !run.isDisabled() && run.getText() != null && run.getText().startsWith("Start Processing");
        });
        String summary = labelText(".processing-workspace-summary");
        int jobs = count(".processing-jobs-table .table-row-cell");
        shot("010-review-ready");
        record("010", "BLOCKED", planReady && jobs >= 3,
                "old=BLOCKED jobs=" + jobs + " startEnabled=" + planReady + " summary=" + summary);

        Button run = button("#processing-run-button");
        if (run == null || run.isDisabled()) {
            record("011", "NOT RUN", false, "old=NOT RUN Start Processing stayed disabled. " + summary);
            record("012", "NOT RUN", false, "old=NOT RUN processing did not start");
            record("013", "NOT RUN", false, "old=NOT RUN processing did not start");
            record("014", "BLOCKED", false, "old=BLOCKED catalog=" + catalogSize());
            flush();
            return;
        }
        onFx(run::fire);
        long started = System.nanoTime();
        boolean finished = waitUntil(12 * 60_000, () -> !service.isPlanExecutionActive()
                && service.workspace().latestSummary().isPresent());
        double elapsedSec = (System.nanoTime() - started) / 1_000_000_000.0;
        pulse();
        String phase = labelText(".plan-status-badge");
        String elapsedLabel = text("#processing-total-elapsed");
        String completeSummary = labelText(".processing-workspace-summary");
        shot("011-processing-complete");
        String running = runningRows();
        boolean noRunning = running.startsWith("0");
        boolean sane = elapsedSec > 0.2 && elapsedSec < 12 * 60 && elapsedLabel != null && !elapsedLabel.isBlank();
        record("011", "NOT RUN", finished && noRunning && sane,
                "old=NOT RUN finished=" + finished + " wallSec=" + String.format("%.1f", elapsedSec)
                        + " elapsedLabel=" + elapsedLabel + " phase=" + phase
                        + " running=" + running + " summary=" + completeSummary);

        boolean details = openDetails();
        shot("012-details");
        closeDetails();
        record("012", "NOT RUN", details, "old=NOT RUN detailsDialog=" + details + " title=Processing Details");

        boolean layout = finished && button("#processing-run-button") != null;
        shot("013-complete-layout");
        record("013", "NOT RUN", layout && catalogSize() > 0,
                "old=NOT RUN catalog=" + catalogSize() + " phase=" + phase);

        open(WorkspaceRoute.SIGNALS);
        SignalsController signals = signals();
        int rows = signals.browserPaneForTest().catalogTable().getItems().size();
        var structure = signals.workspacePaneForTest().toolbarStructureForTest();
        shot("014-signals-populated");
        boolean navigate = structure.contains("navigateParent=toolbar") && structure.contains("mode=Index");
        record("014", "BLOCKED", rows > 0 && navigate,
                "old=BLOCKED rows=" + rows + " toolbar=" + structure);

        Button selectAll = signals.browserPaneForTest().selectAllButtonForTest();
        if (selectAll != null) {
            onFx(selectAll::fire);
        }
        pulse();
        int selected = service.signalsModel().workspaceKeys().size();
        var index = signals.workspacePaneForTest().indexFieldForTest();
        onFx(() -> index.setText("0"));
        onFx(signals.workspacePaneForTest().indexGoButtonForTest()::fire);
        pulse();
        onFx(() -> index.setText("abc"));
        onFx(signals.workspacePaneForTest().indexGoButtonForTest()::fire);
        pulse();
        String invalid = feedback();
        onFx(() -> signals.workspacePaneForTest().selectNavigateTimeForTest());
        pulse();
        var afterTime = signals.workspacePaneForTest().toolbarStructureForTest();
        onFx(() -> index.setText("1"));
        onFx(signals.workspacePaneForTest().indexGoButtonForTest()::fire);
        pulse();
        shot("016-navigate");
        record("015", "BLOCKED", selected > 0, "old=BLOCKED selectedWorkspaceKeys=" + selected + " rows=" + rows);
        record("016", "BLOCKED", navigate && afterTime.contains("mode=Time"),
                "old=BLOCKED indexFeedback=" + invalid + " timeToolbar=" + afterTime);
        record("017", "BLOCKED", invalid.toLowerCase().contains("invalid"),
                "old=BLOCKED invalidIndexFeedback=" + invalid);

        int beforeGraphs = service.signalsModel().workspaceKeys().size();
        open(WorkspaceRoute.GRAPHS);
        int onGraphs = service.signalsModel().workspaceKeys().size();
        ToggleButton overlay = toggle("#overlayLayoutButton");
        ToggleButton stacked = toggle("#stackedLayoutButton");
        if (overlay != null) {
            onFx(overlay::fire);
        }
        pulse();
        shot("018-overlay");
        if (stacked != null) {
            onFx(stacked::fire);
        }
        pulse();
        shot("019-stacked");
        selectTab("Analytics");
        pulse();
        shot("020-analytics");
        selectTab("Engineering");
        pulse();
        shot("020-engineering");
        boolean plotted = !labelText(".graph-status").toLowerCase().contains("no signals plotted")
                || onGraphs > 0;
        record("018", "BLOCKED", onGraphs > 0 && onGraphs == beforeGraphs && overlay != null,
                "old=BLOCKED workspaceKeys=" + onGraphs + " before=" + beforeGraphs
                        + " overlay=" + (overlay != null && overlay.isSelected()));
        record("019", "BLOCKED", stacked != null && stacked.isSelected() && onGraphs > 0,
                "old=BLOCKED stacked=" + (stacked != null && stacked.isSelected()) + " keys=" + onGraphs);
        record("020", "BLOCKED", tabPresent("Analytics") && tabPresent("Engineering"),
                "old=BLOCKED analytics=" + tabPresent("Analytics") + " engineering=" + tabPresent("Engineering")
                        + " plotted=" + plotted);
        record("022", "BLOCKED", tabPresent("Engineering"),
                "old=BLOCKED engineeringTab=" + tabPresent("Engineering") + " keys=" + onGraphs);

        open(WorkspaceRoute.SIGNALS);
        int afterReturn = signals().browserPaneForTest().catalogTable().getItems().size();
        record("014-return", "INFO", afterReturn == rows && afterReturn > 0,
                "rowsAfterGraphs=" + afterReturn + " selected=" + service.signalsModel().workspaceKeys().size());

        record("023", "BLOCKED", false, "old=BLOCKED Missing GPS-bearing measurement fixture.");
        record("026", "BLOCKED", false, "old=BLOCKED Missing GPS-bearing measurement fixture.");
        record("028", "BLOCKED", false, "old=BLOCKED Missing GPS-bearing measurement fixture.");
        flush();
    }

    @BeforeAll
    static void startFx() throws Exception {
        Files.createDirectories(SHOTS);
        Files.createDirectories(RESULTS.getParent());
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException already) {
            started.countDown();
        }
        assertTrue(started.await(15, TimeUnit.SECONDS));
    }

    @AfterAll
    static void shutdown() {
        try {
            if (shell != null) {
                onFx(shell::dispose);
            }
            if (service != null) {
                service.shutdown();
            }
        } catch (Exception ignored) {
            // campaign evidence is already flushed
        }
        DesktopLogging.closeForTests();
        AnalysisStorageRoots.clear();
    }

    private static void pinHome() {
        System.setProperty("stellarai.test.isolate", "false");
        System.setProperty("stellarai.home", "D:\\StellarAI-E2E\\AppHome");
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        AnalysisStorageRoots.clear();
    }

    private static Scene scene;

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
                shell.setUnsavedLeaveDecisionForTest(intent -> UnsavedLeaveChoice.DISCARD);
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
        if (!latch.await(60, TimeUnit.SECONDS)) {
            throw new IllegalStateException("shell did not launch");
        }
        if (error.get() != null) {
            throw new IllegalStateException("shell launch failed", error.get());
        }
        pulse();
    }

    private static void open(WorkspaceRoute route) {
        onFx(() -> shell.openRoute(route));
        pulse();
    }

    private static SignalsController signals() {
        return (SignalsController) shell.controllersForTest().get(NavigationPage.SIGNALS);
    }

    private static int catalogSize() {
        var catalog = service.signalsModel().catalog();
        return catalog == null ? 0 : catalog.size();
    }

    private static boolean openDetails() {
        Button details = firstButton("Details");
        if (details == null) {
            details = firstButtonPrefix("Warnings");
        }
        if (details == null) {
            return false;
        }
        onFx(details::fire);
        pulse();
        return javafx.stage.Window.getWindows().stream()
                .anyMatch(w -> w instanceof Stage dialog && "Processing Details".equals(dialog.getTitle()));
    }

    private static Button firstButtonPrefix(String prefix) {
        if (stage.getScene() == null) {
            return null;
        }
        for (Node node : stage.getScene().getRoot().lookupAll(".button")) {
            if (node instanceof Button button && button.getText() != null && button.getText().startsWith(prefix)) {
                return button;
            }
        }
        return null;
    }

    private static void closeDetails() {
        onFx(() -> javafx.stage.Window.getWindows().stream()
                .filter(Stage.class::isInstance)
                .map(Stage.class::cast)
                .filter(s -> "Processing Details".equals(s.getTitle()))
                .forEach(Stage::close));
        pulse();
    }

    private static String runningRows() {
        if (!Files.exists(DB)) {
            return "missing-db";
        }
        String url = "jdbc:sqlite:" + DB.toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            int runs = countStatus(st, "processing_runs");
            int jobs = countStatus(st, "processing_jobs");
            return runs + " runs / " + jobs + " jobs";
        } catch (Exception ex) {
            return "query-failed " + ex.getClass().getSimpleName();
        }
    }

    private static int countStatus(Statement st, String table) {
        try (ResultSet rs = st.executeQuery(
                "SELECT COUNT(*) FROM " + table + " WHERE UPPER(status)='RUNNING'")) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (Exception ex) {
            return -1;
        }
    }

    private static void selectTab(String title) {
        onFx(() -> {
            if (stage.getScene() == null) {
                return;
            }
            for (Node node : stage.getScene().getRoot().lookupAll(".tab-pane")) {
                if (node instanceof TabPane tabs) {
                    tabs.getTabs().stream().filter(t -> title.equals(t.getText())).findFirst()
                            .ifPresent(t -> tabs.getSelectionModel().select(t));
                }
            }
        });
    }

    private static boolean tabPresent(String title) {
        if (stage.getScene() == null) {
            return false;
        }
        for (Node node : stage.getScene().getRoot().lookupAll(".tab-pane")) {
            if (node instanceof TabPane tabs && tabs.getTabs().stream().anyMatch(t -> title.equals(t.getText()))) {
                return true;
            }
        }
        return false;
    }

    private static String feedback() {
        if (stage.getScene() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Node node : stage.getScene().getRoot().lookupAll(".nav-feedback")) {
            if (node instanceof Label label && label.getText() != null) {
                text.append(label.getText()).append(' ');
            }
        }
        return text.toString().trim();
    }

    private static Button firstButton(String label) {
        if (stage.getScene() == null) {
            return null;
        }
        for (Node node : stage.getScene().getRoot().lookupAll(".button")) {
            if (node instanceof Button button && label.equals(button.getText())) {
                return button;
            }
        }
        return null;
    }

    private static Button button(String selector) {
        Node node = stage.getScene() == null ? null : stage.getScene().lookup(selector);
        return node instanceof Button button ? button : null;
    }

    private static ToggleButton toggle(String selector) {
        Node node = stage.getScene() == null ? null : stage.getScene().lookup(selector);
        return node instanceof ToggleButton button ? button : null;
    }

    private static String text(String selector) {
        Node node = stage.getScene() == null ? null : stage.getScene().lookup(selector);
        if (node instanceof Label label) {
            return label.getText() == null ? "" : label.getText();
        }
        if (node instanceof TextField field) {
            return field.getText() == null ? "" : field.getText();
        }
        return node == null ? "" : node.toString();
    }

    private static String labelText(String selector) {
        Node node = stage.getScene() == null ? null : stage.getScene().lookup(selector);
        return node instanceof Label label && label.getText() != null ? label.getText().replace('\n', ' ') : "";
    }

    private static int count(String selector) {
        return stage.getScene() == null ? 0 : stage.getScene().getRoot().lookupAll(selector).size();
    }

    private static void shot(String name) {
        onFx(() -> {
            try {
                WritableImage image = stage.getScene().snapshot(null);
                Files.createDirectories(SHOTS);
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", SHOTS.resolve(name + ".png").toFile());
            } catch (Exception ignored) {
                // a missing screenshot is recorded by the scenario evidence
            }
        });
    }

    private static boolean waitUntil(long timeoutMs, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < end) {
            AtomicReference<Boolean> value = new AtomicReference<>(false);
            CountDownLatch latch = new CountDownLatch(1);
            Platform.runLater(() -> {
                try {
                    value.set(condition.getAsBoolean());
                } catch (Exception ignored) {
                    value.set(false);
                } finally {
                    latch.countDown();
                }
            });
            if (!latch.await(10, TimeUnit.SECONDS)) {
                return false;
            }
            if (Boolean.TRUE.equals(value.get())) {
                return true;
            }
            Thread.sleep(400);
        }
        return false;
    }

    private static void pulse() {
        try {
            waitUntil(1_200, () -> true);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void onFx(Runnable action) {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void record(String id, String oldStatus, boolean pass, String evidence) {
        ROWS.add(id + "\t" + (pass ? "PASS" : "FAIL") + "\t" + Instant.now() + "\t" + oldStatus + "\t" + evidence);
        flush();
    }

    private static void flush() {
        try {
            Files.writeString(RESULTS, String.join(System.lineSeparator(), ROWS) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // the next scenario flush retries
        }
    }
}
