package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.DesktopLogging;
import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.UnsavedLeaveChoice;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
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
 * Second look at the already processed E2E project. Does not start processing again.
 */
@Tag("live-e2e")
class LiveReleaseE2EUiEvidenceTest {
    private static final Path SHOTS = Path.of("D:/Reports/StellarAI_1.0.0_E2E/02_Evidence/Screenshots");
    private static final Path RESULTS = Path.of("D:/Reports/StellarAI_1.0.0_E2E/03_TestResults/LiveE2E/ui-evidence.tsv");
    private static final List<String> ROWS = new ArrayList<>();
    private static DesktopAppService service;
    private static MainShellController shell;
    private static Stage stage;
    private static Scene scene;

    static {
        pin();
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
        } catch (RuntimeException ignored) {
            // evidence is already flushed
        }
        DesktopLogging.closeForTests();
        AnalysisStorageRoots.clear();
    }

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void captureCompletedProcessingAndGraphs() throws Exception {
        pin();
        launch();
        record("ENV", "project=" + shell.projectNameChromeForTest() + " id=" + service.currentProjectId()
                + " catalog=" + service.signalsModel().catalog().size());

        open(WorkspaceRoute.REVIEW_AND_PROCESS);
        boolean titled = waitUntil(20_000, () -> {
            ProcessingController controller = processing();
            if (controller == null || controller.workspacePaneForTest() == null) {
                return false;
            }
            String title = controller.workspacePaneForTest().titleForTest();
            return title != null && title.toLowerCase().contains("processing complete");
        });
        ProcessingController processing = processing();
        String title = processing == null ? "" : processing.workspacePaneForTest().titleForTest();
        String summary = processing == null ? "" : processing.workspacePaneForTest().summaryForTest();
        String elapsed = text("#processing-total-elapsed");
        shot("011-processing-complete");
        record("011-layout", "titled=" + titled + " title=" + title + " summary=" + summary + " elapsed=" + elapsed);

        Button details = firstButton("Details");
        if (details == null) {
            details = firstPrefix("Warnings");
        }
        boolean dialog = false;
        String dialogTitle = "";
        if (details != null) {
            onFx(details::fire);
            pulse();
            Stage dialogStage = javafx.stage.Window.getWindows().stream()
                    .filter(Stage.class::isInstance)
                    .map(Stage.class::cast)
                    .filter(s -> "Processing Details".equals(s.getTitle()))
                    .findFirst()
                    .orElse(null);
            dialog = dialogStage != null;
            if (dialogStage != null) {
                dialogTitle = dialogStage.getTitle() + " " + (int) dialogStage.getWidth() + "x" + (int) dialogStage.getHeight();
                shotWindow(dialogStage, "012-details-dialog");
                onFx(dialogStage::close);
            }
        }
        shot("012-details");
        record("012-details", "opened=" + dialog + " " + dialogTitle + " button=" + (details == null ? "none" : details.getText()));

        open(WorkspaceRoute.SIGNALS);
        SignalsController signals = signals();
        onFx(signals.browserPaneForTest().selectAllButtonForTest()::fire);
        boolean toolbar = waitUntil(15_000, () -> signals().workspacePaneForTest().toolbarStructureForTest().contains("toolbar=")
                && !signals().workspacePaneForTest().toolbarStructureForTest().contains("toolbar=0"));
        var field = signals().workspacePaneForTest().indexFieldForTest();
        onFx(() -> field.setText("abc"));
        onFx(signals().workspacePaneForTest().indexGoButtonForTest()::fire);
        pulse();
        String invalid = feedback();
        shot("017-invalid-index");
        onFx(() -> field.setText("2"));
        onFx(signals().workspacePaneForTest().indexGoButtonForTest()::fire);
        pulse();
        onFx(() -> signals().workspacePaneForTest().selectNavigateTimeForTest());
        onFx(() -> field.setText("1"));
        onFx(signals().workspacePaneForTest().indexGoButtonForTest()::fire);
        pulse();
        shot("016-navigate");
        record("016-017", "toolbarReady=" + toolbar + " invalid=" + invalid
                + " after=" + signals().workspacePaneForTest().toolbarStructureForTest());

        open(WorkspaceRoute.GRAPHS);
        Button showAll = button("#showAllButton");
        if (showAll != null) {
            onFx(showAll::fire);
        }
        pulse();
        ToggleButton overlay = toggle("#overlayLayoutButton");
        if (overlay != null) {
            onFx(overlay::fire);
        }
        pulse();
        shot("018-overlay");
        String overlayStatus = text(".graph-status");
        ToggleButton stacked = toggle("#stackedLayoutButton");
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
        selectInnerTab("Diagnostics");
        pulse();
        shot("020-engineering");
        record("018-020", "showAll=" + (showAll != null) + " overlaySelected=" + (overlay != null && overlay.isSelected())
                + " stackedSelected=" + (stacked != null && stacked.isSelected())
                + " status=" + overlayStatus + " keys=" + service.signalsModel().workspaceKeys().size());
        flush();
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
        if (!latch.await(60, TimeUnit.SECONDS) || error.get() != null) {
            throw new IllegalStateException("shell failed", error.get());
        }
        pulse();
    }

    private static ProcessingController processing() {
        Object controller = shell.controllersForTest().get(NavigationPage.PROCESSING);
        return controller instanceof ProcessingController processing ? processing : null;
    }

    private static SignalsController signals() {
        return (SignalsController) shell.controllersForTest().get(NavigationPage.SIGNALS);
    }

    private static void open(WorkspaceRoute route) {
        onFx(() -> shell.openRoute(route));
        pulse();
    }

    private static void selectTab(String title) {
        onFx(() -> {
            for (Node node : scene.getRoot().lookupAll(".tab-pane")) {
                if (node instanceof TabPane tabs) {
                    tabs.getTabs().stream().filter(t -> title.equals(t.getText())).findFirst()
                            .ifPresent(t -> tabs.getSelectionModel().select(t));
                }
            }
        });
    }

    private static void selectInnerTab(String title) {
        selectTab(title);
    }

    private static String feedback() {
        StringBuilder text = new StringBuilder();
        for (Node node : scene.getRoot().lookupAll(".nav-feedback")) {
            if (node instanceof Label label && label.getText() != null) {
                text.append(label.getText()).append(' ');
            }
        }
        return text.toString().trim();
    }

    private static Button firstButton(String label) {
        for (Node node : scene.getRoot().lookupAll(".button")) {
            if (node instanceof Button button && label.equals(button.getText()) && button.isVisible()) {
                return button;
            }
        }
        return null;
    }

    private static Button firstPrefix(String prefix) {
        for (Node node : scene.getRoot().lookupAll(".button")) {
            if (node instanceof Button button && button.getText() != null && button.getText().startsWith(prefix) && button.isVisible()) {
                return button;
            }
        }
        return null;
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
        if (node instanceof Label label) {
            return label.getText() == null ? "" : label.getText().replace('\n', ' ');
        }
        return "";
    }

    private static void shot(String name) {
        onFx(() -> writeShot(scene.snapshot(null), name));
    }

    private static void shotWindow(Stage dialog, String name) {
        onFx(() -> {
            if (dialog.getScene() != null) {
                writeShot(dialog.getScene().snapshot(null), name);
            }
        });
    }

    private static void writeShot(WritableImage image, String name) {
        try {
            if (image != null) {
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", SHOTS.resolve(name + ".png").toFile());
            }
        } catch (Exception ignored) {
            // missing screenshot stays visible as absent evidence
        }
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
            if (!latch.await(5, TimeUnit.SECONDS)) {
                return false;
            }
            if (Boolean.TRUE.equals(value.get())) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    private static void pulse() {
        try {
            waitUntil(800, () -> true);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void onFx(Runnable action) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (error.get() != null) {
            throw new IllegalStateException(error.get());
        }
    }

    private static void record(String id, String evidence) {
        ROWS.add(id + "\t" + Instant.now() + "\t" + evidence);
        flush();
    }

    private static void flush() {
        try {
            Files.writeString(RESULTS, String.join(System.lineSeparator(), ROWS) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // next flush retries
        }
    }
}
