package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.DesktopLogging;
import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.UnsavedLeaveChoice;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fresh JVM after the second processing run, to see whether the committed signal store reopens. */
@Tag("live-e2e")
class LiveReleaseE2ERestartCheckTest {
    private static DesktopAppService service;
    private static MainShellController shell;

    @BeforeAll
    static void startFx() throws Exception {
        System.setProperty("stellarai.test.isolate", "false");
        System.setProperty("stellarai.home", "D:\\StellarAI-E2E\\AppHome");
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        AnalysisStorageRoots.clear();
        CountDownLatch started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException already) { started.countDown(); }
        assertTrue(started.await(15, TimeUnit.SECONDS));
    }

    @AfterAll
    static void shutdown() {
        try {
            if (shell != null) Platform.runLater(shell::dispose);
            if (service != null) service.shutdown();
        } catch (RuntimeException ignored) { }
        DesktopLogging.closeForTests();
        AnalysisStorageRoots.clear();
    }

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void catalogAfterSecondRun() throws Exception {
        System.setProperty("stellarai.test.isolate", "false");
        System.setProperty("stellarai.home", "D:\\StellarAI-E2E\\AppHome");
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        AnalysisStorageRoots.clear();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Stage> stage = new AtomicReference<>();
        AtomicReference<Scene> scene = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                service = DesktopAppService.forLocalProduction(new DesktopSession());
                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                Parent root = loader.load();
                shell = loader.getController();
                shell.setNewProjectNameForTest("E2E_Project_A");
                shell.setUnsavedLeaveDecisionForTest(intent -> UnsavedLeaveChoice.DISCARD);
                shell.init(service);
                Scene shown = new Scene(root, 1366, 768);
                shown.getStylesheets().add(MainShellController.class.getResource("/css/stellar-desktop.css").toExternalForm());
                Stage window = new Stage();
                window.setScene(shown);
                window.setTitle(DesktopVersion.windowTitle());
                window.show();
                stage.set(window);
                scene.set(shown);
                if (!"1001".equals(service.currentProjectId())) {
                    shell.requestOpenSavedProject("1001");
                }
                shell.openRoute(WorkspaceRoute.SIGNALS);
            } catch (Throwable t) { error.set(t); }
            finally { latch.countDown(); }
        });
        assertTrue(latch.await(60, TimeUnit.SECONDS), "launch timed out");
        if (error.get() != null) throw new IllegalStateException("launch failed", error.get());
        Thread.sleep(1500);
        AtomicReference<String> line = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                SignalsController signals = (SignalsController) shell.controllersForTest().get(NavigationPage.SIGNALS);
                int rows = signals == null ? -1 : signals.browserPaneForTest().catalogTable().getItems().size();
                line.set("project=" + shell.projectNameChromeForTest()
                        + " id=" + service.currentProjectId()
                        + " catalog=" + service.signalsModel().catalog().size()
                        + " rows=" + rows
                        + " sources=" + service.workspace().measurementFiles().size());
                if (scene.get() != null) {
                    try {
                        ImageIO.write(SwingFXUtils.fromFXImage(scene.get().snapshot(null), null), "png",
                                Path.of("D:/Reports/StellarAI_1.0.0_E2E/02_Evidence/Screenshots/030-second-restart.png").toFile());
                    } catch (Exception ignored) { }
                }
            } finally { done.countDown(); }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        Files.writeString(Path.of("D:/Reports/StellarAI_1.0.0_E2E/03_TestResults/LiveE2E/restart-check.txt"),
                Instant.now() + " " + line.get());
    }
}
