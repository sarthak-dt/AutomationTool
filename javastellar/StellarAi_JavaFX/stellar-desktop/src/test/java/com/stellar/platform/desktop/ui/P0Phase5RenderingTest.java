package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.testsupport.DesktopTestIsolation;
import com.stellar.platform.desktop.ui.graph.EChartsAnalyticsPane;
import com.stellar.platform.desktop.ui.graph.EngineeringAnalysisPane;
import com.stellar.platform.desktop.ui.graph.map.MapRoutePane;
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
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 frame timeline. A {@code runLater} latch is not treated as paint.
 * Post-layout is after CSS and layout of the mutation pulse. The following
 * pre-layout is after that pulse has been presented.
 */
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class P0Phase5RenderingTest {

    private static final int CATALOG = 400;
    private static final int REENTRY = 50;
    private static final Path OUT = Path.of("target", "p0-phase5.txt");
    private static final List<String> LINES = new ArrayList<>();

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
        LINES.add("env java=" + System.getProperty("java.version")
                + " maxHeap=" + Runtime.getRuntime().maxMemory()
                + " prism=" + System.getProperty("prism.order"));
    }

    @AfterAll
    static void writeReport() throws Exception {
        Files.createDirectories(OUT.getParent());
        Files.writeString(OUT, String.join(System.lineSeparator(), LINES) + System.lineSeparator());
    }

    @Test
    void renderingTimeline() throws Exception {
        Path temp = Files.createTempDirectory("p0-phase5");
        DesktopTestIsolation.isolateHome(temp.resolve("home"));
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        AtomicReference<GraphSignalSessionStore> storeRef = new AtomicReference<>();
        Stage[] shell = new Stage[1];
        GraphController[] current = new GraphController[1];
        Recording jfr = startJfr(Path.of("target", "p0-phase5.jfr"));
        AtomicBoolean decoding = new AtomicBoolean(true);
        AtomicInteger decodes = new AtomicInteger();
        Thread decoder = ascDecoder(decoding, decodes);
        decoder.start();
        try {
            JavaFxTestSupport.runOnFx("pipeline", () -> LINES.add("prism-pipeline=" + pipelineName()));
            service.newProject("p0-phase5");
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
            long[] fxml = new long[REENTRY];
            long[] refresh = new long[REENTRY];
            long[] handler = new long[REENTRY];
            long[] cssLayout = new long[REENTRY];
            long[] presented = new long[REENTRY];
            long[] populated = new long[REENTRY];
            for (int i = 0; i < REENTRY; i++) {
                int cycle = i;
                JavaFxTestSupport.runOnFx("reenter " + cycle, () -> {
                    if (current[0] != null) {
                        current[0].dispose();
                        host.getChildren().clear();
                    }
                    long wall = System.nanoTime();
                    FrameWatch watch = FrameWatch.watch(shell[0].getScene(), 1);
                    watch.arm(wall);
                    long fxmlStart = wall;
                    Loaded loaded = loadGraph();
                    Parent root = loaded.root();
                    GraphController graph = loaded.graph();
                    fxml[cycle] = System.nanoTime() - fxmlStart;
                    graph.setAppService(service, null);
                    host.getChildren().setAll(root);
                    graph.onShow();
                    long refreshStart = System.nanoTime();
                    graph.refresh();
                    refresh[cycle] = System.nanoTime() - refreshStart;
                    long end = System.nanoTime();
                    handler[cycle] = end - wall;
                    current[0] = graph;
                    watch.handlerEnd(end);
                });
                FrameWatch.Stamps stamps = FrameWatch.awaitLatest();
                cssLayout[i] = Math.max(0, stamps.postLayout - stamps.handlerEnd);
                presented[i] = Math.max(0, stamps.painted - stamps.start);
                long pop = System.nanoTime();
                awaitPopulated(current[0], 4);
                populated[i] = System.nanoTime() - pop;
            }
            note("graph-fxml-ms", fxml);
            note("graph-refresh-ms", refresh);
            note("graph-handler-ms", handler);
            note("graph-css-layout-ms", cssLayout);
            note("graph-presented-ms", presented);
            note("graph-populated-ms", populated);
            note("graph-presented-warm-ms", Arrays.copyOfRange(presented, 5, REENTRY));
            note("graph-css-layout-warm-ms", Arrays.copyOfRange(cssLayout, 5, REENTRY));
            note("graph-handler-warm-ms", Arrays.copyOfRange(handler, 5, REENTRY));
            LINES.add("graph-presented-warm-samples-ms=" + millis(presented, 5));
            LINES.add("graph-cold-presented-ms=" + (presented[0] / 1_000_000L)
                    + " fxml-ms=" + (fxml[0] / 1_000_000L)
                    + " refresh-ms=" + (refresh[0] / 1_000_000L)
                    + " css-layout-ms=" + (cssLayout[0] / 1_000_000L)
                    + " populated-ms=" + (populated[0] / 1_000_000L));
            cssAblation(host);
            componentConstruction();
            if (current[0] != null && !selected.isEmpty()) {
                interaction("legend", 100, () -> current[0].legendToggleForTest(selected.get(0)));
                interaction("layout", 30, () -> {
                    Node stacked = host.lookup("#stackedLayoutButton");
                    Node overlay = host.lookup("#overlayLayoutButton");
                    Node next = ((javafx.scene.control.ToggleButton) stacked).isSelected() ? overlay : stacked;
                    if (next != null) {
                        ((javafx.scene.control.ToggleButton) next).fire();
                    }
                });
                interaction("toolbar", 20, () -> current[0].panLeftForTest());
            }
            JavaFxTestSupport.runOnFx("dispose graph", () -> {
                if (current[0] != null) {
                    current[0].dispose();
                    current[0] = null;
                }
                host.getChildren().clear();
            });
            LINES.add("heap-before-diagnostic-gc=" + usedHeap());
            System.gc();
            Thread.sleep(200);
            System.gc();
            LINES.add("heap-after-diagnostic-gc=" + usedHeap()
                    + " note=diagnostic-gc-in-test-process-only");
            stopJfr(jfr);
            jfr = null;
            histogram();
            checkboxTimeline();
            columnIdentity();
        } finally {
            decoding.set(false);
            decoder.join(3_000);
            LINES.add("asc-decodes-during-navigation=" + decodes.get());
            if (jfr != null) {
                stopJfr(jfr);
            }
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

    private static void cssAblation(StackPane host) throws Exception {
        JavaFxTestSupport.runOnFx("css ablation", () -> {
            Parent page = (Parent) host.getChildren().get(0);
            Scene scene = page.getScene();
            page.applyCss();
            page.layout();
            LINES.add("css-full nodes=" + count(page)
                    + " lookup=" + page.lookupAll("*").size()
                    + " applyCss-ms=" + millis(time(page::applyCss))
                    + " layout-ms=" + millis(time(page::layout)));
            Node inspector = page.lookup("#inspectorPanel");
            Parent inspectorParent = inspector == null ? null : inspector.getParent();
            int inspectorIndex = inspectorParent == null ? -1 : inspectorParent.getChildrenUnmodifiable().indexOf(inspector);
            if (inspectorParent instanceof javafx.scene.layout.Pane pane && inspectorIndex >= 0) {
                pane.getChildren().remove(inspector);
                page.applyCss();
                page.layout();
                LINES.add("css-without-inspector nodes=" + count(page)
                        + " applyCss-ms=" + millis(time(page::applyCss))
                        + " layout-ms=" + millis(time(page::layout)));
                pane.getChildren().add(inspectorIndex, inspector);
            }
            Node explorer = page.lookup("#signalList");
            Parent explorerParent = explorer == null ? null : explorer.getParent();
            int explorerIndex = explorerParent == null ? -1 : explorerParent.getChildrenUnmodifiable().indexOf(explorer);
            if (explorerParent instanceof javafx.scene.layout.Pane pane && explorerIndex >= 0) {
                pane.getChildren().remove(explorer);
                page.applyCss();
                page.layout();
                LINES.add("css-without-explorer nodes=" + count(page)
                        + " applyCss-ms=" + millis(time(page::applyCss))
                        + " layout-ms=" + millis(time(page::layout)));
                pane.getChildren().add(explorerIndex, explorer);
            }
            List<String> sheets = List.copyOf(scene.getStylesheets());
            scene.getStylesheets().clear();
            page.applyCss();
            long bare = time(page::applyCss);
            scene.getStylesheets().setAll(sheets);
            page.applyCss();
            long styled = time(page::applyCss);
            LINES.add("css-bare-second-pass-ms=" + millis(bare)
                    + " css-restored-pass-ms=" + millis(styled)
                    + " selector-delta-ms=" + millis(Math.max(0, styled - bare)));
            page.applyCss();
            page.layout();
        });
    }

    private static void componentConstruction() throws Exception {
        JavaFxTestSupport.runOnFx("constructors", () -> {
            long engineering = time(EngineeringAnalysisPane::new);
            long analytics = time(EChartsAnalyticsPane::new);
            long map = time(MapRoutePane::new);
            long fxml = time(() -> {
                try {
                    loadGraph();
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            });
            LINES.add("warm-engineering-ctor-ms=" + millis(engineering)
                    + " analytics-ctor-ms=" + millis(analytics)
                    + " map-ctor-ms=" + millis(map)
                    + " warm-fxml-ms=" + millis(fxml));
        });
    }

    private static void checkboxTimeline() throws Exception {
        SignalsScreenModel model = new SignalsScreenModel();
        model.replaceCatalog(browserCatalog());
        SignalBrowserPane browser = new SignalBrowserPane();
        ActiveSignalWorkspacePane workspace = new ActiveSignalWorkspacePane();
        browser.setModel(model);
        workspace.setModel(model);
        HBox root = new HBox(browser, workspace);
        Stage[] shown = new Stage[1];
        JavaFxTestSupport.runOnFx("checkbox show", () -> {
            shown[0] = stage(root);
            browser.resize(480, 768);
            workspace.resize(880, 768);
            root.applyCss();
            root.layout();
        });
        JavaFxTestSupport.drainFxPulse();
        measureChecks("check-selected-only", 30, browser, shown[0].getScene(), box -> box.setSelected(!box.isSelected()));
        measureChecks("check-after-paint", 100, browser, shown[0].getScene(), box -> {
            browser.setAddToWorkspaceListener(entries -> {
                if (box.isSelected()) {
                    workspace.addEntriesAfterPaint(entries);
                } else {
                    workspace.removePhysicalKeysAfterPaint(entries.stream().map(SignalCatalogEntry::key).toList());
                }
            });
            box.fire();
        });
        measureChecks("check-sync-reload", 20, browser, shown[0].getScene(), box -> {
            browser.setAddToWorkspaceListener(entries -> {
                if (box.isSelected()) {
                    workspace.addEntries(entries);
                } else {
                    workspace.removePhysicalKeys(entries.stream().map(SignalCatalogEntry::key).toList());
                }
            });
            box.fire();
        });
        JavaFxTestSupport.runOnFx("checkbox close", () -> shown[0].close());
        model.shutdown();
    }

    private static void measureChecks(String label, int count, SignalBrowserPane browser, Scene scene,
                                      Consumer<CheckBox> click) throws Exception {
        long[] handler = new long[count];
        long[] cssLayout = new long[count];
        long[] presented = new long[count];
        long[] tableReady = new long[count];
        for (int i = 0; i < count; i++) {
            int cycle = i;
            CheckBox[] box = new CheckBox[1];
            JavaFxTestSupport.runOnFx(label + " " + cycle, () -> {
                for (Node node : browser.lookupAll(".browser-row-check")) {
                    CheckBox candidate = (CheckBox) node;
                    box[0] = candidate;
                    break;
                }
                assertTrue(box[0] != null, "checkbox");
                long start = System.nanoTime();
                FrameWatch watch = FrameWatch.watch(scene, 2);
                watch.arm(start);
                click.accept(box[0]);
                watch.handlerEnd(System.nanoTime());
            });
            FrameWatch.Stamps stamps = FrameWatch.awaitLatest();
            handler[i] = Math.max(0, stamps.handlerEnd - stamps.start);
            cssLayout[i] = Math.max(0, stamps.postLayout - stamps.handlerEnd);
            presented[i] = Math.max(0, stamps.painted - stamps.start);
            tableReady[i] = Math.max(0, stamps.tablePainted - stamps.start);
        }
        note(label + "-handler-ms", handler);
        note(label + "-css-layout-ms", cssLayout);
        note(label + "-presented-ms", presented);
        note(label + "-table-ready-ms", tableReady);
    }

    private static void columnIdentity() throws Exception {
        SignalsScreenModel model = new SignalsScreenModel();
        model.replaceCatalog(browserCatalog());
        ActiveSignalWorkspacePane workspace = new ActiveSignalWorkspacePane();
        workspace.setModel(model);
        List<SignalCatalogEntry> entries = model.displayedCatalog();
        Stage[] shown = new Stage[1];
        JavaFxTestSupport.runOnFx("columns show", () -> {
            shown[0] = stage(workspace);
            long start = System.nanoTime();
            workspace.addPhysicalKeys(entries.stream().limit(128).map(SignalCatalogEntry::key).toList());
            LINES.add("select-all-128-handler-ms=" + millis(System.nanoTime() - start)
                    + " columns=" + workspace.table().getColumns().size());
        });
        FrameWatch watchHolder = null;
        JavaFxTestSupport.runOnFx("clear before select", () -> workspace.removePhysicalKeys(model.workspaceKeys()));
        JavaFxTestSupport.drainFxPulse();
        JavaFxTestSupport.runOnFx("select all frame", () -> {
            long start = System.nanoTime();
            FrameWatch watch = FrameWatch.watch(shown[0].getScene(), 2);
            watch.arm(start);
            workspace.addPhysicalKeysAfterPaint(entries.stream().limit(128).map(SignalCatalogEntry::key).toList());
            watch.handlerEnd(System.nanoTime());
        });
        FrameWatch.Stamps select = FrameWatch.awaitLatest();
        LINES.add("select-all-deferred-handler-ms=" + millis(select.handlerEnd - select.start)
                + " css-layout-ms=" + millis(select.postLayout - select.handlerEnd)
                + " presented-ms=" + millis(select.painted - select.start)
                + " table-ready-ms=" + millis(select.tablePainted - select.start));
        Map<SignalCatalogKey, TableColumn<?, ?>> before = columns(workspace);
        TableColumn<?, ?> kept = before.get(entries.get(80).key());
        JavaFxTestSupport.runOnFx("remove middle", () -> workspace.removePhysicalKeys(List.of(entries.get(50).key())));
        Map<SignalCatalogKey, TableColumn<?, ?>> after = columns(workspace);
        assertSame(kept, after.get(entries.get(80).key()));
        assertEquals(127, after.size());
        JavaFxTestSupport.runOnFx("clear", () -> {
            workspace.removePhysicalKeysAfterPaint(List.copyOf(model.workspaceKeys()));
        });
        int settled = -1;
        for (int pulse = 0; pulse < 6; pulse++) {
            JavaFxTestSupport.drainFxPulse();
            AtomicInteger size = new AtomicInteger(-1);
            JavaFxTestSupport.runOnFx("clear visible", () -> size.set(workspace.table().getColumns().size()));
            settled = size.get();
            if (settled == 0) {
                break;
            }
        }
        LINES.add("clear-all-columns=" + settled);
        assertEquals(0, settled, "deferred clear must drop every signal column");
        JavaFxTestSupport.runOnFx("close columns", () -> shown[0].close());
        model.shutdown();
    }

    private static void interaction(String label, int count, Runnable action) throws Exception {
        long[] handler = new long[count];
        long[] presented = new long[count];
        Scene[] scene = new Scene[1];
        JavaFxTestSupport.runOnFx("scene " + label, () -> scene[0] = Stage.getWindows().stream()
                .filter(Stage.class::isInstance)
                .map(Stage.class::cast)
                .filter(Stage::isShowing)
                .findFirst()
                .orElseThrow()
                .getScene());
        for (int i = 0; i < count; i++) {
            JavaFxTestSupport.runOnFx(label + " " + i, () -> {
                long start = System.nanoTime();
                FrameWatch watch = FrameWatch.watch(scene[0], 1);
                watch.arm(start);
                action.run();
                watch.handlerEnd(System.nanoTime());
            });
            FrameWatch.Stamps stamps = FrameWatch.awaitLatest();
            handler[i] = Math.max(0, stamps.handlerEnd - stamps.start);
            presented[i] = Math.max(0, stamps.painted - stamps.start);
        }
        note(label + "-handler-ms", handler);
        note(label + "-presented-ms", presented);
    }

    private static final class FrameWatch {
        private static final AtomicReference<FrameWatch> LATEST = new AtomicReference<>();
        private final Scene scene;
        private final int extraPulses;
        private final CountDownLatch done = new CountDownLatch(1);
        private final Stamps stamps = new Stamps();
        private int post;
        private Runnable postListener;

        private FrameWatch(Scene scene, int extraPulses) {
            this.scene = scene;
            this.extraPulses = extraPulses;
        }

        static FrameWatch watch(Scene scene, int extraPulses) {
            FrameWatch watch = new FrameWatch(scene, extraPulses);
            LATEST.set(watch);
            return watch;
        }

        static Stamps awaitLatest() throws Exception {
            FrameWatch watch = LATEST.get();
            assertTrue(watch.done.await(8, TimeUnit.SECONDS), "frame");
            return watch.stamps;
        }

        void arm(long start) {
            stamps.start = start;
            postListener = () -> {
                post++;
                long now = System.nanoTime();
                if (post == 1) {
                    stamps.postLayout = now;
                    stamps.painted = now;
                    if (extraPulses < 2) {
                        scene.removePostLayoutPulseListener(postListener);
                        done.countDown();
                    }
                } else if (post >= 2) {
                    stamps.tablePainted = now;
                    scene.removePostLayoutPulseListener(postListener);
                    done.countDown();
                }
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
            long tablePainted;
        }
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

    private static CanonicalSignal sample(int index, long timestamp, double value) {
        return new CanonicalSignal(
                "Signal" + index, "Msg", timestamp, 0x100L + (index % 40), 1,
                value, (long) value, "km/h", null, SourceReference.ofLine(1));
    }

    private static InMemorySignalCatalogStore browserCatalog() {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("sierra.mf4", "sierra.dbc", CATALOG * 2);
        List<CanonicalSignal> samples = new ArrayList<>(CATALOG * 2);
        for (int i = 0; i < CATALOG; i++) {
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
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        return false;
    }

    private static Thread ascDecoder(AtomicBoolean decoding, AtomicInteger decodes) {
        Thread worker = new Thread(() -> {
            Path asc = Path.of("src", "test", "resources", "asc", "desktop_engine.asc").toAbsolutePath();
            Path dbc = Path.of("src", "test", "resources", "dbc", "desktop_engine.dbc").toAbsolutePath();
            var gateway = new com.stellar.platform.desktop.processing.LocalProcessingGateway(
                    new com.stellar.platform.processing.MeasurementDecodePipeline(
                            com.stellar.platform.processing.ParserRegistry.load()));
            var measurement = new com.stellar.platform.desktop.project.WorkspaceFile(
                    asc, com.stellar.platform.desktop.project.FileKind.MEASUREMENT_ASC, 1);
            var definition = new com.stellar.platform.desktop.project.WorkspaceFile(
                    dbc, com.stellar.platform.desktop.project.FileKind.DEFINITION_DBC, 1);
            while (decoding.get() && decodes.get() < 30) {
                gateway.process(measurement, definition, 8, 100, null, null);
                decodes.incrementAndGet();
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(30));
            }
        }, "phase5-asc-decode");
        worker.setDaemon(true);
        return worker;
    }

    private static String pipelineName() {
        try {
            Object pipeline = Class.forName("com.sun.prism.GraphicsPipeline")
                    .getMethod("getPipeline")
                    .invoke(null);
            return pipeline == null ? "none" : pipeline.getClass().getName();
        } catch (ReflectiveOperationException ex) {
            return "unavailable:" + ex.getClass().getSimpleName();
        }
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

    private static long time(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return System.nanoTime() - start;
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
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

    private static Recording startJfr(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.deleteIfExists(path);
            Recording recording = new Recording();
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            recording.enable("jdk.ObjectAllocationSample").with("throttle", "200/s");
            recording.enable("jdk.OldObjectSample").withStackTrace();
            recording.setToDisk(true);
            recording.setDestination(path);
            recording.start();
            return recording;
        } catch (Exception ex) {
            LINES.add("jfr-start-failed=" + ex);
            return null;
        }
    }

    private static void stopJfr(Recording recording) {
        if (recording == null) {
            return;
        }
        Path path = recording.getDestination();
        try {
            recording.stop();
            recording.close();
            Map<String, Integer> frames = new LinkedHashMap<>();
            Map<String, Long> allocations = new LinkedHashMap<>();
            Map<String, Integer> oldInts = new LinkedHashMap<>();
            int samples = 0;
            try (RecordingFile file = new RecordingFile(path)) {
                while (file.hasMoreEvents()) {
                    RecordedEvent event = file.readEvent();
                    String name = event.getEventType().getName();
                    if ("jdk.ExecutionSample".equals(name)) {
                        var thread = event.getThread("sampledThread");
                        if (thread != null && "JavaFX Application Thread".equals(thread.getJavaName())) {
                            samples++;
                            frames.merge(frame(event.getStackTrace()), 1, Integer::sum);
                        }
                    } else if ("jdk.ObjectAllocationSample".equals(name)) {
                        var type = event.getClass("objectClass");
                        if (type != null && "[I".equals(type.getName())) {
                            allocations.merge(frame(event.getStackTrace()), event.getLong("weight"), Long::sum);
                        }
                    } else if ("jdk.OldObjectSample".equals(name)) {
                        String described = String.valueOf(event);
                        if (described.contains("int[]") || described.contains("[I")) {
                            oldInts.merge(frame(event.getStackTrace()), 1, Integer::sum);
                        }
                    }
                }
            }
            LINES.add("jfr-fxSamples=" + samples);
            frames.entrySet().stream()
                    .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                    .limit(12)
                    .forEach(entry -> LINES.add("jfr-exec " + entry.getValue() + " " + entry.getKey()));
            allocations.entrySet().stream()
                    .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                    .limit(12)
                    .forEach(entry -> LINES.add("jfr-int-alloc " + entry.getValue() + " " + entry.getKey()));
            oldInts.entrySet().stream()
                    .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                    .limit(12)
                    .forEach(entry -> LINES.add("jfr-old-int " + entry.getValue() + " " + entry.getKey()));
        } catch (Exception ex) {
            LINES.add("jfr-read-failed=" + ex);
        }
    }

    private static String frame(RecordedStackTrace stack) {
        if (stack == null) {
            return "(no stack)";
        }
        List<String> frames = new ArrayList<>();
        for (RecordedFrame item : stack.getFrames()) {
            if (item.getMethod() == null || item.getMethod().getType() == null) {
                continue;
            }
            String type = item.getMethod().getType().getName();
            if (type.startsWith("com.stellar.platform.desktop.ui.P0Phase5")
                    || type.startsWith("jdk.jfr")
                    || type.startsWith("java.lang.reflect")) {
                continue;
            }
            frames.add(type + "." + item.getMethod().getName());
            if (frames.size() == 2) {
                break;
            }
        }
        if (frames.isEmpty()) {
            return "(empty)";
        }
        return frames.size() == 1 ? frames.get(0) : frames.get(0) + " <- " + frames.get(1);
    }

    private static void histogram() {
        try {
            Path jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd.exe");
            Process process = new ProcessBuilder(
                    jcmd.toString(),
                    Long.toString(ProcessHandle.current().pid()),
                    "GC.class_histogram")
                    .redirectErrorStream(true)
                    .start();
            String text = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LINES.add("histogram-timeout");
                return;
            }
            int kept = 0;
            long intBytes = 0;
            for (String line : text.split("\\R")) {
                if (line.contains("[I ")) {
                    intBytes += bytesOf(line);
                }
                if (kept < 8 || line.contains("[I ") || line.contains("com.stellar")
                        || line.contains("ParsedValue") || line.contains("Prism")
                        || line.contains("javafx.scene.image")) {
                    if (line.isBlank() || line.startsWith("num") || line.startsWith("-")) {
                        continue;
                    }
                    LINES.add("histogram " + line.trim());
                    kept++;
                    if (kept > 24) {
                        break;
                    }
                }
            }
            LINES.add("histogram-int-array-bytes=" + intBytes + " note=diagnostic-gc-in-test-process-only");
        } catch (Exception ex) {
            LINES.add("histogram-failed=" + ex);
        }
    }

    private static long bytesOf(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 3) {
            return 0;
        }
        try {
            return Long.parseLong(parts[2]);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static void note(String name, long[] nanos) {
        long[] sorted = new long[nanos.length];
        for (int i = 0; i < nanos.length; i++) {
            sorted[i] = nanos[i] / 1_000_000L;
        }
        Arrays.sort(sorted);
        LINES.add(name + " n=" + sorted.length
                + " p50=" + pct(sorted, 50)
                + " p95=" + pct(sorted, 95)
                + " p99=" + pct(sorted, 99)
                + " max=" + sorted[sorted.length - 1]);
    }

    private static long pct(long[] sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static String millis(long[] nanos, int from) {
        StringBuilder text = new StringBuilder();
        for (int i = from; i < nanos.length; i++) {
            if (i > from) {
                text.append(',');
            }
            text.append(nanos[i] / 1_000_000L);
        }
        return text.toString();
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000L;
    }
}
