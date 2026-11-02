package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.testsupport.DesktopTestIsolation;
import com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 proof gate. No JFR and no background decode in this process, so the
 * numbers stay on the normal-use workload.
 */
@Timeout(value = 12, unit = TimeUnit.MINUTES)
class P0Phase6ArchitectureTest {

    private static final int CATALOG = 400;
    private static final int CYCLES = 25;
    private static final Path OUT = Path.of("target", "p0-phase6.txt");
    private static final List<String> LINES = new ArrayList<>();

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
        LINES.add("env java=" + System.getProperty("java.version")
                + " maxHeap=" + Runtime.getRuntime().maxMemory()
                + " prism=" + System.getProperty("prism.order")
                + " workload=normal-use-no-jfr-no-decode");
    }

    @AfterAll
    static void writeReport() throws Exception {
        Files.createDirectories(OUT.getParent());
        Files.writeString(OUT, String.join(System.lineSeparator(), LINES) + System.lineSeparator());
    }

    @Test
    void compareNavigationAndColumnArchitectures() throws Exception {
        Path temp = Files.createTempDirectory("p0-phase6");
        DesktopTestIsolation.isolateHome(temp.resolve("home"));
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        AtomicReference<GraphSignalSessionStore> storeRef = new AtomicReference<>();
        Stage[] shell = new Stage[1];
        GraphController[] current = new GraphController[1];
        try {
            service.newProject("p0-phase6");
            GraphSignalSessionStore store = GraphSignalSessionStore.create("sierra.mf4", "sierra.dbc");
            storeRef.set(store);
            List<CanonicalSignal> samples = new ArrayList<>(CATALOG * 2);
            for (int i = 0; i < CATALOG; i++) {
                samples.add(sample(i, 0L, i));
                samples.add(sample(i, 1_000_000_000L, i + 0.25));
            }
            store.accept(samples);
            service.signalsModel().replaceCatalog(store);
            List<SignalCatalogKey> selected = service.signalsModel().displayedCatalog().stream()
                    .limit(8).map(SignalCatalogEntry::key).toList();
            service.signalsModel().addToWorkspace(selected);

            StackPane host = new StackPane();
            JavaFxTestSupport.runOnFx("shell", () -> {
                shell[0] = stage(host);
                host.applyCss();
                host.layout();
            });

            long[] baseline = new long[CYCLES];
            long[] baselineHandler = new long[CYCLES];
            long[] baselineCss = new long[CYCLES];
            long[] populated = new long[CYCLES];
            int[] nodes = new int[CYCLES];
            for (int i = 0; i < CYCLES; i++) {
                int cycle = i;
                JavaFxTestSupport.runOnFx("baseline " + cycle, () -> {
                    disposeCurrent(current, host);
                    long start = System.nanoTime();
                    FrameWatch watch = FrameWatch.watch(shell[0].getScene(), 1);
                    watch.arm(start);
                    Loaded loaded = loadGraph();
                    loaded.graph().setAppService(service, null);
                    host.getChildren().setAll(loaded.root());
                    loaded.graph().onShow();
                    loaded.graph().refresh();
                    nodes[cycle] = count(loaded.root());
                    current[0] = loaded.graph();
                    long end = System.nanoTime();
                    baselineHandler[cycle] = end - start;
                    watch.handlerEnd(end);
                });
                FrameWatch.Stamps stamps = FrameWatch.awaitLatest();
                baselineCss[cycle] = Math.max(0, stamps.postLayout - stamps.handlerEnd);
                baseline[cycle] = Math.max(0, stamps.painted - stamps.start);
                long popStart = System.nanoTime();
                assertTrue(awaitPopulated(current[0], 4), "baseline waveform");
                populated[cycle] = System.nanoTime() - popStart;
            }
            note("baseline-layout-complete-ms", baseline);
            note("baseline-handler-ms", baselineHandler);
            note("baseline-css-layout-ms", baselineCss);
            note("baseline-populated-ms", populated);
            noteWarm("baseline-layout-complete-warm-ms", baseline);
            noteWarm("baseline-handler-warm-ms", baselineHandler);
            noteWarm("baseline-css-layout-warm-ms", baselineCss);
            LINES.add("baseline-cold-layout-complete-ms=" + millis(baseline[0])
                    + " handler-ms=" + millis(baselineHandler[0])
                    + " css-layout-ms=" + millis(baselineCss[0])
                    + " populated-ms=" + millis(populated[0])
                    + " nodes=" + nodes[0]);
            LINES.add("baseline-warm-nodes=" + nodes[CYCLES - 1]);

            if (current[0] != null) {
                long[] legend = interaction(shell[0].getScene(), 40,
                        () -> current[0].legendToggleForTest(selected.get(0)));
                note("legend-layout-complete-ms", legend);
            }

            long[] feedback = new long[CYCLES];
            long[] interactive = new long[CYCLES];
            long[] interactiveHandler = new long[CYCLES];
            long[] wave = new long[CYCLES];
            for (int i = 0; i < CYCLES; i++) {
                int cycle = i;
                JavaFxTestSupport.runOnFx("feedback " + cycle, () -> {
                    disposeCurrent(current, host);
                    long start = System.nanoTime();
                    FrameWatch watch = FrameWatch.watch(shell[0].getScene(), 1);
                    watch.arm(start);
                    Label opening = new Label("Opening Graph…");
                    opening.setStyle("-fx-font-size: 16px;");
                    host.getChildren().setAll(opening);
                    watch.handlerEnd(System.nanoTime());
                });
                feedback[cycle] = Math.max(0, FrameWatch.awaitLatest().painted - FrameWatch.latestStart());
                JavaFxTestSupport.runOnFx("interactive " + cycle, () -> {
                    long start = System.nanoTime();
                    FrameWatch watch = FrameWatch.watch(shell[0].getScene(), 1);
                    watch.arm(start);
                    Loaded loaded = loadGraph();
                    loaded.graph().setAppService(service, null);
                    host.getChildren().setAll(loaded.root());
                    loaded.graph().onShow();
                    loaded.graph().refresh();
                    current[0] = loaded.graph();
                    long end = System.nanoTime();
                    interactiveHandler[cycle] = end - start;
                    watch.handlerEnd(end);
                });
                FrameWatch.Stamps stamps = FrameWatch.awaitLatest();
                interactive[cycle] = Math.max(0, stamps.painted - stamps.start);
                long popStart = System.nanoTime();
                assertTrue(awaitPopulated(current[0], 4), "progressive waveform");
                wave[cycle] = System.nanoTime() - popStart;
            }
            note("feedback-layout-complete-ms", feedback);
            noteWarm("feedback-layout-complete-warm-ms", feedback);
            note("interactive-after-feedback-ms", interactive);
            noteWarm("interactive-after-feedback-warm-ms", interactive);
            noteWarm("interactive-handler-warm-ms", interactiveHandler);
            note("interactive-populated-ms", wave);

            long[] shellLayout = new long[10];
            long[] shellHandler = new long[10];
            int[] shellNodes = new int[10];
            long[] inspectorLayout = new long[10];
            for (int i = 0; i < 10; i++) {
                int cycle = i;
                JavaFxTestSupport.runOnFx("shell-first " + cycle, () -> {
                    disposeCurrent(current, host);
                    long start = System.nanoTime();
                    FrameWatch watch = FrameWatch.watch(shell[0].getScene(), 1);
                    watch.arm(start);
                    Loaded loaded = loadGraph();
                    Node inspector = loaded.root().lookup("#inspectorPanel");
                    Parent parent = inspector == null ? null : inspector.getParent();
                    if (parent instanceof Pane pane && inspector != null) {
                        pane.getChildren().remove(inspector);
                    }
                    loaded.graph().setAppService(service, null);
                    host.getChildren().setAll(loaded.root());
                    loaded.graph().onShow();
                    loaded.graph().refresh();
                    shellNodes[cycle] = count(loaded.root());
                    current[0] = loaded.graph();
                    inspectorHold[0] = inspector;
                    inspectorParent[0] = parent;
                    long end = System.nanoTime();
                    shellHandler[cycle] = end - start;
                    watch.handlerEnd(end);
                });
                FrameWatch.Stamps first = FrameWatch.awaitLatest();
                shellLayout[cycle] = Math.max(0, first.painted - first.start);
                JavaFxTestSupport.runOnFx("reattach inspector " + cycle, () -> {
                    long start = System.nanoTime();
                    FrameWatch watch = FrameWatch.watch(shell[0].getScene(), 1);
                    watch.arm(start);
                    if (inspectorParent[0] instanceof Pane pane && inspectorHold[0] != null
                            && inspectorHold[0].getParent() == null) {
                        pane.getChildren().add(inspectorHold[0]);
                    }
                    watch.handlerEnd(System.nanoTime());
                });
                inspectorLayout[cycle] = Math.max(0,
                        FrameWatch.awaitLatest().painted - FrameWatch.latestStart());
            }
            note("shell-without-inspector-layout-ms", shellLayout);
            note("shell-without-inspector-handler-ms", shellHandler);
            note("inspector-reattach-layout-ms", inspectorLayout);
            LINES.add("shell-without-inspector-nodes=" + shellNodes[shellNodes.length - 1]);
            LINES.add("heap-after-graph-cycles=" + usedHeap());

            columnArchitectures();
            LINES.add("heap-before-diagnostic-gc=" + usedHeap());
            System.gc();
            Thread.sleep(200);
            System.gc();
            LINES.add("heap-after-diagnostic-gc=" + usedHeap()
                    + " note=diagnostic-gc-in-test-process-only");
        } finally {
            JavaFxTestSupport.runOnFx("close", () -> {
                if (current[0] != null) {
                    current[0].dispose();
                }
                if (shell[0] != null) {
                    shell[0].close();
                }
            });
            service.shutdown();
            GraphSignalSessionStore open = storeRef.get();
            if (open != null) {
                try {
                    open.close();
                } catch (RuntimeException ignored) {
                    // The model may already have closed this session.
                }
            }
            DesktopTestIsolation.resetProcessState();
        }
    }

    private static final Node[] inspectorHold = new Node[1];
    private static final Parent[] inspectorParent = new Parent[1];

    private static void columnArchitectures() throws Exception {
        SignalsScreenModel model = new SignalsScreenModel();
        model.replaceCatalog(columnCatalog());
        ActiveSignalWorkspacePane workspace = new ActiveSignalWorkspacePane();
        workspace.setModel(model);
        List<SignalCatalogKey> keys = model.displayedCatalog().stream().limit(128)
                .map(SignalCatalogEntry::key).toList();
        Stage[] shown = new Stage[1];
        JavaFxTestSupport.runOnFx("columns", () -> shown[0] = stage(workspace));
        try {
            long[] full = new long[5];
            long[] fullHandler = new long[5];
            for (int i = 0; i < 5; i++) {
                int cycle = i;
                JavaFxTestSupport.runOnFx("clear full " + cycle, () -> {
                    if (!model.workspaceKeys().isEmpty()) {
                        workspace.removePhysicalKeys(List.copyOf(model.workspaceKeys()));
                    }
                });
                JavaFxTestSupport.drainFxPulse();
                JavaFxTestSupport.runOnFx("full " + cycle, () -> {
                    long start = System.nanoTime();
                    FrameWatch watch = FrameWatch.watch(shown[0].getScene(), 1);
                    watch.arm(start);
                    workspace.addPhysicalKeys(keys);
                    long end = System.nanoTime();
                    fullHandler[cycle] = end - start;
                    watch.handlerEnd(end);
                });
                full[cycle] = Math.max(0, FrameWatch.awaitLatest().painted - FrameWatch.latestStart());
            }
            note("select-all-128-layout-ms", full);
            note("select-all-128-handler-ms", fullHandler);
            assertEquals(128, signalColumns(workspace));

            long[] firstBatch = new long[5];
            long[] complete = new long[5];
            for (int i = 0; i < 5; i++) {
                int cycle = i;
                JavaFxTestSupport.runOnFx("clear batch " + cycle, () -> {
                    if (!model.workspaceKeys().isEmpty()) {
                        workspace.removePhysicalKeys(List.copyOf(model.workspaceKeys()));
                    }
                });
                JavaFxTestSupport.drainFxPulse();
                long started = System.nanoTime();
                for (int from = 0; from < keys.size(); from += 24) {
                    int startIndex = from;
                    List<SignalCatalogKey> slice = keys.subList(startIndex, Math.min(keys.size(), startIndex + 24));
                    JavaFxTestSupport.runOnFx("batch " + cycle + " " + startIndex, () -> {
                        long start = System.nanoTime();
                        FrameWatch watch = FrameWatch.watch(shown[0].getScene(), 1);
                        watch.arm(start);
                        workspace.addPhysicalKeys(slice);
                        watch.handlerEnd(System.nanoTime());
                    });
                    long layout = Math.max(0, FrameWatch.awaitLatest().painted - FrameWatch.latestStart());
                    if (startIndex == 0) {
                        firstBatch[cycle] = layout;
                    }
                }
                complete[cycle] = System.nanoTime() - started;
            }
            note("batch24-first-layout-ms", firstBatch);
            note("batch24-all-128-wall-ms", complete);
            assertEquals(128, signalColumns(workspace));
            assertEquals(keys, signalColumnKeys(workspace));
            AtomicInteger rows = new AtomicInteger();
            JavaFxTestSupport.runOnFx("rows", () -> rows.set(workspace.table().getItems().size()));
            for (int i = 0; i < 20 && rows.get() == 0; i++) {
                JavaFxTestSupport.drainFxPulse();
                JavaFxTestSupport.runOnFx("rows", () -> rows.set(workspace.table().getItems().size()));
            }
            LINES.add("batch-table-rows=" + rows.get());
            assertTrue(rows.get() > 0, "aligned rows");
            AtomicReference<String> firstCell = new AtomicReference<>();
            AtomicReference<String> lastCell = new AtomicReference<>();
            JavaFxTestSupport.runOnFx("cells", () -> {
                var row = workspace.table().getItems().get(0);
                var columns = workspace.table().getColumns();
                firstCell.set(String.valueOf(columns.get(3).getCellData(0)));
                lastCell.set(String.valueOf(columns.get(columns.size() - 1).getCellData(0)));
                LINES.add("batch-row0-timestamp=" + row.timestampNanos());
            });
            LINES.add("batch-first-signal-cell=" + firstCell.get());
            LINES.add("batch-last-signal-cell=" + lastCell.get());
            assertTrue(firstCell.get() != null && !firstCell.get().isBlank());
            assertTrue(lastCell.get() != null && !lastCell.get().isBlank());
        } finally {
            JavaFxTestSupport.runOnFx("close columns", () -> shown[0].close());
            model.shutdown();
        }
    }

    private static int signalColumns(ActiveSignalWorkspacePane workspace) throws Exception {
        AtomicInteger size = new AtomicInteger();
        JavaFxTestSupport.runOnFx("count", () -> {
            int signals = 0;
            for (TableColumn<?, ?> column : workspace.table().getColumns()) {
                if (column.getUserData() instanceof SignalCatalogKey) {
                    signals++;
                }
            }
            size.set(signals);
        });
        return size.get();
    }

    private static List<SignalCatalogKey> signalColumnKeys(ActiveSignalWorkspacePane workspace) throws Exception {
        AtomicReference<List<SignalCatalogKey>> found = new AtomicReference<>(List.of());
        JavaFxTestSupport.runOnFx("keys", () -> {
            List<SignalCatalogKey> keys = new ArrayList<>();
            for (TableColumn<?, ?> column : workspace.table().getColumns()) {
                if (column.getUserData() instanceof SignalCatalogKey key) {
                    keys.add(key);
                }
            }
            found.set(keys);
        });
        return found.get();
    }

    private static long[] interaction(Scene scene, int count, Runnable action) throws Exception {
        long[] presented = new long[count];
        for (int i = 0; i < count; i++) {
            JavaFxTestSupport.runOnFx("legend " + i, () -> {
                long start = System.nanoTime();
                FrameWatch watch = FrameWatch.watch(scene, 1);
                watch.arm(start);
                action.run();
                watch.handlerEnd(System.nanoTime());
            });
            presented[i] = Math.max(0, FrameWatch.awaitLatest().painted - FrameWatch.latestStart());
        }
        return presented;
    }

    private static void disposeCurrent(GraphController[] current, StackPane host) {
        if (current[0] != null) {
            current[0].dispose();
            current[0] = null;
        }
        host.getChildren().clear();
    }

    private static Loaded loadGraph() throws Exception {
        FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
        Parent root = loader.load();
        return new Loaded(root, loader.getController());
    }

    private record Loaded(Parent root, GraphController graph) {
    }

    private static Stage stage(Parent root) {
        Scene scene = new Scene(root, 1366, 768);
        var css = GraphController.class.getResource("/css/stellar-desktop.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        return stage;
    }

    private static boolean awaitPopulated(GraphController graph, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            AtomicBoolean ready = new AtomicBoolean();
            JavaFxTestSupport.runOnFx("populated", () -> {
                int series = graph.canvasForTest() == null ? 0 : graph.canvasForTest().seriesCountForTest();
                String status = graph.graphStatusForTest();
                ready.set(series > 0 && (status == null || !status.startsWith("Loading")));
            });
            if (ready.get()) {
                return true;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        return false;
    }

    private static InMemorySignalCatalogStore columnCatalog() {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("sierra.mf4", "sierra.dbc", 256);
        List<CanonicalSignal> samples = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            samples.add(sample(i, 1_000_000L, i));
            samples.add(sample(i, 2_000_000L, i + 0.5));
        }
        store.accept(samples);
        return store;
    }

    private static CanonicalSignal sample(int index, long timestamp, double value) {
        return new CanonicalSignal(
                "Signal" + index, "Msg", timestamp, 0x100L + (index % 40), 1,
                value, (long) value, "km/h", null, SourceReference.ofLine(1));
    }

    private static int count(Node node) {
        int total = 1;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                total += count(child);
            }
        }
        return total;
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void note(String name, long[] nanos) {
        LINES.add(name + " " + stats(nanos, 0));
    }

    private static void noteWarm(String name, long[] nanos) {
        LINES.add(name + " " + stats(nanos, 5));
    }

    private static String stats(long[] nanos, int from) {
        long[] sorted = new long[nanos.length - from];
        for (int i = from; i < nanos.length; i++) {
            sorted[i - from] = nanos[i] / 1_000_000L;
        }
        Arrays.sort(sorted);
        return "n=" + sorted.length
                + " p50=" + pct(sorted, 50)
                + " p95=" + pct(sorted, 95)
                + " p99=" + pct(sorted, 99)
                + " max=" + sorted[sorted.length - 1];
    }

    private static long pct(long[] sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static final class FrameWatch {
        private static final AtomicReference<FrameWatch> LATEST = new AtomicReference<>();
        private final Scene scene;
        private final CountDownLatch done = new CountDownLatch(1);
        private final Stamps stamps = new Stamps();
        private Runnable postListener;

        private FrameWatch(Scene scene) {
            this.scene = scene;
        }

        static FrameWatch watch(Scene scene, int ignored) {
            FrameWatch watch = new FrameWatch(scene);
            LATEST.set(watch);
            return watch;
        }

        static Stamps awaitLatest() throws Exception {
            FrameWatch watch = LATEST.get();
            assertTrue(watch.done.await(8, TimeUnit.SECONDS), "frame");
            return watch.stamps;
        }

        static long latestStart() {
            return LATEST.get().stamps.start;
        }

        void arm(long start) {
            stamps.start = start;
            postListener = () -> {
                stamps.postLayout = System.nanoTime();
                stamps.painted = stamps.postLayout;
                scene.removePostLayoutPulseListener(postListener);
                done.countDown();
            };
            scene.addPostLayoutPulseListener(postListener);
        }

        void handlerEnd(long nanos) {
            stamps.handlerEnd = nanos;
        }

        static final class Stamps {
            long start;
            long handlerEnd;
            long postLayout;
            long painted;
        }
    }
}
