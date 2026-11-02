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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 timings. The navigation path matches production: FXML and refresh run in the
 * handler, then the next pulse applies CSS and layout. Distributions are evidence.
 */
@Timeout(value = 15, unit = TimeUnit.MINUTES)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class P0Phase4ResponsivenessTest {

    private static final int CATALOG = 400;
    private static final int REENTRY = 50;
    private static final Path OUT = Path.of("target", "p0-phase4.txt");
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
    @Order(2)
    void checkboxAndColumnLatency() throws Exception {
        SignalsScreenModel model = new SignalsScreenModel();
        model.replaceCatalog(catalog(CATALOG));
        SignalBrowserPane browser = new SignalBrowserPane();
        ActiveSignalWorkspacePane workspace = new ActiveSignalWorkspacePane();
        browser.setModel(model);
        workspace.setModel(model);
        browser.setAddToWorkspaceListener(entries -> workspace.addEntries(entries));
        HBox root = new HBox(browser, workspace);
        Stage[] shown = new Stage[1];
        JavaFxTestSupport.runOnFx("show", () -> {
            shown[0] = stage(root);
            browser.resize(480, 768);
            workspace.resize(880, 768);
            root.applyCss();
            root.layout();
        });
        JavaFxTestSupport.drainFxPulse();
        long[] dispatch = new long[16];
        long[] handler = new long[16];
        long[] pulse = new long[16];
        int[] heard = new int[1];
        for (int i = 0; i < dispatch.length; i++) {
            int index = i;
            long[] mark = new long[2];
            CountDownLatch frame = new CountDownLatch(1);
            CountDownLatch column = new CountDownLatch(1);
            browser.setAddToWorkspaceListener(entries -> {
                mark[0] = System.nanoTime();
                workspace.addEntriesAfterPaint(entries);
                mark[1] = System.nanoTime();
            });
            JavaFxTestSupport.runOnFx("check " + index, () -> {
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
                    handler[index] = mark[1] - mark[0];
                    heard[0]++;
                }
                Platform.runLater(() -> {
                    frame.countDown();
                    Platform.runLater(column::countDown);
                });
            });
            assertTrue(frame.await(5, TimeUnit.SECONDS));
            pulse[index] = mark[1] == 0 ? 0 : System.nanoTime() - mark[1];
            assertTrue(column.await(5, TimeUnit.SECONDS));
            LINES.add("checkbox-column-visible-ms=" + ((System.nanoTime() - mark[1]) / 1_000_000L));
        }
        LINES.add("checkbox-clicks-heard=" + heard[0]);
        note("checkbox-dispatch-ms", dispatch);
        note("checkbox-handler-ms", handler);
        note("checkbox-next-pulse-ms", pulse);
        long[] visible = new long[dispatch.length];
        for (int i = 0; i < visible.length; i++) {
            visible[i] = (handler[i] + pulse[i]) / 1_000_000L == 0 && handler[i] == 0
                    ? 0
                    : handler[i] + pulse[i];
        }
        note("checkbox-input-to-visible-ms", visible);

        List<SignalCatalogEntry> entries = model.displayedCatalog();
        int already = model.workspaceKeys().size();
        long[] add = new long[100];
        long[] addPulse = new long[100];
        for (int i = already; i < add.length; i++) {
            int index = i;
            CountDownLatch frame = new CountDownLatch(1);
            long[] posted = new long[1];
            JavaFxTestSupport.runOnFx("add " + index, () -> {
                long t = System.nanoTime();
                workspace.addEntries(List.of(entries.get(index)));
                add[index] = System.nanoTime() - t;
                posted[0] = System.nanoTime();
                Platform.runLater(frame::countDown);
            });
            assertTrue(frame.await(5, TimeUnit.SECONDS));
            addPulse[index] = System.nanoTime() - posted[0];
        }
        note("workspace-add-handler-ms", Arrays.copyOfRange(add, already, add.length));
        note("workspace-add-pulse-ms", Arrays.copyOfRange(addPulse, already, addPulse.length));
        LINES.add("workspace-add-first-ms=" + (add[Math.min(already, 99)] / 1_000_000L)
                + " middle-ms=" + (add[49] / 1_000_000L)
                + " hundredth-ms=" + (add[99] / 1_000_000L)
                + " hundredth-pulse-ms=" + (addPulse[99] / 1_000_000L));

        Map<SignalCatalogKey, TableColumn<?, ?>> before = columns(workspace);
        TableColumn<?, ?> keptFirst = before.get(entries.get(0).key());
        TableColumn<?, ?> keptLater = before.get(entries.get(80).key());
        int[] victims = {50, 99, 0};
        String[] labels = {"middle", "last", "first"};
        for (int i = 0; i < victims.length; i++) {
            int cycle = i;
            SignalCatalogKey key = entries.get(victims[i]).key();
            CountDownLatch frame = new CountDownLatch(1);
            long[] posted = new long[1];
            long[] remove = new long[1];
            JavaFxTestSupport.runOnFx("remove " + labels[cycle], () -> {
                long t = System.nanoTime();
                workspace.removePhysicalKeys(List.of(key));
                remove[0] = System.nanoTime() - t;
                posted[0] = System.nanoTime();
                Platform.runLater(frame::countDown);
            });
            assertTrue(frame.await(5, TimeUnit.SECONDS));
            LINES.add("workspace-remove-" + labels[cycle] + "-handler-ms=" + (remove[0] / 1_000_000L)
                    + " pulse-ms=" + ((System.nanoTime() - posted[0]) / 1_000_000L));
            if (cycle == 0) {
                Map<SignalCatalogKey, TableColumn<?, ?>> after = columns(workspace);
                assertSame(keptFirst, after.get(entries.get(0).key()));
                assertSame(keptLater, after.get(entries.get(80).key()));
                assertEquals(99, after.size());
            }
        }

        JavaFxTestSupport.runOnFx("filter", () -> {
            long t = System.nanoTime();
            browser.searchField().setText("Signal1");
            LINES.add("browser-filter-handler-ms=" + ((System.nanoTime() - t) / 1_000_000L)
                    + " shown=" + model.displayedCatalog().size());
        });
        JavaFxTestSupport.runOnFx("clear filter", () -> browser.searchField().clear());

        SignalsScreenModel bulk = new SignalsScreenModel();
        bulk.replaceCatalog(catalog(CATALOG));
        ActiveSignalWorkspacePane bulkPane = new ActiveSignalWorkspacePane();
        bulkPane.setModel(bulk);
        JavaFxTestSupport.runOnFx("select all", () -> {
            Stage extra = stage(bulkPane);
            bulkPane.resize(880, 768);
            bulkPane.applyCss();
            bulkPane.layout();
            long t = System.nanoTime();
            bulkPane.addPhysicalKeys(bulk.displayedCatalog().stream().limit(128).map(SignalCatalogEntry::key).toList());
            LINES.add("workspace-select-all-128-handler-ms=" + ((System.nanoTime() - t) / 1_000_000L)
                    + " columns=" + bulkPane.table().getColumns().size());
            extra.close();
        });
        bulk.shutdown();
        JavaFxTestSupport.runOnFx("close workspace", () -> shown[0].close());
        model.shutdown();
        runtime("after-workspace");
    }

    @Test
    @Order(1)
    void warmGraphNavigationAndColdOpen() throws Exception {
        Path temp = Files.createTempDirectory("p0-phase4");
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
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
            }
        }, "p0-phase4-catalog");
        background.setDaemon(true);
        try {
            service.newProject("p0-phase4");
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
            long[] fxml = new long[REENTRY];
            long[] refresh = new long[REENTRY];
            long[] handler = new long[REENTRY];
            long[] visible = new long[REENTRY];
            long[] interactive = new long[REENTRY];
            long[] populated = new long[REENTRY];
            int[] nodes = new int[REENTRY];
            Recording jfr = startJfr(Path.of("target", "p0-phase4-reentry.jfr"));
            for (int i = 0; i < REENTRY; i++) {
                int cycle = i;
                CountDownLatch frame = new CountDownLatch(1);
                long[] wall = new long[1];
                JavaFxTestSupport.runOnFx("reenter " + cycle, () -> {
                    wall[0] = System.nanoTime();
                    if (current[0] != null) {
                        current[0].dispose();
                        host.getChildren().clear();
                    }
                    long fxmlStart = System.nanoTime();
                    Loaded loaded = loadGraph();
                    fxml[cycle] = System.nanoTime() - fxmlStart;
                    loaded.graph().setAppService(service, null);
                    host.getChildren().setAll(loaded.root());
                    loaded.graph().onShow();
                    long refreshStart = System.nanoTime();
                    loaded.graph().refresh();
                    refresh[cycle] = System.nanoTime() - refreshStart;
                    handler[cycle] = System.nanoTime() - wall[0];
                    nodes[cycle] = host.lookupAll("*").size();
                    current[0] = loaded.graph();
                    Platform.runLater(() -> {
                        visible[cycle] = System.nanoTime() - wall[0];
                        Platform.runLater(frame::countDown);
                    });
                });
                assertTrue(frame.await(8, TimeUnit.SECONDS), "interactive " + cycle);
                interactive[cycle] = System.nanoTime() - wall[0];
                long pop = System.nanoTime();
                awaitPopulated(current[0], 4);
                populated[cycle] = System.nanoTime() - pop;
            }
            stopJfr(jfr, "reentry");
            note("graph-fxml-ms", fxml);
            note("graph-refresh-ms", refresh);
            note("graph-handler-ms", handler);
            note("graph-input-to-visible-ms", visible);
            note("graph-input-to-interactive-ms", interactive);
            note("graph-populated-ms", populated);
            note("graph-input-to-visible-warm-ms", Arrays.copyOfRange(visible, 5, REENTRY));
            note("graph-input-to-interactive-warm-ms", Arrays.copyOfRange(interactive, 5, REENTRY));
            note("graph-handler-warm-ms", Arrays.copyOfRange(handler, 5, REENTRY));
            note("graph-fxml-warm-ms", Arrays.copyOfRange(fxml, 5, REENTRY));
            LINES.add("graph-visible-warm-samples-ms=" + samplesMillis(visible, 5));
            LINES.add("graph-cold-visible-ms=" + (visible[0] / 1_000_000L)
                    + " cold-interactive-ms=" + (interactive[0] / 1_000_000L)
                    + " fxml-ms=" + (fxml[0] / 1_000_000L)
                    + " refresh-ms=" + (refresh[0] / 1_000_000L)
                    + " populated-ms=" + (populated[0] / 1_000_000L));
            LINES.add("graph-nodes=" + nodes[0] + ".." + nodes[REENTRY - 1]);
            LINES.add("catalog-reads-during-reentry=" + reads.get());
            if (current[0] != null && !selected.isEmpty()) {
                long[] legend = new long[100];
                long[] legendPulse = new long[100];
                SignalCatalogKey key = selected.get(0);
                for (int i = 0; i < legend.length; i++) {
                    int cycle = i;
                    CountDownLatch frame = new CountDownLatch(1);
                    long[] posted = new long[1];
                    JavaFxTestSupport.runOnFx("legend " + cycle, () -> {
                        long t = System.nanoTime();
                        current[0].legendToggleForTest(key);
                        legend[cycle] = System.nanoTime() - t;
                        posted[0] = System.nanoTime();
                        Platform.runLater(frame::countDown);
                    });
                    assertTrue(frame.await(5, TimeUnit.SECONDS));
                    legendPulse[cycle] = System.nanoTime() - posted[0];
                }
                note("legend-handler-ms", legend);
                note("legend-next-pulse-ms", legendPulse);
                long[] layout = new long[30];
                long[] layoutPulse = new long[30];
                for (int i = 0; i < layout.length; i++) {
                    int cycle = i;
                    CountDownLatch layoutFrame = new CountDownLatch(1);
                    long[] posted = new long[1];
                    JavaFxTestSupport.runOnFx("layout " + cycle, () -> {
                        javafx.scene.control.ToggleButton stacked =
                                (javafx.scene.control.ToggleButton) host.lookup("#stackedLayoutButton");
                        javafx.scene.control.ToggleButton overlay =
                                (javafx.scene.control.ToggleButton) host.lookup("#overlayLayoutButton");
                        javafx.scene.control.ToggleButton next = cycle % 2 == 0 ? overlay : stacked;
                        long t = System.nanoTime();
                        if (next != null) {
                            next.fire();
                        }
                        layout[cycle] = System.nanoTime() - t;
                        posted[0] = System.nanoTime();
                        Platform.runLater(layoutFrame::countDown);
                    });
                    assertTrue(layoutFrame.await(5, TimeUnit.SECONDS));
                    layoutPulse[cycle] = System.nanoTime() - posted[0];
                }
                note("layout-handler-ms", layout);
                note("layout-next-pulse-ms", layoutPulse);
                long[] toolbar = new long[20];
                long[] toolbarPulse = new long[20];
                for (int i = 0; i < toolbar.length; i++) {
                    int cycle = i;
                    CountDownLatch toolFrame = new CountDownLatch(1);
                    long[] posted = new long[1];
                    JavaFxTestSupport.runOnFx("pan " + cycle, () -> {
                        long t = System.nanoTime();
                        current[0].panLeftForTest();
                        toolbar[cycle] = System.nanoTime() - t;
                        posted[0] = System.nanoTime();
                        Platform.runLater(toolFrame::countDown);
                    });
                    assertTrue(toolFrame.await(5, TimeUnit.SECONDS));
                    toolbarPulse[cycle] = System.nanoTime() - posted[0];
                }
                note("toolbar-handler-ms", toolbar);
                note("toolbar-next-pulse-ms", toolbarPulse);
            }
            runtime("after-graph-before-gc");
            JavaFxTestSupport.runOnFx("dispose graph", () -> {
                if (current[0] != null) {
                    current[0].dispose();
                    current[0] = null;
                }
                host.getChildren().clear();
            });
            LINES.add("heap-before-diagnostic-gc=" + usedHeap());
            System.gc();
            Thread.sleep(150);
            System.gc();
            LINES.add("heap-after-diagnostic-gc=" + usedHeap()
                    + " note=diagnostic-gc-in-test-process-only");
            histogram();
        } catch (Throwable ex) {
            LINES.add("graph-error=" + ex);
            throw ex;
        } finally {
            read.set(false);
            background.join(2_000);
            LINES.add("catalog-reads-total=" + reads.get());
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
            System.clearProperty("stellarai.home");
        }
    }

    @Test
    @Order(3)
    void pacedSessionDuringAscDecode() throws Exception {
        Path home = Files.createTempDirectory("p0-phase4-session");
        DesktopTestIsolation.isolateHome(home);
        Path asc = Path.of("src", "test", "resources", "asc", "desktop_engine.asc").toAbsolutePath();
        Path dbc = Path.of("src", "test", "resources", "dbc", "desktop_engine.dbc").toAbsolutePath();
        AtomicBoolean decoding = new AtomicBoolean(true);
        AtomicInteger decodes = new AtomicInteger();
        Thread worker = new Thread(() -> {
            var gateway = new com.stellar.platform.desktop.processing.LocalProcessingGateway(
                    new com.stellar.platform.processing.MeasurementDecodePipeline(
                            com.stellar.platform.processing.ParserRegistry.load()));
            var measurement = new com.stellar.platform.desktop.project.WorkspaceFile(
                    asc, com.stellar.platform.desktop.project.FileKind.MEASUREMENT_ASC, 1);
            var definition = new com.stellar.platform.desktop.project.WorkspaceFile(
                    dbc, com.stellar.platform.desktop.project.FileKind.DEFINITION_DBC, 1);
            while (decoding.get() && decodes.get() < 40) {
                gateway.process(measurement, definition, 8, 100, null, null);
                decodes.incrementAndGet();
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(40));
            }
        }, "phase4-asc-decode");
        worker.setDaemon(true);
        worker.start();
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(com.stellar.platform.processing.ParserRegistry.load()));
        service.newProject("Phase4 Session");
        Stage[] shell = new Stage[1];
        GraphController[] current = new GraphController[1];
        GraphSignalSessionStore sessionStore = GraphSignalSessionStore.create("session.mf4", "session.dbc");
        try {
            List<CanonicalSignal> batch = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                batch.add(sample(i, 1_000_000L, i));
                batch.add(sample(i, 2_000_000L, i + 0.5));
            }
            sessionStore.accept(batch);
            service.signalsModel().replaceCatalog(sessionStore);
            List<SignalCatalogKey> selected = service.signalsModel().displayedCatalog().stream()
                    .limit(8).map(SignalCatalogEntry::key).toList();
            service.signalsModel().addToWorkspace(selected);
            long[] switches = new long[50];
            StackPane host = new StackPane();
            JavaFxTestSupport.runOnFx("session shell", () -> shell[0] = stage(host));
            for (int i = 0; i < switches.length; i++) {
                int cycle = i;
                CountDownLatch frame = new CountDownLatch(1);
                long[] wall = new long[1];
                JavaFxTestSupport.runOnFx("session graph " + cycle, () -> {
                    wall[0] = System.nanoTime();
                    if (current[0] != null) {
                        current[0].dispose();
                        host.getChildren().clear();
                    }
                    Loaded loaded = loadGraph();
                    loaded.graph().setAppService(service, null);
                    host.getChildren().setAll(loaded.root());
                    loaded.graph().onShow();
                    loaded.graph().refresh();
                    current[0] = loaded.graph();
                    Platform.runLater(frame::countDown);
                });
                assertTrue(frame.await(8, TimeUnit.SECONDS));
                switches[cycle] = System.nanoTime() - wall[0];
                if (cycle == 0 || cycle == 24 || cycle == 49) {
                    Thread.sleep(80);
                }
            }
            note("session-graph-visible-begin-ms", Arrays.copyOfRange(switches, 0, 8));
            note("session-graph-visible-middle-ms", Arrays.copyOfRange(switches, 21, 29));
            note("session-graph-visible-end-ms", Arrays.copyOfRange(switches, 42, 50));
            if (current[0] != null) {
                long[] legend = new long[100];
                SignalCatalogKey key = selected.get(0);
                for (int i = 0; i < legend.length; i++) {
                    int cycle = i;
                    JavaFxTestSupport.runOnFx("session legend " + cycle, () -> {
                        long t = System.nanoTime();
                        current[0].legendToggleForTest(key);
                        legend[cycle] = System.nanoTime() - t;
                    });
                }
                note("session-legend-begin-ms", Arrays.copyOfRange(legend, 0, 20));
                note("session-legend-end-ms", Arrays.copyOfRange(legend, 80, 100));
            }
            var saved = service.saveProject();
            LINES.add("session-save=" + saved.saved() + " " + saved.message());
            if (saved.saved()) {
                String id = service.currentProjectId();
                service.closeProject();
                var opened = service.openSavedProject(id);
                LINES.add("session-reopen=" + opened);
            }
            long[] report = new long[3];
            for (int i = 0; i < report.length; i++) {
                int cycle = i;
                JavaFxTestSupport.runOnFx("report " + cycle, () -> {
                    long t = System.nanoTime();
                    FXMLLoader loader = new FXMLLoader(
                            InvestigationController.class.getResource("/fxml/investigation-view.fxml"));
                    Parent root = loader.load();
                    InvestigationController controller = loader.getController();
                    Stage stage = new Stage();
                    stage.setScene(new Scene(root, 1280, 800));
                    stage.show();
                    controller.setAppService(service, null);
                    controller.setOpenGeneratedReportForTest(false);
                    controller.refresh();
                    report[cycle] = System.nanoTime() - t;
                    controller.dispose();
                    stage.close();
                });
            }
            note("session-report-open-ms", report);
            LINES.add("session-report-pdf=not-in-this-probe regression-covers-export");
        } finally {
            decoding.set(false);
            worker.join(5_000);
            LINES.add("session-asc-decodes=" + decodes.get());
            JavaFxTestSupport.runOnFx("session close", () -> {
                if (current[0] != null) {
                    current[0].dispose();
                }
                if (shell[0] != null) {
                    shell[0].close();
                }
            });
            service.shutdown();
            try {
                sessionStore.close();
            } catch (RuntimeException ignored) {
                // The model may already have closed this session.
            }
            DesktopTestIsolation.resetProcessState();
        }
        assertTrue(decodes.get() > 0, "ASC decode did not run");
    }

    private static String samplesMillis(long[] nanos, int from) {
        StringBuilder text = new StringBuilder();
        for (int i = from; i < nanos.length; i++) {
            if (i > from) {
                text.append(',');
            }
            text.append(nanos[i] / 1_000_000L);
        }
        return text.toString();
    }

    private static void histogram() {
        try {
            Path jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd.exe");
            if (!Files.exists(jcmd)) {
                jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd");
            }
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
            for (String line : text.split("\\R")) {
                if (kept < 18
                        || line.contains("com.stellar")
                        || line.contains("TableColumn")
                        || line.contains("javafx.scene.image")
                        || line.contains("WritableImage")
                        || line.contains("SQLite")) {
                    if (line.isBlank() || line.startsWith("num")) {
                        continue;
                    }
                    LINES.add("histogram " + line.trim());
                    kept++;
                    if (kept > 40) {
                        break;
                    }
                }
            }
            LINES.add("histogram-note=diagnostic-gc-in-test-process-only");
        } catch (Exception ex) {
            LINES.add("histogram-failed=" + ex);
        }
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
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
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
        }
        return false;
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
            if (type.startsWith("com.stellar.platform.desktop.ui.P0Phase4")) {
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
                + " committed=" + rt.totalMemory()
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
                + " max=" + sorted[ms.length - 1]);
    }

    private static long pct(long[] sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }
}
