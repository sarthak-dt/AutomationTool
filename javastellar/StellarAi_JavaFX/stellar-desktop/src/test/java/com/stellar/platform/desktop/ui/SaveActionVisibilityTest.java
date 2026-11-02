package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveActionVisibilityTest {

    @TempDir Path tempDir;
    private DesktopAppService service;

    @BeforeAll
    static void initJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
            service = null;
        }
    }

    @Test
    @Timeout(30)
    void saveStaysVisibleAtSupportedWidthsIncludingWhenSaved() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                System.setProperty("stellarai.home", tempDir.resolve("home").toString());
                service = new DesktopAppService(
                        new DesktopSession(),
                        new SqliteProjectMetadataStore(tempDir.resolve("save-visible.db")));
                service.newProject("Save Visible");
                assertTrue(service.saveProject().saved());
                assertTrue(service.saveStatusLabel().startsWith("Saved"));

                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                Parent root = loader.load();
                MainShellController controller = loader.getController();
                controller.bindServiceForChromeTest(service);

                assertTrue(controller.saveActionVisibleForTest(), "hidden Save is a regression");
                Button save = controller.saveProjectButtonForTest();
                assertEquals("Save", save.getText());
                assertFalse(save.isDisabled());
                HBox actions = controller.headerActionsForTest();
                assertEquals(javafx.scene.layout.Priority.NEVER, javafx.scene.layout.HBox.getHgrow(actions));

                Scene scene = new Scene(root, 1366, 768);
                scene.getStylesheets().add(
                        MainShellController.class.getResource("/css/stellar-desktop.css").toExternalForm());
                for (double[] size : new double[][] {
                        {1920, 1080}, {1600, 900}, {1536, 832}, {1366, 768}}) {
                    root.resize(size[0], size[1]);
                    root.applyCss();
                    root.layout();
                    assertTrue(controller.saveActionVisibleForTest(),
                            "Save must remain visible at " + (int) size[0] + "x" + (int) size[1]);
                    Bounds bounds = save.localToScene(save.getBoundsInLocal());
                    assertTrue(bounds.getWidth() > 0, "Save has no width at " + (int) size[0]);
                    assertTrue(bounds.getMinX() >= 0, "Save clipped on the left at " + (int) size[0]);
                    assertTrue(bounds.getMaxX() <= size[0] + 1,
                            "Save clipped on the right at " + (int) size[0] + ": maxX=" + bounds.getMaxX());
                }
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }
}
