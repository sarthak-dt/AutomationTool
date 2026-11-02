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
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
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
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase timings for Graph re-entry and checkbox feedback. Numbers are evidence.
 * They do not assert the responsiveness budgets.
 */
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class P0Phase2ResponsivenessTest {

    private static final int CATALOG = 400;
    private static final int CHECKS = 100;
    private static final int LEGEND = 100;
    private static final int LAYOUT = 30;
    private static final int EVENTS = 20;
    private static final int REENTRY = 20;
    private static final Path OUT = Path.of("target", "p0-phase2.txt");
    private static final List<String> LINES = new ArrayList<>();

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
        LINES.add("env java=" + System.getProperty("java.version")
                + " os=" + System.getProperty("os.name")
                + " processors=" + Runtime.getRuntime().availableProcessors()
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
    void checkboxFeedbackOnAFourHundredSignalCatalog() throws Exception {
        long[] dispatch = new long[16];
        long[] clickHandler = new long[16];
        long[] clickPulse = new long[16];
        long[] modelMs = new long[CHECKS];
        long[] reloadMs = new long[CHECKS];
        long[] refreshMs = new long[CHECKS];
        long[] addPulse = new long[CHECKS];
        long[] rapid = new long[40];
        long[] rapidPulse = new long[40];
        AtomicLongBox selectAll = new AtomicLongBox();
        try {
            SignalsScreenModel model = new SignalsScreenModel();
            InMemorySignalCatalogStore store = catalog(CATALOG);
            model.replaceCatalog(store);
            SignalBrowserPane browser = new SignalBrowserPane();
            ActiveSignalWorkspacePane workspace = new ActiveSignalWorkspacePane();
            browser.setModel(model);
            workspace.setModel(model);
            browser.setAddToWorkspaceListener(entries -> workspace.addEntries(entries));
            browser.setRemoveFromWorkspaceListener(entries -> {
                workspace.removePhysicalKeys(entries.stream().map(SignalCatalogEntry::key).toList());
                browser.refreshFavoritePresentation();
            });
            HBox root = new HBox(browser, workspace);
            Stage[] shown = new Stage[1];
            JavaFxTestSupport.runOnFx("size browser", () -> {
                shown[0] = stage(root, 1366, 768);
                browser.resize(480, 768);
                workspace.resize(880, 768);
                root.applyCss();
                root.layout();
            });
            Stage stage = shown[0];
            JavaFxTestSupport.drainFxPulse();
            List<CheckBox> boxes = new ArrayList<>();
            JavaFxTestSupport.runOnFx("find checks", () -> {
                for (javafx.scene.Node node : browser.lookupAll(".browser-row-check")) {
                    boxes.add((CheckBox) node);
                }
            });
            LINES.add("checkbox-visible-rows=" + boxes.size() + " catalog=" + model.displayedCatalog().size());
            int clicks = Math.min(dispatch.length, boxes.size());
            int[] heard = new int[1];
            for (int i = 0; i < clicks; i++) {
                int index = i;
                long[] mark = new long[2];
                CountDownLatch pulse = new CountDownLatch(1);
                browser.setAddToWorkspaceListener(entries -> {
                    mark[0] = System.nanoTime();
                    workspace.addEntries(entries);
                    browser.refreshFavoritePresentation();
                    mark[1] = System.nanoTime();
                });
                JavaFxTestSupport.runOnFx("click " + index, () -> {
                    CheckBox box = null;
                    for (javafx.scene.Node node : browser.lookupAll(".browser-row-check")) {
                        CheckBox candidate = (CheckBox) node;
                        if (!candidate.isSelected()) {
                            box = candidate;
                            break;
                        }
                    }
                    long fired = System.nanoTime();
                    if (box != null) {
                        box.fire();
                    }
                    if (mark[0] > 0) {
                        dispatch[index] = mark[0] - fired;
                        clickHandler[index] = mark[1] - mark[0];
                        heard[0]++;
                    }
                    Platform.runLater(pulse::countDown);
                });
                assertTrue(pulse.await(5, TimeUnit.SECONDS), "click pulse " + index);
                clickPulse[index] = mark[1] == 0 ? 0 : System.nanoTime() - mark[1];
            }
            LINES.add("checkbox-clicks-heard=" + heard[0]);
            note("checkbox-click-dispatch-ms", dispatch, clicks);
            note("checkbox-click-handler-ms", clickHandler, clicks);
            note("checkbox-click-next-pulse-ms", clickPulse, clicks);

            List<SignalCatalogEntry> entries = model.displayedCatalog();
            int already = model.workspaceKeys().size();
            for (int i = already; i < CHECKS && i < entries.size(); i++) {
                int index = i;
                CountDownLatch pulse = new CountDownLatch(1);
                long[] posted = new long[1];
                JavaFxTestSupport.runOnFx("add " + index, () -> {
                    long a = System.nanoTime();
                    model.addToWorkspace(entries.get(index).key());
                    long b = System.nanoTime();
                    workspace.reload(true);
                    long c = System.nanoTime();
                    browser.refreshFavoritePresentation();
                    long d = System.nanoTime();
                    modelMs[index] = b - a;
                    reloadMs[index] = c - b;
                    refreshMs[index] = d - c;
                    posted[0] = d;
                    Platform.runLater(pulse::countDown);
                });
                assertTrue(pulse.await(5, TimeUnit.SECONDS), "add pulse " + index);
                addPulse[index] = System.nanoTime() - posted[0];
            }
            note("checkbox-model-ms", Arrays.copyOfRange(modelMs, already, CHECKS));
            note("checkbox-reload-ms", Arrays.copyOfRange(reloadMs, already, CHECKS));
            note("checkbox-catalog-refresh-ms", Arrays.copyOfRange(refreshMs, already, CHECKS));
            note("checkbox-add-next-pulse-ms", Arrays.copyOfRange(addPulse, already, CHECKS));
            note("checkbox-reload-first10-ms", Arrays.copyOfRange(reloadMs, already, Math.min(CHECKS, already + 10)));
            note("checkbox-reload-last10-ms", Arrays.copyOfRange(reloadMs, CHECKS - 10, CHECKS));
            JavaFxTestSupport.runOnFx("queue after 100", () ->
                    LINES.add("signal-worker-queue-after-100=" + queueDepth(worker(model))
                            + " workspace=" + model.workspaceKeys().size()));

            SignalCatalogEntry rapidEntry = model.displayedCatalog().get(0);
            for (int i = 0; i < rapid.length; i++) {
                int index = i;
                CountDownLatch pulse = new CountDownLatch(1);
                long[] span = new long[1];
                long[] posted = new long[1];
                JavaFxTestSupport.runOnFx("rapid " + index, () -> {
                    long a = System.nanoTime();
                    if (model.workspaceKeys().contains(rapidEntry.key())) {
                        workspace.removePhysicalKeys(List.of(rapidEntry.key()));
                    } else {
                        workspace.addEntries(List.of(rapidEntry));
                    }
                    browser.refreshFavoritePresentation();
                    span[0] = System.nanoTime() - a;
                    posted[0] = System.nanoTime();
                    Platform.runLater(pulse::countDown);
                });
                assertTrue(pulse.await(5, TimeUnit.SECONDS), "rapid pulse " + index);
                rapid[index] = span[0];
                rapidPulse[index] = System.nanoTime() - posted[0];
            }
            note("checkbox-rapid-toggle-ms", rapid);
            note("checkbox-rapid-next-pulse-ms", rapidPulse);

            SignalsScreenModel bulkModel = new SignalsScreenModel();
            bulkModel.replaceCatalog(catalog(CATALOG));
            SignalBrowserPane bulkBrowser = new SignalBrowserPane();
            ActiveSignalWorkspacePane bulkWorkspace = new ActiveSignalWorkspacePane();
            bulkBrowser.setModel(bulkModel);
            bulkWorkspace.setModel(bulkModel);
            bulkBrowser.setOnSelectAll(() -> bulkWorkspace.addPhysicalKeys(
                    bulkBrowser.filteredEntries().stream().map(SignalCatalogEntry::key).toList()));
            HBox bulkRoot = new HBox(bulkBrowser, bulkWorkspace);
            Stage[] bulkShown = new Stage[1];
            JavaFxTestSupport.runOnFx("select all", () -> {
                bulkShown[0] = stage(bulkRoot, 1366, 768);
                bulkBrowser.resize(480, 768);
                bulkWorkspace.resize(880, 768);
                bulkRoot.applyCss();
                bulkRoot.layout();
                long a = System.nanoTime();
                bulkBrowser.selectAllButtonForTest().fire();
                selectAll.nanos = System.nanoTime() - a;
            });
            Stage bulkStage = bulkShown[0];
            LINES.add("checkbox-select-all-ms=" + (selectAll.nanos / 1_000_000L)
                    + " added=" + bulkModel.workspaceKeys().size()
                    + " cap=" + bulkModel.maxWorkspaceSignals());
            JavaFxTestSupport.runOnFx("close checkbox", () -> {
                stage.close();
                bulkStage.close();
            });
            model.shutdown();
            bulkModel.shutdown();
            runtime("after-checkbox");
        } catch (Throwable ex) {
            LINES.add("checkbox-error=" + ex);
            throw ex;
        }
    }

    @Test
    void graphReentryLegendAndLongSession() throws Exception {
        Path temp = Files.createTempDirectory("p0-phase2");
        DesktopTestIsolation.isolateHome(temp.resolve("home"));
        Path db = temp.resolve("meta.db");
        DesktopAppService service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
        GraphSignalSessionStore store = null;
        Stage[] shell = new Stage[1];
        GraphController[] current = new GraphController[1];
        AtomicBoolean metadataReads = new AtomicBoolean(true);
        AtomicInteger metadataCalls = new AtomicInteger();
        Thread metadata = new Thread(() -> {
            while (metadataReads.get()) {
                try {
                    service.metadataStore().listProjects();
                    metadataCalls.incrementAndGet();
                } catch (RuntimeException ignored) {
                    break;
                }
            }
        }, "p0-metadata-read");
        metadata.setDaemon(true);
        try {
            service.newProject("p0-phase2");
            store = GraphSignalSessionStore.create("sierra.mf4", "sierra.dbc");
            List<CanonicalSignal> samples = new ArrayList<>(CATALOG * 2);
            for (int i = 0; i < CATALOG; i++) {
                samples.add(sample(i, 0L, i));
                samples.add(sample(i, 1_000_000_000L, i + 0.25));
            }
            store.accept(samples);
            service.signalsModel().replaceCatalog(store);
            List<SignalCatalogKey> selected = service.signalsModel().displayedCatalog().stream()
                    .limit(8)
                    .map(SignalCatalogEntry::key)
                    .toList();
            service.signalsModel().addToWorkspace(selected);
            LINES.add("graph-catalog=" + service.engineeringCatalog().size()
                    + " selected=" + selected.size());
            metadata.start();

            long[] fresh = new long[4];
            for (int i = 0; i < fresh.length; i++) {
                int cycle = i;
                JavaFxTestSupport.runOnFx("fresh stage " + cycle, () -> {
                    long t0 = System.nanoTime();
                    Loaded loaded = loadGraph();
                    Stage shown = stage(loaded.root(), 1366, 768);
                    loaded.root().applyCss();
                    loaded.root().layout();
                    fresh[cycle] = System.nanoTime() - t0;
                    shown.close();
                    loaded.graph().dispose();
                });
            }
            note("graph-fresh-stage-ms", fresh);

            StackPane host = new StackPane();
            JavaFxTestSupport.runOnFx("open shell scene", () -> {
                shell[0] = stage(host, 1366, 768);
                host.applyCss();
                host.layout();
            });
            JavaFxTestSupport.drainFxPulse();
            runtime("scene-ready");

            long[] evict = new long[REENTRY];
            long[] fxml = new long[REENTRY];
            long[] bind = new long[REENTRY];
            long[] attach = new long[REENTRY];
            long[] css = new long[REENTRY];
            long[] onShow = new long[REENTRY];
            long[] refresh = new long[REENTRY];
            long[] blocked = new long[REENTRY];
            long[] pulse = new long[REENTRY];
            List<Long> populated = new ArrayList<>();
            int[] listeners = new int[REENTRY];
            boolean[] waitSeries = {true};
            Recording jfr = startJfr(Path.of("target", "p0-phase2-reentry.jfr"));
            for (int i = 0; i < REENTRY; i++) {
                int cycle = i;
                CountDownLatch frame = new CountDownLatch(1);
                long[] posted = new long[1];
                JavaFxTestSupport.runOnFx("reenter " + cycle, () -> {
                    long wall = System.nanoTime();
                    long t = wall;
                    if (current[0] != null) {
                        current[0].dispose();
                        host.getChildren().clear();
                    }
                    evict[cycle] = System.nanoTime() - t;
                    t = System.nanoTime();
                    Loaded loaded = loadGraph();
                    fxml[cycle] = System.nanoTime() - t;
                    t = System.nanoTime();
                    loaded.graph().setAppService(service, null);
                    bind[cycle] = System.nanoTime() - t;
                    t = System.nanoTime();
                    host.getChildren().setAll(loaded.root());
                    attach[cycle] = System.nanoTime() - t;
                    t = System.nanoTime();
                    loaded.root().applyCss();
                    loaded.root().layout();
                    css[cycle] = System.nanoTime() - t;
                    t = System.nanoTime();
                    loaded.graph().onShow();
                    onShow[cycle] = System.nanoTime() - t;
                    t = System.nanoTime();
                    loaded.graph().refresh();
                    refresh[cycle] = System.nanoTime() - t;
                    blocked[cycle] = System.nanoTime() - wall;
                    listeners[cycle] = listenerCount(loaded.graph());
                    current[0] = loaded.graph();
                    posted[0] = System.nanoTime();
                    Platform.runLater(frame::countDown);
                });
                assertTrue(frame.await(5, TimeUnit.SECONDS), "reentry frame " + cycle);
                pulse[cycle] = System.nanoTime() - posted[0];
                if (waitSeries[0] || cycle == REENTRY - 1) {
                    long pop = System.nanoTime();
                    boolean ready = awaitPopulated(current[0], 4);
                    long elapsed = System.nanoTime() - pop;
                    populated.add(elapsed);
                    if (cycle == 0) {
                        AtomicReference<String> state = new AtomicReference<>();
                        JavaFxTestSupport.runOnFx("reentry state", () -> state.set(graphState(current[0])));
                        LINES.add("reentry-0 " + state.get() + " populatedReady=" + ready
                                + " populatedMs=" + (elapsed / 1_000_000L));
                        if (!ready || elapsed > 1_500_000_000L) {
                            waitSeries[0] = false;
                        }
                    }
                }
            }
            if (current[0] != null) {
                AtomicReference<String> second = new AtomicReference<>();
                JavaFxTestSupport.runOnFx("refresh again", () -> {
                    int before = listenerCount(current[0]);
                    current[0].refresh();
                    second.set("listeners-before-second-refresh=" + before
                            + " after=" + listenerCount(current[0])
                            + " " + graphState(current[0]));
                });
                LINES.add(second.get());
            }
            stopJfr(jfr, "reentry");
            note("graph-evict-ms", evict);
            note("graph-fxml-ms", fxml);
            note("graph-bind-ms", bind);
            note("graph-attach-ms", attach);
            note("graph-css-layout-ms", css);
            note("graph-onshow-ms", onShow);
            note("graph-refresh-ms", refresh);
            note("graph-fx-blocked-ms", blocked);
            note("graph-first-pulse-ms", pulse);
            note("graph-populated-ms", populated.stream().mapToLong(Long::longValue).toArray());
            LINES.add("graph-listeners=" + Arrays.toString(listeners));
            runtime("after-reentry");

            if (current[0] != null && !selected.isEmpty()) {
                long[] legend = new long[LEGEND];
                long[] legendPulse = new long[LEGEND];
                SignalCatalogKey key = selected.get(0);
                for (int i = 0; i < LEGEND; i++) {
                    int cycle = i;
                    CountDownLatch frame = new CountDownLatch(1);
                    long[] posted = new long[1];
                    long[] span = new long[1];
                    JavaFxTestSupport.runOnFx("legend " + cycle, () -> {
                        long a = System.nanoTime();
                        current[0].legendToggleForTest(key);
                        span[0] = System.nanoTime() - a;
                        posted[0] = System.nanoTime();
                        Platform.runLater(frame::countDown);
                    });
                    assertTrue(frame.await(5, TimeUnit.SECONDS), "legend frame " + cycle);
                    legend[cycle] = span[0];
                    legendPulse[cycle] = System.nanoTime() - posted[0];
                }
                note("legend-toggle-ms", legend);
                note("legend-next-pulse-ms", legendPulse);
                note("legend-toggle-first10-ms", Arrays.copyOf(legend, 10));
                note("legend-toggle-last10-ms", Arrays.copyOfRange(legend, LEGEND - 10, LEGEND));

                long[] layout = new long[LAYOUT];
                long[] layoutPulse = new long[LAYOUT];
                for (int i = 0; i < LAYOUT; i++) {
                    int cycle = i;
                    boolean stacked = cycle % 2 == 0;
                    CountDownLatch frame = new CountDownLatch(1);
                    long[] posted = new long[1];
                    long[] span = new long[1];
                    JavaFxTestSupport.runOnFx("layout " + cycle, () -> {
                        long a = System.nanoTime();
                        current[0].setLayoutModeForTest(stacked);
                        span[0] = System.nanoTime() - a;
                        posted[0] = System.nanoTime();
                        Platform.runLater(frame::countDown);
                    });
                    assertTrue(frame.await(5, TimeUnit.SECONDS), "layout frame " + cycle);
                    layout[cycle] = span[0];
                    layoutPulse[cycle] = System.nanoTime() - posted[0];
                }
                note("layout-switch-ms", layout);
                note("layout-next-pulse-ms", layoutPulse);

                long[] events = new long[EVENTS];
                JavaFxTestSupport.runOnFx("arm event cursor", () -> current[0].setCursorsForTest(1_000_000L, null));
                Method buildDialog = GraphController.class.getDeclaredMethod("buildAddEventDialog");
                buildDialog.setAccessible(true);
                for (int i = 0; i < EVENTS; i++) {
                    int cycle = i;
                    AtomicReference<String> saved = new AtomicReference<>("");
                    JavaFxTestSupport.runOnFx("event " + cycle, () -> {
                        long a = System.nanoTime();
                        Dialog<?> dialog = (Dialog<?>) buildDialog.invoke(current[0]);
                        if (dialog != null) {
                            saved.set(current[0].commitAddEventForTest(dialog));
                        }
                        events[cycle] = System.nanoTime() - a;
                    });
                    if (cycle == 0) {
                        LINES.add("event-0-saved=" + !saved.get().isBlank());
                    }
                }
                note("graph-add-event-ms", events);
            }

            var save = service.saveProject();
            LINES.add("project-save-ms-not-timed saved=" + save.saved() + " message=" + save.message());
            String projectId = save.saved() ? service.currentProjectId() : "";
            long[] switchOpen = new long[3];
            if (!projectId.isBlank()) {
                for (int i = 0; i < switchOpen.length; i++) {
                    int cycle = i;
                    JavaFxTestSupport.runOnFx("evict before switch " + cycle, () -> {
                        if (current[0] != null) {
                            current[0].dispose();
                            current[0] = null;
                            host.getChildren().clear();
                        }
                    });
                    service.closeProject();
                    long t = System.nanoTime();
                    var opened = service.openSavedProject(projectId);
                    switchOpen[cycle] = System.nanoTime() - t;
                    LINES.add("project-switch-" + cycle + " opened=" + opened.opened()
                            + " ms=" + (switchOpen[cycle] / 1_000_000L)
                            + " catalog=" + service.signalsModel().displayedCatalog().size());
                }
            }
            note("project-switch-open-ms", switchOpen);
            runtime("after-switches");

            JavaFxTestSupport.runOnFx("stuck pools", () -> {
                Loaded loaded = loadGraph();
                CountDownLatch running = new CountDownLatch(3);
                AtomicBoolean release = new AtomicBoolean();
                for (String name : List.of("worker", "categoricalScan", "viewportScheduler")) {
                    executor(loaded.graph(), name).submit(() -> {
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
                int before = FxExecutorRelease.abandonedPools();
                long t = System.nanoTime();
                loaded.graph().dispose();
                long blockedMs = (System.nanoTime() - t) / 1_000_000L;
                LINES.add("graph-dispose-busy-ms=" + blockedMs
                        + " abandonedDelta=" + (FxExecutorRelease.abandonedPools() - before));
                release.set(true);
            });
        } catch (Throwable ex) {
            LINES.add("graph-error=" + ex);
            throw ex;
        } finally {
            metadataReads.set(false);
            metadata.join(2_000);
            LINES.add("metadata-reads-during-ui=" + metadataCalls.get());
            JavaFxTestSupport.runOnFx("dispose leftover graph", () -> {
                if (current[0] != null) {
                    current[0].dispose();
                    current[0] = null;
                }
                if (shell[0] != null) {
                    shell[0].close();
                }
            });
            Thread.sleep(200);
            LINES.add("stellar-after-graph-dispose=" + JavaFxTestSupport.liveStellarThreadNames());
            service.shutdown();
            if (store != null) {
                try {
                    store.close();
                } catch (RuntimeException ex) {
                    LINES.add("store-close=" + ex);
                }
            }
            DesktopTestIsolation.resetProcessState();
            System.clearProperty("stellarai.home");
            System.clearProperty("stellarai.test.isolate");
        }
    }

    private static void unusedFreshCompileGuard() {
    }

    private record Loaded(Parent root, GraphController graph) {
    }

    private static Loaded loadGraph() throws Exception {
        FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
        Parent root = loader.load();
        return new Loaded(root, loader.getController());
    }

    private static Stage stage(Parent root, double width, double height) {
        Scene scene = new Scene(root, width, height);
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

    private static ExecutorService worker(SignalsScreenModel model) throws Exception {
        Field field = SignalsScreenModel.class.getDeclaredField("worker");
        field.setAccessible(true);
        return (ExecutorService) field.get(model);
    }

    private static int queueDepth(ExecutorService executor) {
        try {
            Field delegate = executor.getClass().getDeclaredField("e");
            delegate.setAccessible(true);
            Object inner = delegate.get(executor);
            if (inner instanceof java.util.concurrent.ThreadPoolExecutor pool) {
                return pool.getQueue().size();
            }
        } catch (ReflectiveOperationException ignored) {
            return -1;
        }
        return -1;
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
                + " stellar=" + JavaFxTestSupport.liveStellarThreadNames()
                + " gcCount=" + gcCount
                + " gcMs=" + gcMs
                + " abandonedPools=" + FxExecutorRelease.abandonedPools());
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
                    String frame = applicationFrame(event.getStackTrace());
                    frames.merge(frame, 1, Integer::sum);
                }
            }
            LINES.add("jfr-" + label + " fxSamples=" + samples + " file=" + path.toAbsolutePath());
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
        String top = "(empty)";
        for (RecordedFrame frame : stack.getFrames()) {
            if (frame.getMethod() == null || frame.getMethod().getType() == null) {
                continue;
            }
            String type = frame.getMethod().getType().getName();
            String name = type + "." + frame.getMethod().getName();
            if (top.equals("(empty)")) {
                top = name;
            }
            if (type.startsWith("com.stellar.") || type.startsWith("javafx.fxml.")) {
                return name;
            }
        }
        return top;
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
            Thread.sleep(25);
        }
        return false;
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

    private static String graphState(GraphController graph) {
        int series = graph.canvasForTest() == null ? -1 : graph.canvasForTest().seriesCountForTest();
        int queue = -1;
        try {
            queue = queueDepth(executor(graph, "worker"));
        } catch (ReflectiveOperationException ignored) {
            queue = -1;
        }
        return "explorer=" + graph.explorerRowsForTest().size()
                + " membership=" + graph.selectedMembershipForTest().size()
                + " status=" + graph.graphStatusForTest()
                + " series=" + series
                + " listeners=" + listenerCount(graph)
                + " queryQueue=" + queue;
    }

    private static ExecutorService executor(GraphController graph, String fieldName) throws ReflectiveOperationException {
        Field field = GraphController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (ExecutorService) field.get(graph);
    }

    private static void note(String name, long[] nanos) {
        note(name, nanos, nanos.length);
    }

    private static void note(String name, long[] nanos, int count) {
        int n = Math.max(0, Math.min(count, nanos.length));
        if (n == 0) {
            LINES.add(name + " n=0");
            return;
        }
        long[] ms = new long[n];
        for (int i = 0; i < n; i++) {
            ms[i] = nanos[i] / 1_000_000L;
        }
        long[] sorted = ms.clone();
        Arrays.sort(sorted);
        LINES.add(name
                + " n=" + n
                + " p50=" + pct(sorted, 50)
                + " p95=" + pct(sorted, 95)
                + " p99=" + pct(sorted, 99)
                + " max=" + sorted[n - 1]
                + " samples=" + Arrays.toString(ms));
    }

    private static long pct(long[] sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static final class AtomicLongBox {
        private long nanos;
    }
}
