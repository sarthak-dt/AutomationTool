package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.testsupport.DesktopTestIsolation;
import com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane;
import com.stellar.platform.desktop.ui.signals.SignalBrowserPane;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TableColumn;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 timings. Distributions are evidence. They do not lower existing budgets.
 */
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class P0Phase3ResponsivenessTest {

    private static final int CATALOG = 400;
    private static final int REENTRY = 30;
    private static final Path OUT = Path.of("target", "p0-phase3.txt");
    private static final List<String> LINES = new ArrayList<>();

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
        LINES.add("env java=" + System.getProperty("java.version")
                + " maxHeap=" + Runtime.getRuntime().maxMemory());
        runtime("start");
    }

    @AfterAll
    static void writeReport() throws Exception {
        runtime("end");
        Files.createDirectories(OUT.getParent());
        Files.writeString(OUT, String.join(System.lineSeparator(), LINES) + System.lineSeparator());
    }

    @Test
    void middleColumnRemovalReusesSurvivingColumns() throws Exception {
        SignalsScreenModel model = new SignalsScreenModel();
        model.replaceCatalog(catalog(CATALOG));
        SignalBrowserPane browser = new SignalBrowserPane();
        ActiveSignalWorkspacePane workspace = new ActiveSignalWorkspacePane();
        browser.setModel(model);
        workspace.setModel(model);
        HBox root = new HBox(browser, workspace);
        Stage[] shown = new Stage[1];
        JavaFxTestSupport.runOnFx("show workspace", () -> {
            shown[0] = stage(root);
            browser.resize(480, 768);
            workspace.resize(880, 768);
            root.applyCss();
            root.layout();
        });
        List<SignalCatalogEntry> entries = model.displayedCatalog();
        long[] add = new long[100];
        for (int i = 0; i < add.length; i++) {
            int index = i;
            JavaFxTestSupport.runOnFx("add " + index, () -> {
                long t = System.nanoTime();
                workspace.addEntries(List.of(entries.get(index)));
                add[index] = System.nanoTime() - t;
            });
        }
        note("workspace-add-handler-ms", add);
        Map<SignalCatalogKey, TableColumn<?, ?>> before = columns(workspace);
        TableColumn<?, ?> kept = before.get(entries.get(0).key());
        TableColumn<?, ?> alsoKept = before.get(entries.get(90).key());
        long[] remove = new long[8];
        long[] removePulse = new long[8];
        for (int i = 0; i < remove.length; i++) {
            int cycle = i;
            int victim = 40 + cycle;
            SignalCatalogKey key = entries.get(victim).key();
            CountDownLatch frame = new CountDownLatch(1);
            long[] posted = new long[1];
            JavaFxTestSupport.runOnFx("remove " + victim, () -> {
                long t = System.nanoTime();
                workspace.removePhysicalKeys(List.of(key));
                remove[cycle] = System.nanoTime() - t;
                posted[0] = System.nanoTime();
                Platform.runLater(frame::countDown);
            });
            assertTrue(frame.await(5, TimeUnit.SECONDS), "remove frame " + victim);
            removePulse[cycle] = System.nanoTime() - posted[0];
        }
        note("workspace-middle-remove-handler-ms", remove);
        note("workspace-middle-remove-pulse-ms", removePulse);
        Map<SignalCatalogKey, TableColumn<?, ?>> after = columns(workspace);
        assertSame(kept, after.get(entries.get(0).key()), "first column was reconstructed");
        assertSame(alsoKept, after.get(entries.get(90).key()), "later column was reconstructed");
        assertEquals(92, after.size());
        JavaFxTestSupport.runOnFx("close workspace", () -> shown[0].close());
        model.shutdown();
        runtime("after-workspace");
    }

    @Test
    void graphReentryAfterExplorerAndCategoryChanges() throws Exception {
        Path temp = Files.createTempDirectory("p0-phase3");
        DesktopTestIsolation.isolateHome(temp.resolve("home"));
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        AtomicReference<GraphSignalSessionStore> storeRef = new AtomicReference<>();
        Stage[] shell = new Stage[1];
        GraphController[] current = new GraphController[1];
        AtomicBoolean read = new AtomicBoolean(true);
        AtomicInteger reads = new AtomicInteger();
        Thread background = new Thread(() -> {
            while (read.get()) {
                GraphSignalSessionStore open = storeRef.get();
                try {
                    if (open != null) {
                        open.snapshot();
                        reads.incrementAndGet();
                    }
                } catch (RuntimeException ex) {
                    break;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
            }
        }, "p0-catalog-read");
        background.setDaemon(true);
        try {
            service.newProject("p0-phase3");
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
            background.start();

            StackPane host = new StackPane();
            JavaFxTestSupport.runOnFx("scene", () -> {
                shell[0] = stage(host);
                host.applyCss();
                host.layout();
            });
            long[] css = new long[REENTRY];
            long[] layoutPass = new long[REENTRY];
            long[] refresh = new long[REENTRY];
            long[] blocked = new long[REENTRY];
            long[] pulse = new long[REENTRY];
            long[] populated = new long[REENTRY];
            int[] nodes = new int[REENTRY];
            int[] listeners = new int[REENTRY];
            Recording jfr = startJfr(Path.of("target", "p0-phase3-reentry.jfr"));
            for (int i = 0; i < REENTRY; i++) {
                int cycle = i;
                CountDownLatch frame = new CountDownLatch(1);
                long[] posted = new long[1];
                JavaFxTestSupport.runOnFx("reenter " + cycle, () -> {
                    long wall = System.nanoTime();
                    if (current[0] != null) {
                        current[0].dispose();
                        host.getChildren().clear();
                    }
                    long t = System.nanoTime();
                    Loaded loaded = loadGraph();
                    loaded.graph().setAppService(service, null);
                    host.getChildren().setAll(loaded.root());
                    long cssStart = System.nanoTime();
                    loaded.root().applyCss();
                    css[cycle] = System.nanoTime() - cssStart;
                    long layoutStart = System.nanoTime();
                    loaded.root().layout();
                    layoutPass[cycle] = System.nanoTime() - layoutStart;
                    if (cycle == 0) {
                        LINES.add("graph-node-split " + nodeSplit(loaded.graph()));
                    }
                    loaded.graph().onShow();
                    long refreshStart = System.nanoTime();
                    loaded.graph().refresh();
                    refresh[cycle] = System.nanoTime() - refreshStart;
                    blocked[cycle] = System.nanoTime() - wall;
                    nodes[cycle] = host.lookupAll("*").size();
                    listeners[cycle] = listenerCount(loaded.graph());
                    current[0] = loaded.graph();
                    posted[0] = System.nanoTime();
                    Platform.runLater(frame::countDown);
                });
                assertTrue(frame.await(5, TimeUnit.SECONDS), "frame " + cycle);
                pulse[cycle] = System.nanoTime() - posted[0];
                long pop = System.nanoTime();
                awaitPopulated(current[0], 4);
                populated[cycle] = System.nanoTime() - pop;
                service.metadataStore().listProjects();
            }
            stopJfr(jfr, "reentry");
            note("graph-apply-css-ms", css);
            note("graph-layout-ms", layoutPass);
            note("graph-refresh-ms", refresh);
            note("graph-fx-blocked-ms", blocked);
            note("graph-first-pulse-ms", pulse);
            note("graph-populated-ms", populated);
            long[] visible = new long[REENTRY];
            for (int i = 0; i < REENTRY; i++) {
                visible[i] = blocked[i] + pulse[i];
            }
            note("graph-input-to-visible-ms", visible);
            note("graph-fx-blocked-warm-ms", Arrays.copyOfRange(blocked, 5, REENTRY));
            note("graph-input-to-visible-warm-ms", Arrays.copyOfRange(visible, 5, REENTRY));
            LINES.add("graph-nodes=" + nodes[0] + ".." + nodes[REENTRY - 1]
                    + " listeners=" + listeners[0] + ".." + listeners[REENTRY - 1]);
            LINES.add("catalog-reads-during-reentry=" + reads.get());
            if (current[0] != null) {
                Parent engineering = (Parent) field(current[0], "engineeringPane");
                LINES.add("engineering-parked children=" + engineering.getChildrenUnmodifiable().size()
                        + " descendants=" + descendants(engineering)
                        + " scene=" + (engineering.getScene() != null));
                assertFalse(engineering.getScene() != null, "inactive engineering tab stayed in the scene");
                JavaFxTestSupport.runOnFx("open engineering", () -> {
                    javafx.scene.control.TabPane tabs =
                            (javafx.scene.control.TabPane) field(current[0], "analysisTabs");
                    tabs.getSelectionModel().select(2);
                    javafx.scene.control.Tab tab = tabs.getTabs().get(2);
                    Parent parent = engineering.getParent();
                    LINES.add("engineering-selected index=" + tabs.getSelectionModel().getSelectedIndex()
                            + " content=" + (tab.getContent() == null ? "null" : tab.getContent().getClass().getSimpleName())
                            + " same=" + (tab.getContent() == engineering)
                            + " parent=" + (parent == null ? "null" : parent.getClass().getSimpleName())
                            + " scene=" + (engineering.getScene() != null));
                });
                JavaFxTestSupport.drainFxPulse();
                JavaFxTestSupport.runOnFx("engineering visible", () -> {
                    Parent parent = engineering.getParent();
                    boolean runAnalysis = engineering.lookupAll(".button").stream()
                            .anyMatch(node -> node instanceof javafx.scene.control.Button button
                                    && "Run Analysis".equals(button.getText()));
                    LINES.add("engineering-after-pulse parent="
                            + (parent == null ? "null" : parent.getClass().getSimpleName())
                            + " scene=" + (engineering.getScene() != null)
                            + " descendants=" + descendants(engineering)
                            + " runAnalysis=" + runAnalysis);
                    assertTrue(engineering.getScene() != null, "engineering tab did not return to the scene");
                    assertTrue(runAnalysis, "engineering tab lost its controls");
                });
                JavaFxTestSupport.runOnFx("return waveforms", () -> {
                    javafx.scene.control.TabPane tabs =
                            (javafx.scene.control.TabPane) field(current[0], "analysisTabs");
                    tabs.getSelectionModel().select(0);
                });
            }

            long[] paced = new long[10];
            for (int i = 0; i < paced.length; i++) {
                int cycle = i;
                JavaFxTestSupport.runOnFx("paced " + cycle, () -> {
                    long wall = System.nanoTime();
                    current[0].dispose();
                    host.getChildren().clear();
                    Loaded loaded = loadGraph();
                    loaded.graph().setAppService(service, null);
                    host.getChildren().setAll(loaded.root());
                    loaded.root().applyCss();
                    loaded.root().layout();
                    loaded.graph().onShow();
                    loaded.graph().refresh();
                    paced[cycle] = System.nanoTime() - wall;
                    current[0] = loaded.graph();
                });
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(80));
            }
            note("graph-fx-blocked-paced-ms", paced);

            if (current[0] != null && !selected.isEmpty()) {
                long[] legend = new long[100];
                SignalCatalogKey key = selected.get(0);
                for (int i = 0; i < legend.length; i++) {
                    int cycle = i;
                    JavaFxTestSupport.runOnFx("legend " + cycle, () -> {
                        long t = System.nanoTime();
                        current[0].legendToggleForTest(key);
                        legend[cycle] = System.nanoTime() - t;
                    });
                }
                note("legend-handler-ms", legend);
                long[] layout = new long[30];
                for (int i = 0; i < layout.length; i++) {
                    int cycle = i;
                    boolean stacked = cycle % 2 == 0;
                    JavaFxTestSupport.runOnFx("layout " + cycle, () -> {
                        long t = System.nanoTime();
                        current[0].setLayoutModeForTest(stacked);
                        layout[cycle] = System.nanoTime() - t;
                    });
                }
                note("layout-handler-ms", layout);
                long[] pan = new long[20];
                for (int i = 0; i < pan.length; i++) {
                    int cycle = i;
                    JavaFxTestSupport.runOnFx("pan " + cycle, () -> {
                        long t = System.nanoTime();
                        current[0].panLeftForTest();
                        pan[cycle] = System.nanoTime() - t;
                    });
                }
                note("toolbar-pan-handler-ms", pan);
            }

            JavaFxTestSupport.runOnFx("stuck dispose", () -> {
                Loaded loaded = loadGraph();
                CountDownLatch running = new CountDownLatch(3);
                AtomicBoolean release = new AtomicBoolean();
                for (String name : List.of("worker", "categoricalScan", "viewportScheduler")) {
                    executor(loaded.graph(), name).submit(() -> {
                        running.countDown();
                        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                        while (System.nanoTime() < until && !release.get()) {
                            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(40));
                        }
                    });
                }
                assertTrue(running.await(5, TimeUnit.SECONDS));
                long t = System.nanoTime();
                loaded.graph().dispose();
                LINES.add("graph-dispose-busy-ms=" + ((System.nanoTime() - t) / 1_000_000L)
                        + " abandoned=" + FxExecutorRelease.abandonedPools());
                release.set(true);
            });
            runtime("after-graph");
        } catch (Throwable ex) {
            LINES.add("graph-error=" + ex);
            throw ex;
        } finally {
            read.set(false);
            background.join(2_000);
            LINES.add("catalog-reads-total=" + reads.get());
            JavaFxTestSupport.runOnFx("dispose", () -> {
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
            System.clearProperty("stellarai.home");
        }
    }

    private static Map<SignalCatalogKey, TableColumn<?, ?>> columns(ActiveSignalWorkspacePane workspace) throws Exception {
        AtomicReference<Map<SignalCatalogKey, TableColumn<?, ?>>> found = new AtomicReference<>();
        JavaFxTestSupport.runOnFx("columns", () -> {
            Field field = ActiveSignalWorkspacePane.class.getDeclaredField("table");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            javafx.scene.control.TableView<?> table =
                    (javafx.scene.control.TableView<?>) field.get(workspace);
            Map<SignalCatalogKey, TableColumn<?, ?>> byKey = new LinkedHashMap<>();
            for (TableColumn<?, ?> column : table.getColumns()) {
                if (column.getUserData() instanceof SignalCatalogKey key) {
                    byKey.put(key, column);
                }
            }
            found.set(byKey);
        });
        return found.get();
    }

    private record Loaded(Parent root, GraphController graph) {
    }

    private static Loaded loadGraph() throws Exception {
        FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
        Parent root = loader.load();
        return new Loaded(root, loader.getController());
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

    private static CanonicalSignal sample(int index, long timestamp, double value) {
        return new CanonicalSignal(
                "Signal" + index, "Msg", timestamp, 0x100L + (index % 40), 1,
                value, (long) value, "km/h", null, SourceReference.ofLine(1));
    }

    private static InMemorySignalCatalogStore catalog(int count) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("sierra.mf4", "sierra.dbc", count * 2);
        List<CanonicalSignal> samples = new ArrayList<>(count * 2);
        for (int i = 0; i < count; i++) {
            samples.add(sample(i, 1_000_000L, i));
            samples.add(sample(i, 2_000_000L, i + 0.5));
        }
        store.accept(samples);
        return store;
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
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
        }
        return false;
    }

    private static String nodeSplit(GraphController graph) {
        StringBuilder text = new StringBuilder();
        for (String name : List.of("signalList", "analysisTabs", "engineeringPane", "inspectorTabs", "mapRoutePane")) {
            Object value = field(graph, name);
            text.append(name).append('=').append(nodeCount(value));
            if (value instanceof javafx.scene.Node node) {
                javafx.scene.Parent parent = node.getParent();
                text.append("/scene=").append(node.getScene() != null);
                text.append("/parent=").append(parent == null ? "null" : parent.getClass().getSimpleName());
            }
            text.append(' ');
        }
        if (field(graph, "analysisTabs") instanceof Parent tabs) {
            Map<String, Integer> kinds = new LinkedHashMap<>();
            for (javafx.scene.Node node : tabs.lookupAll("*")) {
                kinds.merge(node.getClass().getSimpleName(), 1, Integer::sum);
            }
            kinds.entrySet().stream()
                    .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                    .limit(8)
                    .forEach(entry -> text.append(entry.getKey()).append(':').append(entry.getValue()).append(' '));
        }
        return text.toString().trim();
    }

    private static int descendants(Parent parent) {
        int count = 0;
        for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
            count++;
            if (child instanceof Parent nested) {
                count += descendants(nested);
            }
        }
        return count;
    }

    private static int nodeCount(Object value) {
        if (!(value instanceof Parent parent)) {
            return 0;
        }
        return parent.lookupAll("*").size();
    }

    private static Object field(GraphController graph, String name) {
        try {
            Field field = GraphController.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(graph);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private static int listenerCount(GraphController graph) {
        try {
            Field field = GraphController.class.getDeclaredField("listenerRemovers");
            field.setAccessible(true);
            return ((List<?>) field.get(graph)).size();
        } catch (ReflectiveOperationException ex) {
            return -1;
        }
    }

    private static java.util.concurrent.ExecutorService executor(GraphController graph, String name) throws Exception {
        Field field = GraphController.class.getDeclaredField(name);
        field.setAccessible(true);
        return (java.util.concurrent.ExecutorService) field.get(graph);
    }

    private static Recording startJfr(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.deleteIfExists(path);
            Recording recording = new Recording();
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            recording.setToDisk(true);
            recording.setDestination(path);
            recording.start();
            return recording;
        } catch (Exception ex) {
            LINES.add("jfr-start-failed=" + ex);
            return null;
        }
    }

    private static void stopJfr(Recording recording, String label) {
        if (recording == null) {
            return;
        }
        Path path = recording.getDestination();
        try {
            recording.stop();
            recording.close();
            Map<String, Integer> frames = new LinkedHashMap<>();
            int samples = 0;
            try (RecordingFile file = new RecordingFile(path)) {
                while (file.hasMoreEvents()) {
                    RecordedEvent event = file.readEvent();
                    if (!"jdk.ExecutionSample".equals(event.getEventType().getName())) {
                        continue;
                    }
                    var thread = event.getThread("sampledThread");
                    if (thread == null || !"JavaFX Application Thread".equals(thread.getJavaName())) {
                        continue;
                    }
                    samples++;
                    frames.merge(applicationFrame(event.getStackTrace()), 1, Integer::sum);
                }
            }
            LINES.add("jfr-" + label + " fxSamples=" + samples);
            frames.entrySet().stream()
                    .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                    .limit(12)
                    .forEach(entry -> LINES.add("jfr-" + label + " " + entry.getValue() + " " + entry.getKey()));
        } catch (Exception ex) {
            LINES.add("jfr-read-failed=" + ex);
        }
    }

    private static String applicationFrame(RecordedStackTrace stack) {
        if (stack == null) {
            return "(no stack)";
        }
        List<String> frames = new ArrayList<>();
        for (RecordedFrame frame : stack.getFrames()) {
            if (frame.getMethod() == null || frame.getMethod().getType() == null) {
                continue;
            }
            String type = frame.getMethod().getType().getName();
            if (type.startsWith("com.stellar.platform.desktop.ui.P0Phase3")) {
                continue;
            }
            frames.add(type + "." + frame.getMethod().getName());
            if (frames.size() == 2) {
                break;
            }
        }
        if (frames.isEmpty()) {
            return "(empty)";
        }
        return frames.size() == 1 ? frames.get(0) : frames.get(0) + " <- " + frames.get(1);
    }

    private static void runtime(String label) {
        Runtime rt = Runtime.getRuntime();
        long gcCount = 0;
        long gcMs = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, bean.getCollectionCount());
            gcMs += Math.max(0, bean.getCollectionTime());
        }
        LINES.add("runtime " + label
                + " heapUsed=" + (rt.totalMemory() - rt.freeMemory())
                + " threads=" + Thread.getAllStackTraces().size()
                + " gcCount=" + gcCount
                + " gcMs=" + gcMs
                + " abandonedPools=" + FxExecutorRelease.abandonedPools());
    }

    private static void note(String name, long[] nanos) {
        long[] ms = new long[nanos.length];
        for (int i = 0; i < nanos.length; i++) {
            ms[i] = nanos[i] / 1_000_000L;
        }
        long[] sorted = ms.clone();
        Arrays.sort(sorted);
        LINES.add(name + " n=" + ms.length
                + " p50=" + pct(sorted, 50)
                + " p95=" + pct(sorted, 95)
                + " p99=" + pct(sorted, 99)
                + " max=" + sorted[ms.length - 1]
                + " samples=" + Arrays.toString(ms));
    }

    private static long pct(long[] sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }
}
