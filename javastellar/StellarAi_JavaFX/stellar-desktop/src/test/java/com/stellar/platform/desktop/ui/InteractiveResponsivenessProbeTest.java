package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane;
import com.stellar.platform.desktop.ui.signals.SignalBrowserPane;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures production JavaFX interaction cost. Timing lines are evidence, not assertions
 * that a budget passed.
 */
class InteractiveResponsivenessProbeTest {

    private static final int CATALOG = 400;
    private static final int CHECKBOXES = 30;
    private static final Path OUT = Path.of("target", "responsiveness-probe.txt");

    private static final List<String> LINES = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
        LINES.add("env java=" + System.getProperty("java.version")
                + " os=" + System.getProperty("os.name")
                + " processors=" + Runtime.getRuntime().availableProcessors()
                + " maxHeap=" + Runtime.getRuntime().maxMemory());
    }

    @AfterAll
    static void writeReport() throws Exception {
        Files.createDirectories(OUT.getParent());
        Files.writeString(OUT, String.join(System.lineSeparator(), LINES) + System.lineSeparator());
    }

    @Test
    void graphLeaveBlocksTheFxThreadWhileAQueryIgnoresInterrupt() throws Exception {
        long[] coldLoad = new long[8];
        long[] warmLoad = new long[8];
        long[] idleDispose = new long[8];
        for (int i = 0; i < coldLoad.length; i++) {
            final int cycle = i;
            JavaFxTestSupport.runOnFx("graph load " + cycle, () -> {
                long t0 = System.nanoTime();
                LoadedGraph loaded = loadGraph();
                Stage stage = show(loaded.root());
                coldLoad[cycle] = System.nanoTime() - t0;
                stage.close();
                long t1 = System.nanoTime();
                loaded.graph().dispose();
                idleDispose[cycle] = System.nanoTime() - t1;
            });
            JavaFxTestSupport.runOnFx("graph warm " + cycle, () -> {
                long t0 = System.nanoTime();
                LoadedGraph loaded = loadGraph();
                warmLoad[cycle] = System.nanoTime() - t0;
                loaded.graph().dispose();
            });
        }
        note("graph-fxml-cold-ms", coldLoad);
        note("graph-fxml-warm-ms", warmLoad);
        note("graph-dispose-idle-ms", idleDispose);

        JavaFxTestSupport.runOnFx("graph dispose while query ignores interrupt", () -> {
            GraphController graph = loadGraph().graph();
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
            assertTrue(running.await(5, TimeUnit.SECONDS));
            long t0 = System.nanoTime();
            graph.dispose();
            long blockedMs = (System.nanoTime() - t0) / 1_000_000L;
            release.set(true);
            LINES.add("graph-dispose-busy-ms=" + blockedMs + " warm=false fxThread=true");
        });
    }

    @Test
    void signalCheckboxHandlerOnAFourHundredSignalCatalog() throws Exception {
        long[] handler = new long[CHECKBOXES];
        long[] pulse = new long[CHECKBOXES];
        JavaFxTestSupport.runOnFx("checkbox catalog", () -> {
            SignalsScreenModel model = new SignalsScreenModel();
            InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("sierra.mf4", "sierra.dbc", CATALOG * 2);
            List<CanonicalSignal> samples = new ArrayList<>(CATALOG * 2);
            for (int i = 0; i < CATALOG; i++) {
                samples.add(sample(i, 1_000_000L));
                samples.add(sample(i, 2_000_000L));
            }
            store.accept(samples);
            model.replaceCatalog(store);
            SignalBrowserPane browser = new SignalBrowserPane();
            ActiveSignalWorkspacePane workspace = new ActiveSignalWorkspacePane();
            browser.setModel(model);
            workspace.setModel(model);
            browser.setAddToWorkspaceListener(entries -> {
                long t0 = System.nanoTime();
                workspace.addEntries(entries);
                browser.refreshFavoritePresentation();
                long done = System.nanoTime();
                int index = model.workspaceKeys().size() - 1;
                if (index >= 0 && index < handler.length) {
                    handler[index] = done - t0;
                    CountDownLatch pulseLatch = new CountDownLatch(1);
                    long posted = System.nanoTime();
                    Platform.runLater(() -> {
                        if (index < pulse.length) {
                            pulse[index] = System.nanoTime() - posted;
                        }
                        pulseLatch.countDown();
                    });
                }
            });
            HBox root = new HBox(browser, workspace);
            Stage stage = show(root);
            browser.resize(480, 768);
            workspace.resize(880, 768);
            root.applyCss();
            root.layout();
            List<CheckBox> boxes = new ArrayList<>();
            for (javafx.scene.Node node : browser.lookupAll(".browser-row-check")) {
                boxes.add((CheckBox) node);
            }
            int clicks = Math.min(CHECKBOXES, boxes.size());
            LINES.add("checkbox-visible-rows=" + boxes.size() + " catalog=" + CATALOG);
            for (int i = 0; i < clicks; i++) {
                boxes.get(i).fire();
            }
            stage.close();
            model.shutdown();
        });
        JavaFxTestSupport.drainFxPulse();
        note("checkbox-handler-ms", Arrays.copyOf(handler, lastPositive(handler)));
        note("checkbox-next-pulse-ms", Arrays.copyOf(pulse, lastPositive(pulse)));
    }

    private static int lastPositive(long[] values) {
        int n = 0;
        for (long value : values) {
            if (value > 0) {
                n++;
            }
        }
        return Math.max(n, 1);
    }

    private static CanonicalSignal sample(int index, long timestamp) {
        return new CanonicalSignal(
                "Signal" + index, "Msg", timestamp, 0x100L + (index % 40), 1,
                index, (long) index, "km/h", null, SourceReference.ofLine(1));
    }

    private record LoadedGraph(javafx.scene.Parent root, GraphController graph) {
    }

    private static LoadedGraph loadGraph() throws Exception {
        FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
        javafx.scene.Parent root = loader.load();
        return new LoadedGraph(root, loader.getController());
    }

    private static Stage show(javafx.scene.Parent root) {
        Scene scene = new Scene(root, 1366, 768);
        var css = GraphController.class.getResource("/css/stellar-desktop.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();
        return stage;
    }

    private static ExecutorService executor(GraphController graph, String fieldName) throws Exception {
        Field field = GraphController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (ExecutorService) field.get(graph);
    }

    private static void note(String name, long[] nanos) {
        long[] ms = new long[nanos.length];
        for (int i = 0; i < nanos.length; i++) {
            ms[i] = nanos[i] / 1_000_000L;
        }
        Arrays.sort(ms);
        LINES.add(name
                + " n=" + ms.length
                + " p50=" + pct(ms, 50)
                + " p95=" + pct(ms, 95)
                + " p99=" + pct(ms, 99)
                + " max=" + ms[ms.length - 1]
                + " samples=" + Arrays.toString(ms));
    }

    private static long pct(long[] sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }
}
