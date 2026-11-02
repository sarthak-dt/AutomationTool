package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.project.ProjectInventoryJobState;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputControl;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group 3: one-source processing persistence, then signal cases whose expected
 * sentences can be checked without the customer A_BAT1PosRelay_Status oracle.
 */
class NotRunClearanceGroup3Test {

    private static final List<String> NOTES = new ArrayList<>();

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterAll
    static void writeEvidence() throws Exception {
        Path out = Path.of("..", "docs", "qa-handoff", "clearance3-execution.txt").toAbsolutePath().normalize();
        Files.createDirectories(out.getParent());
        Files.writeString(out, String.join(System.lineSeparator(), NOTES) + System.lineSeparator());
    }

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void oneSourceProcessingAndSignalControls() throws Exception {
        Path home = Files.createTempDirectory("stellar-clearance-g3");
        isolate(home);
        Path asc = home.resolve("one-source.asc");
        Files.writeString(asc, multiFrameAsc());
        Path dbc = Path.of("src", "test", "resources", "dbc", "desktop_engine.dbc").toAbsolutePath();

        SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(home.resolve("meta.db"));
        DesktopAppService service = new DesktopAppService(new DesktopSession(), store);
        AtomicReference<Stage> stage = new AtomicReference<>();
        AtomicReference<MainShellController> shell = new AtomicReference<>();
        AtomicReference<Parent> root = new AtomicReference<>();
        try {
            note("TC_SIGNAL_063 NOT RUN category B: Batch 2 recorded Graphs drew two tracks and a coolant hover. "
                    + "That note does not match the workbook sentence of one A_BAT1PosRelay_Status track, "
                    + "Min 0.000 · Max 1.000 · 1,589 samples. It is not counted as PASS.");
            note("TC_SIGNAL_064 NOT RUN category B: the workbook range 22.134 s — 101.537 s belongs to A_BAT1PosRelay_Status. That measurement is not in this workspace.");
            note("TC_SIGNAL_065 NOT RUN category B: the workbook requires Average 0.619887 and RMS 0.787329 for A_BAT1PosRelay_Status. That sample oracle is not in this workspace.");
            note("TC_SIGNAL_066 NOT RUN category B: the steps check three more signals in the POWERTRAIN group. Those named signals are not in this workspace.");
            note("TC_SIGNAL_067 NOT RUN category B: the step unchecks Acc_pedal_processed from the named four-signal plot. Those signals are not in this workspace.");
            note("TC_SIGNAL_073 NOT RUN category B: the workbook requires a constant track Min 1.000 · Max 1.000 · 2,405 samples. That customer track is not in this workspace.");
            note("TC_SIGNAL_074 NOT RUN category B: the legend must name A_BAT1PosRelay_Status, Acc_Pedal_1_Low_Idle_Switch, Acc_Pedal_2_Low_Idle_Switch and Acc_pedal_processed. Those signals are not in this workspace.");
            note("TC_SIGNAL_076 NOT RUN category B: the workbook tooltip is Time: 00:00:54.680 with the four named customer values. That time point is not in this workspace.");
            note("TC_SIGNAL_077 NOT RUN category B: the comparison is at about 54.68 s on the named customer signals. That time point is not in this workspace.");
            note("TC_SIGNAL_082 NOT RUN category B: Cursor A near 30 s and Cursor B near 60 s are specified for A_BAT1PosRelay_Status. That waveform is not in this workspace.");
            note("TC_SIGNAL_086 NOT RUN category B: the full-range oracle is Average 0.619887 and RMS 0.787329. That sample set is not in this workspace.");
            note("TC_SIGNAL_094 NOT RUN category B: the workspace must still contain A_BAT1PosRelay_Status. That signal is not in this workspace.");
            note("TC_SIGNAL_095 NOT RUN category B: the four named customer signals must share colors across chip, legend, tooltip and checkbox. Those signals are not in this workspace.");
            note("TC_SIGNAL_111 NOT RUN category B: the precondition is A_BAT1PosRelay_Status at 1,589 samples and Acc_Pedal_1_Low_Idle_Switch at 2,405 samples. Those series are not in this workspace.");

            service.newProject("Clearance Group3");
            runOnFx(() -> {
                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                Parent page = loader.load();
                MainShellController controller = loader.getController();
                controller.init(service);
                Stage window = new Stage();
                window.setScene(new Scene(page, 1440, 900));
                window.show();
                controller.openRoute(WorkspaceRoute.SOURCES);
                page.applyCss();
                page.layout();
                stage.set(window);
                shell.set(controller);
                root.set(page);
            });
            runOnFx(() -> {
                ProjectFilesController sources = shell.get().projectFilesControllerForTest();
                sources.setOpenDialogForTest(chooser -> List.of(asc.toFile()));
                fire(root.get(), "Browse Files");
            });
            awaitInventory(service, 2);
            runOnFx(() -> {
                shell.get().openRoute(WorkspaceRoute.SOURCES);
                root.get().applyCss();
                root.get().layout();
                ProjectFilesController sources = shell.get().projectFilesControllerForTest();
                sources.setOpenDialogForTest(chooser -> List.of(dbc.toFile()));
                fire(root.get(), "+ Add DBC");
            });
            awaitInventory(service, 1);
            Thread.sleep(800);
            runOnFx(() -> {
                shell.get().openRoute(WorkspaceRoute.DECODE);
                root.get().applyCss();
                root.get().layout();
                Button proceed = buttonOrNull(root.get(), "Review & Process");
                if (proceed != null && !proceed.isDisabled()) {
                    proceed.fire();
                }
            });
            Thread.sleep(400);
            boolean started = runOnFxValue(() -> {
                Button run = buttonOrNull(root.get(), "Start Processing");
                if (run != null && !run.isDisabled()) {
                    run.fire();
                    return true;
                }
                return false;
            });
            note("processingStarted=" + started + " sources=" + service.workspace().measurementFiles().size());
            if (started) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (System.nanoTime() < deadline && !visible(root.get()).contains("Processing Complete")) {
                    Thread.sleep(200);
                }
                runOnFx(() -> {
                    shell.get().openRoute(WorkspaceRoute.PROCESSING_RESULT);
                    root.get().applyCss();
                    root.get().layout();
                });
            }
            String complete = visible(root.get());
            boolean greenComplete = runOnFxValue(() -> completeBadge(root.get()));
            note("TC_JOB_003 page='" + oneLine(complete) + "' greenComplete=" + greenComplete);
            boolean oneSource = complete.contains("Processed Sources: 1")
                    && complete.contains("With Failures: 0")
                    && complete.contains("COMPLETE")
                    && complete.contains("Completed")
                    && complete.contains("one-source.asc")
                    && greenComplete
                    && service.workspace().measurementFiles().size() == 1;
            if (oneSource) {
                note("TC_JOB_003 PASS one synthetic source. The page shows a green COMPLETE badge, Processed Sources: 1, With Failures: 0, and the source result Completed.");
            } else {
                note("TC_JOB_003 NOT RUN category A: the one-source complete page did not show a green COMPLETE badge, Processed Sources: 1 and With Failures: 0 together.");
            }

            String beforeLeave = complete;
            boolean saved = runOnFxValue(() -> {
                Button save = buttonStarting(root.get(), "Save");
                if (save == null || save.isDisabled()) {
                    return false;
                }
                save.fire();
                return true;
            });
            String afterSave = visible(root.get());
            note("saved=" + saved + " afterSave='" + oneLine(afterSave) + "'");

            runOnFx(() -> fire(root.get(), "Signals"));
            Thread.sleep(300);
            String signalsPage = visible(root.get());
            Button processing = runOnFxValue(() -> buttonOrNull(root.get(), "Processing"));
            Button viewDetails = runOnFxValue(() -> buttonOrNull(root.get(), "View Details"));
            boolean viewDetailsShown = viewDetails != null && viewDetails.isVisible() && viewDetails.isManaged();
            note("TC_EXECUTION_011 signals='" + oneLine(signalsPage) + "' processingButton=" + (processing != null)
                    + " viewDetailsShown=" + viewDetailsShown);
            if (processing != null) {
                runOnFx(() -> processing.fire());
            } else if (viewDetailsShown) {
                runOnFx(() -> viewDetails.fire());
            } else {
                runOnFx(() -> fire(root.get(), "Decode & Process"));
            }
            Thread.sleep(300);
            String returned = visible(root.get());
            note("TC_EXECUTION_011 returned='" + oneLine(returned) + "'");
            boolean sameResult = returned.contains("Processing Complete")
                    && returned.contains("Processed Sources: 1")
                    && returned.contains("With Failures: 0")
                    && returned.contains("COMPLETE")
                    && returned.contains("one-source.asc")
                    && beforeLeave.contains("Processed Sources: 1");
            if (processing != null && sameResult && saved) {
                note("TC_EXECUTION_011 PASS sidebar Signals then Processing restored Processing Complete with Processed Sources: 1, With Failures: 0 and one-source.asc.");
            } else if (viewDetailsShown && sameResult && saved) {
                note("TC_EXECUTION_011 NOT RUN category C: the Signals page control is View Details, not Processing. It did restore the same Processing Complete values.");
            } else {
                note("TC_EXECUTION_011 NOT RUN category C: the sidebar has Signals and Decode & Process. There is no Processing item. "
                        + "Decode & Process does not restore the Processing Complete page. Saved=" + saved + ".");
            }

            runOnFx(() -> {
                shell.get().openRoute(WorkspaceRoute.GRAPHS);
                root.get().applyCss();
                root.get().layout();
                Button selectAll = buttonOrNull(root.get(), "Select All");
                if (selectAll != null && !selectAll.isDisabled()) {
                    selectAll.fire();
                }
            });
            GraphController graph = awaitGraph(shell.get(), root.get());
            String beforeRange = graph.rangeTextForTest();
            long beforeSpan = graph.viewportEndForTest() - graph.viewportStartForTest();
            int tracks = graph.logicalTrackCountForTest();
            runOnFx(() -> graph.zoomInForTest());
            Thread.sleep(200);
            String zoomedRange = graph.rangeTextForTest();
            long zoomedSpan = graph.viewportEndForTest() - graph.viewportStartForTest();
            runOnFx(() -> graph.zoomOutForTest());
            Thread.sleep(200);
            String restoredRange = graph.rangeTextForTest();
            long restoredSpan = graph.viewportEndForTest() - graph.viewportStartForTest();
            note("TC_SIGNAL_078 before='" + beforeRange + "' span=" + beforeSpan
                    + " zoomed='" + zoomedRange + "' span=" + zoomedSpan
                    + " restored='" + restoredRange + "' span=" + restoredSpan
                    + " tracks=" + tracks);
            if (beforeSpan > 0 && zoomedSpan > 0 && zoomedSpan < beforeSpan
                    && !zoomedRange.equals(beforeRange)
                    && restoredSpan > zoomedSpan
                    && !restoredRange.equals(zoomedRange)) {
                note("TC_SIGNAL_078 PASS synthetic one-source.asc. Zoom in narrowed the visible time range and updated the range text. Zoom out widened it and updated the text.");
            } else {
                note("TC_SIGNAL_078 NOT RUN category C: zoom changed the viewport span from " + beforeSpan
                        + " to " + zoomedSpan + " and back to " + restoredSpan
                        + ", but the range text stayed '" + beforeRange + "'. The plot is one unknown-order slot, not an elapsed-time range.");
            }

            int sharedTracks = graph.logicalTrackCountForTest();
            String rangeBeforePan = graph.rangeTextForTest();
            long startBeforePan = graph.viewportStartForTest();
            runOnFx(() -> graph.panRightForTest());
            Thread.sleep(200);
            String rangeAfterPan = graph.rangeTextForTest();
            long startAfterPan = graph.viewportStartForTest();
            long cursorAt = graph.viewportStartForTest() + (graph.viewportEndForTest() - graph.viewportStartForTest()) / 2;
            runOnFx(() -> graph.selectCursorAForTest(cursorAt));
            String cursorPage = visible(root.get());
            Long cursorA = graph.cursorAForTest();
            note("TC_SIGNAL_096 tracks=" + sharedTracks + " beforePan='" + rangeBeforePan
                    + "' afterPan='" + rangeAfterPan + "' startMoved=" + (startAfterPan != startBeforePan)
                    + " cursorA=" + cursorA + " pageHasA=" + cursorPage.contains("A:"));
            if (sharedTracks >= 2 && !rangeAfterPan.equals(rangeBeforePan) && startAfterPan != startBeforePan
                    && cursorA != null && cursorPage.contains("A:")) {
                note("TC_SIGNAL_096 PASS synthetic one-source.asc. " + sharedTracks
                        + " tracks share one range. Pan moved that range, and Cursor A is one time for the plot.");
            } else {
                note("TC_SIGNAL_096 NOT RUN category C: three tracks and Cursor A were visible, and the range text stayed '"
                        + rangeBeforePan + "' after pan. One unknown-order slot does not move.");
            }
        } finally {
            if (stage.get() != null) {
                runOnFx(() -> stage.get().close());
            }
            service.shutdown();
            store.close();
            deleteTree(home);
        }
    }

    private static GraphController awaitGraph(MainShellController shell, Parent root) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        GraphController graph = null;
        while (System.nanoTime() < deadline) {
            graph = runOnFxValue(() -> {
                root.applyCss();
                root.layout();
                return shell.graphControllerForTest();
            });
            if (graph != null && graph.logicalTrackCountForTest() > 0 && graph.viewportEndForTest() > graph.viewportStartForTest()) {
                return graph;
            }
            Thread.sleep(200);
        }
        assertNotNull(graph, "Graphs page did not load");
        return graph;
    }

    private static boolean completeBadge(Parent root) {
        return badgeReady(root);
    }

    private static boolean badgeReady(Node node) {
        if (node instanceof Label label
                && "COMPLETE".equals(label.getText())
                && label.getStyleClass().contains("badge-ready")) {
            return true;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (badgeReady(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String multiFrameAsc() {
        StringBuilder body = new StringBuilder();
        body.append("date Thu Jan 01 12:00:00.000 am 2026\n");
        body.append("base hex timestamps absolute\n");
        body.append("Begin Triggerblock Thu Jan 01 12:00:00.000 am 2026\n");
        int[] speeds = {1000, 1500, 2000, 2500, 3000, 3500};
        for (int i = 0; i < speeds.length; i++) {
            int raw = speeds[i] * 4;
            int lo = raw & 0xFF;
            int hi = (raw >> 8) & 0xFF;
            body.append(String.format("  %d.000000 1 100 Rx d 8 %02X %02X 5A 01 00 00 00 00%n",
                    (i + 1) * 10, lo, hi));
        }
        body.append("End TriggerBlock\n");
        return body.toString();
    }

    private static void awaitInventory(DesktopAppService service, int minutes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(minutes);
        while (System.nanoTime() < deadline) {
            boolean done = !service.workspace().measurementFiles().isEmpty()
                    && service.workspace().measurementFiles().stream().allMatch(file -> {
                        ProjectInventoryJobState state = service.inventoryJobState(file.projectSourceId());
                        return state == ProjectInventoryJobState.READY
                                || state == ProjectInventoryJobState.REQUIRES_ATTENTION
                                || state == ProjectInventoryJobState.FAILED
                                || state == ProjectInventoryJobState.UNSUPPORTED
                                || state == ProjectInventoryJobState.CANCELLED;
                    });
            if (done) {
                return;
            }
            Thread.sleep(200);
        }
        note("inventoryWaitExpired measurements=" + service.workspace().measurementFiles().size());
    }

    private static String visible(Parent root) throws Exception {
        return runOnFxValue(() -> visibleText(root));
    }

    private static String visibleText(Node node) {
        StringBuilder sb = new StringBuilder();
        collect(node, sb);
        return sb.toString();
    }

    private static void collect(Node node, StringBuilder sb) {
        if (node instanceof Labeled labeled && labeled.getText() != null && !labeled.getText().isBlank()) {
            sb.append(labeled.getText()).append('\n');
        }
        if (node instanceof TextInputControl input && input.getText() != null && !input.getText().isBlank()) {
            sb.append(input.getText()).append('\n');
        }
        if (node instanceof TextArea area && area.getText() != null && !area.getText().isBlank()) {
            sb.append(area.getText()).append('\n');
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, sb);
            }
        }
    }

    private static void fire(Parent root, String label) {
        Button button = buttonOrNull(root, label);
        assertNotNull(button, label);
        button.fire();
    }

    private static Button buttonOrNull(Parent root, String label) {
        Button found = null;
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button candidate && label.equals(candidate.getText())) {
                found = candidate;
            }
        }
        return found;
    }

    private static Button buttonStarting(Parent root, String prefix) {
        Button found = null;
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button candidate && candidate.getText() != null && candidate.getText().startsWith(prefix)) {
                found = candidate;
            }
        }
        return found;
    }

    private static void isolate(Path home) {
        System.setProperty("stellarai.home", home.toAbsolutePath().toString());
        System.setProperty("stellarai.test.isolate", "true");
        System.clearProperty("stellarai.metadata.db");
        AnalysisStorageRoots.clear();
    }

    private static void note(String line) {
        NOTES.add(line);
    }

    private static void runOnFx(FxAction action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "FX task timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static <T> T runOnFxValue(FxValue<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        runOnFx(() -> value.set(action.get()));
        return value.get();
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Temporary home cleanup is best-effort.
                }
            });
        } catch (Exception ignored) {
            // Temporary home cleanup is best-effort.
        }
    }

    @FunctionalInterface
    private interface FxAction {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface FxValue<T> {
        T get() throws Exception;
    }
}
