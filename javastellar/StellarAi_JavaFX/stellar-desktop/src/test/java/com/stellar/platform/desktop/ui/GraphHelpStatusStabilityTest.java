package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.ui.graph.AutomotiveSignalCanvas;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Label;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphHelpStatusStabilityTest {

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(40)
    void pointerUpdatesDoNotRecreateOrRewriteHelpStatus() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
                loader.load();
                GraphController controller = loader.getController();
                Label status = (Label) loader.getNamespace().get("statusLabel");
                Label pointer = (Label) loader.getNamespace().get("liveCursorLabel");
                AutomotiveSignalCanvas canvas = (AutomotiveSignalCanvas) loader.getNamespace().get("graphCanvas");

                controller.applyGraphHelpForTest(3);
                String help = status.getText();
                assertEquals("3 track(s) · drag=pan · click=select sample · wheel=zoom · Shift+click=Cursor A · Ctrl+click=Cursor B", help);
                assertEquals(1, controller.graphHelpApplyCountForTest());

                controller.applyGraphHelpForTest(3);
                assertEquals(1, controller.graphHelpApplyCountForTest());
                assertSame(status, loader.getNamespace().get("statusLabel"));

                canvas.hoverTimestampProperty().set(278_855_136_000L);
                canvas.hoverPointProperty().set(new AutomotiveSignalCanvas.HoverPoint(
                        278_855_136_000L, "Vehicle speed", "km/h", 42.5));
                canvas.hoverPointProperty().set(new AutomotiveSignalCanvas.HoverPoint(
                        279_100_000_000L, "Engine speed", "rpm", 1800));
                assertTrue(pointer.getText().startsWith("Pointer:"));
                assertEquals(help, status.getText());
                assertEquals(1, controller.graphHelpApplyCountForTest());
                assertSame(status, loader.getNamespace().get("statusLabel"));

                controller.applyGraphHelpForTest(2);
                assertEquals("2 track(s) · drag=pan · click=select sample · wheel=zoom · Shift+click=Cursor A · Ctrl+click=Cursor B",
                        status.getText());
                assertEquals(2, controller.graphHelpApplyCountForTest());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }
}
