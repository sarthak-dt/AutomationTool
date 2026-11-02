package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.AutomotiveSignalCanvas;
import com.stellar.platform.desktop.ui.graph.EChartsAnalyticsPane;
import com.stellar.platform.desktop.ui.graph.EngineeringAnalysisPane;
import com.stellar.platform.desktop.ui.graph.map.MapRoutePane;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** One cold Graph load, optionally after background class initialization. */
@Timeout(value = 4, unit = TimeUnit.MINUTES)
class P0Phase6ColdPreloadTest {

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @Test
    void coldGraphLoad() throws Exception {
        boolean preload = Boolean.getBoolean("phase6.preload");
        long preloadMs = 0;
        if (preload) {
            long started = System.nanoTime();
            Thread loader = new Thread(() -> {
                for (Class<?> type : List.of(
                        GraphController.class,
                        AutomotiveSignalCanvas.class,
                        EngineeringAnalysisPane.class,
                        EChartsAnalyticsPane.class,
                        MapRoutePane.class)) {
                    type.getDeclaredMethods();
                }
            }, "phase6-class-preload");
            loader.start();
            loader.join(10_000);
            preloadMs = (System.nanoTime() - started) / 1_000_000L;
        }
        AtomicLong handler = new AtomicLong();
        AtomicLong layout = new AtomicLong();
        CountDownLatch done = new CountDownLatch(1);
        JavaFxTestSupport.runOnFx("cold", () -> {
            StackPane host = new StackPane();
            Scene scene = new Scene(host, 1366, 768);
            var css = GraphController.class.getResource("/css/stellar-desktop.css");
            if (css != null) {
                scene.getStylesheets().add(css.toExternalForm());
            }
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            scene.addPostLayoutPulseListener(() -> {
                layout.set(System.nanoTime());
                done.countDown();
            });
            long start = System.nanoTime();
            FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
            Parent root = loader.load();
            GraphController graph = loader.getController();
            host.getChildren().setAll(root);
            graph.onShow();
            handler.set(System.nanoTime() - start);
            layout.compareAndSet(0, System.nanoTime());
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "cold frame");
        long layoutMs = Math.max(0, (layout.get() - (System.nanoTime() - handler.get())) / 1_000_000L);
        String line = "preload=" + preload
                + " preload-ms=" + preloadMs
                + " cold-handler-ms=" + (handler.get() / 1_000_000L)
                + " note=single-cold-open";
        Files.writeString(Path.of("target", "p0-phase6-cold.txt"), line + System.lineSeparator());
        assertTrue(handler.get() > 0);
        // layoutMs is diagnostic only; the file records the handler.
        if (layoutMs < 0) {
            Files.writeString(Path.of("target", "p0-phase6-cold.txt"), line + " layout-negative" + System.lineSeparator());
        }
    }
}
