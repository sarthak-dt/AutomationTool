package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.support.JavaFxTestSupport;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leaving Graph runs {@link GraphController#dispose()} on the JavaFX thread.
 * A query that does not observe interrupt must not freeze that thread for the old 2s join.
 */
class GraphFxThreadReleaseTest {

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @Test
    void disposeReturnsWhileAQueryIgnoresInterrupt() throws Exception {
        JavaFxTestSupport.runOnFx("dispose under a stuck graph query", () -> {
            FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
            loader.load();
            GraphController graph = loader.getController();
            CountDownLatch running = new CountDownLatch(3);
            AtomicBoolean release = new AtomicBoolean();
            for (String name : List.of("worker", "categoricalScan", "viewportScheduler")) {
                executor(graph, name).submit(() -> {
                    running.countDown();
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                    while (System.nanoTime() < until && !release.get()) {
                        try {
                            Thread.sleep(40);
                        } catch (InterruptedException ignored) {
                            Thread.interrupted();
                        }
                    }
                });
            }
            assertTrue(running.await(5, TimeUnit.SECONDS), "graph workers did not start");
            int before = FxExecutorRelease.abandonedPools();
            long started = System.nanoTime();
            graph.dispose();
            long blockedMs = (System.nanoTime() - started) / 1_000_000L;
            int abandoned = FxExecutorRelease.abandonedPools() - before;
            release.set(true);
            assertTrue(blockedMs < 250, "JavaFX thread blocked for " + blockedMs + " ms in GraphController.dispose");
            assertTrue(abandoned >= 1, "a stuck pool was treated as finished");
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (FxExecutorRelease.abandonedPools() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(40);
        }
        assertTrue(FxExecutorRelease.abandonedPools() == 0,
                "stuck graph pools did not terminate after cancellation");
    }

    private static ExecutorService executor(GraphController graph, String fieldName) throws Exception {
        Field field = GraphController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (ExecutorService) field.get(graph);
    }
}
