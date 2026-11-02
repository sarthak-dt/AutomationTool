package com.stellar.platform.desktop.ui;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every view document the desktop ships must load with its controller wired. A structural mistake
 * in one FXML otherwise only surfaces when a user happens to navigate there.
 */
class DesktopViewDocumentsLoadTest {

    private static final List<String> VIEWS = List.of(
            "/fxml/main-shell.fxml",
            "/fxml/home-dashboard-view.fxml",
            "/fxml/dashboard-view.fxml",
            "/fxml/project-files-view.fxml",
            "/fxml/processing-view.fxml",
            "/fxml/signals-view.fxml",
            "/fxml/graph-view.fxml");

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        CountDownLatch configured = new CountDownLatch(1);
        Platform.runLater(() -> {
            Platform.setImplicitExit(false);
            configured.countDown();
        });
        assertTrue(configured.await(5, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(90)
    void allShippedViewDocumentsLoadWithTheirControllers() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                for (String view : VIEWS) {
                    var resource = DesktopViewDocumentsLoadTest.class.getResource(view);
                    assertNotNull(resource, "missing view document: " + view);
                    FXMLLoader loader = new FXMLLoader(resource);
                    Parent root = loader.load();
                    assertNotNull(root, view + " produced no root node");
                    assertNotNull(loader.getController(), view + " has no controller");
                }
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(80, TimeUnit.SECONDS), "FXML loading timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @Test
    @Timeout(90)
    void projectsDashboardListsAdvertisedMeasurementFormats() throws Exception {
        AtomicReference<String> supported = new AtomicReference<>("");
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(DesktopViewDocumentsLoadTest.class.getResource("/fxml/dashboard-view.fxml"));
                Parent root = loader.load();
                StringBuilder text = new StringBuilder();
                collectSupportedLabels(root, text);
                supported.set(text.toString());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(80, TimeUnit.SECONDS), "FXML loading timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        String text = supported.get();
        assertTrue(text.contains("MF4") && text.contains("ASC") && text.contains("BLF"), text);
        assertTrue(text.contains("DAT") && text.contains("LOG"), text);
        assertFalse(text.toLowerCase().contains("experimental"), text);
    }

    private static void collectSupportedLabels(Node node, StringBuilder text) {
        if (node instanceof Label label && label.getStyleClass().contains("dashboard-supported-item")) {
            text.append(label.getText()).append(' ');
        }
        if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            collectSupportedLabels(scroll.getContent(), text);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectSupportedLabels(child, text);
            }
        }
    }
}
