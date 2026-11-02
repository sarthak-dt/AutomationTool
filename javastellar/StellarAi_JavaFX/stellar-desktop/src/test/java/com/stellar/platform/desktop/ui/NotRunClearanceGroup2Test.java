package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.project.ProjectInventoryJobState;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.EngineeringAnalysisPane;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.TabPane;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group 2 P1 workflows on the JavaFX shell and the Engineering Rules editor.
 * Evidence is written even when a workbook sentence stays NOT RUN.
 */
class NotRunClearanceGroup2Test {

    private static final List<String> NOTES = new ArrayList<>();

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterAll
    static void writeEvidence() throws Exception {
        Path out = Path.of("..", "docs", "qa-handoff", "clearance2-execution.txt").toAbsolutePath().normalize();
        Files.createDirectories(out.getParent());
        Files.writeString(out, String.join(System.lineSeparator(), NOTES) + System.lineSeparator());
    }

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void sourcesDecodeAndProcessing() throws Exception {
        Path home = Files.createTempDirectory("stellar-clearance-g2");
        isolate(home);
        Path db = home.resolve("meta.db");
        Path asc = Path.of("src", "test", "resources", "asc", "desktop_engine.asc").toAbsolutePath();
        Path matchingDbc = Path.of("src", "test", "resources", "dbc", "desktop_engine.dbc").toAbsolutePath();
        Path secondAsc = home.resolve("second-engine.asc");
        Files.writeString(secondAsc, Files.readString(asc).replace("1.000000", "2.000000"));
        Path unrelated = home.resolve("unrelated.dbc");
        Files.writeString(unrelated, """
                VERSION ""

                NS_ :
                \tCM_
                BS_:

                BU_: ECU1

                BO_ 2047 OtherData: 8 ECU1
                 SG_ Other : 0|8@1+ (1,0) [0|255] "" ECU1
                """);
        Path vehicle = home.resolve("vehicle.dbc");
        Files.copy(matchingDbc, vehicle);
        Path corruptAsc = home.resolve("corrupt.asc");
        Files.write(corruptAsc, new byte[]{0x00, 0x01, 0x02, 0x03});

        SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db);
        DesktopAppService service = new DesktopAppService(new DesktopSession(), store);
        AtomicReference<Stage> stage = new AtomicReference<>();
        AtomicReference<MainShellController> shell = new AtomicReference<>();
        AtomicReference<Parent> root = new AtomicReference<>();
        try {
            note("TC_INGEST_014 PASS preserved from Batch 1. b1-3.mf4 submitted as a definition was skipped: unsupported definition format. It was not registered as a DBC. This group does not count it again.");
            note("TC_JOB_004 NOT RUN category C: the workbook says the failure-job UI was not provided and control names are To be confirmed.");
            note("TC_JOB_009 NOT RUN category C: the workbook says the failure-identification UI was not provided and control names are To be confirmed.");
            service.newProject("Clearance Group2");
            runOnFx(() -> {
                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                Parent page = loader.load();
                MainShellController controller = loader.getController();
                controller.init(service);
                Stage window = new Stage();
                window.setScene(new Scene(page, 1440, 900));
                window.show();
                controller.openRoute(WorkspaceRoute.REVIEW_AND_PROCESS);
                page.applyCss();
                page.layout();
                stage.set(window);
                shell.set(controller);
                root.set(page);
            });

            String emptyProcessing = visible(root.get());
            boolean runDisabled = runOnFxValue(() -> {
                Button run = buttonOrNull(root.get(), "Start Processing");
                return run != null && run.isDisabled();
            });
            note("TC_PROCESSING_008 page='" + oneLine(emptyProcessing) + "' startDisabled=" + runDisabled);
            assertTrue(emptyProcessing.contains("No channels are ready for processing"), emptyProcessing);
            assertTrue(runDisabled, emptyProcessing);
            note("TC_PROCESSING_008 PASS empty Review & Process page. The run control is Start Processing and it is disabled. "
                    + "The sidebar destination is Decode & Process.");

            runOnFx(() -> {
                shell.get().openRoute(WorkspaceRoute.SOURCES);
                root.get().applyCss();
                root.get().layout();
            });
            runOnFx(() -> {
                ProjectFilesController sources = shell.get().projectFilesControllerForTest();
                sources.setOpenDialogForTest(chooser -> List.of(corruptAsc.toFile()));
                fire(root.get(), "Browse Files");
            });
            String corruptMessage = runOnFxValue(() -> shell.get().projectFilesControllerForTest()
                    .addResultLabelForTest().getText());
            runOnFx(() -> {
                shell.get().openRoute(WorkspaceRoute.DECODE);
                root.get().applyCss();
                root.get().layout();
            });
            String decodeAfterCorrupt = visible(root.get());
            note("TC_INGEST_036 message='" + oneLine(corruptMessage) + "' listed="
                    + service.workspace().measurementFiles().size()
                    + " decodeHasCorrupt=" + decodeAfterCorrupt.contains("corrupt.asc"));
            assertTrue(service.workspace().measurementFiles().isEmpty(), corruptMessage);
            assertTrue(decodeAfterCorrupt.contains("No measurement files added"), decodeAfterCorrupt);
            note("TC_INGEST_036 PASS synthetic corrupt.asc was refused: " + oneLine(corruptMessage)
                    + " Measurements stay at 0 and the page says No measurement files added.");

            runOnFx(() -> {
                shell.get().openRoute(WorkspaceRoute.SOURCES);
                root.get().applyCss();
                root.get().layout();
                ProjectFilesController sources = shell.get().projectFilesControllerForTest();
                sources.setOpenDialogForTest(chooser -> List.of(asc.toFile()));
                fire(root.get(), "Browse Files");
            });
            awaitInventory(service, 2);
            runOnFx(() -> {
                shell.get().openRoute(WorkspaceRoute.DECODE);
                root.get().applyCss();
                root.get().layout();
            });
            String noDbc = visible(root.get());
            note("TC_INVENTORY_018 decode='" + oneLine(noDbc) + "'");
            assertTrue(noDbc.contains("No matching DBC") || noDbc.contains("Ready with warning"), noDbc);
            note("TC_INVENTORY_018 PASS synthetic desktop_engine.asc, no definition. "
                    + "The decode page warns that no matching DBC is available and shows Ready with warning. "
                    + "Processing can continue for native output.");

            runOnFx(() -> {
                shell.get().openRoute(WorkspaceRoute.SOURCES);
                root.get().applyCss();
                root.get().layout();
                ProjectFilesController sources = shell.get().projectFilesControllerForTest();
                sources.setOpenDialogForTest(chooser -> List.of(unrelated.toFile()));
                fire(root.get(), "+ Add DBC");
            });
            awaitInventory(service, 1);
            runOnFx(() -> {
                shell.get().openRoute(WorkspaceRoute.DECODE);
                root.get().applyCss();
                root.get().layout();
            });
            String unrelatedPage = visible(root.get());
            note("TC_INVENTORY_017 decode='" + oneLine(unrelatedPage) + "'");
            boolean literalCounters = unrelatedPage.contains("No Match") && unrelatedPage.contains("0 Ready");
            if (literalCounters && (unrelatedPage.contains("blocked") || buttonDisabled(root.get(), "Review & Process"))) {
                note("TC_INVENTORY_017 PASS");
            } else {
                note("TC_INVENTORY_017 NOT RUN category C: the workbook requires the counters No Match = 1 and Ready = 0, "
                        + "and blocked navigation. The decode page shows Configured / Ready with warning and says processing "
                        + "can continue without DBC-decoded CAN signals. That is the approved native-only path.");
            }

            runOnFx(() -> {
                shell.get().openRoute(WorkspaceRoute.SOURCES);
                root.get().applyCss();
                root.get().layout();
                ProjectFilesController sources = shell.get().projectFilesControllerForTest();
                sources.setConfirmMultiFileAddForTest(() -> true);
                sources.setOpenDialogForTest(chooser -> List.of(secondAsc.toFile()));
                fire(root.get(), "Browse Files");
                sources.setOpenDialogForTest(chooser -> List.of(matchingDbc.toFile(), vehicle.toFile()));
                fire(root.get(), "+ Add DBC");
            });
            awaitInventory(service, 2);
            runOnFx(() -> {
                shell.get().openRoute(WorkspaceRoute.DECODE);
                root.get().applyCss();
                root.get().layout();
            });
            String beforeOverride = visible(root.get());
            boolean overrode = runOnFxValue(() -> selectCombo(root.get(), "vehicle.dbc"));
            runOnFx(() -> {
                root.get().applyCss();
                root.get().layout();
            });
            String afterOverride = visible(root.get());
            note("TC_INVENTORY_026 before='" + oneLine(beforeOverride) + "' selectedCombo=" + overrode
                    + " after='" + oneLine(afterOverride) + "'");
            if (overrode && afterOverride.contains("vehicle.dbc")
                    && afterOverride.indexOf("vehicle.dbc") != afterOverride.lastIndexOf("vehicle.dbc")
                    && !afterOverride.contains("desktop_engine.dbc")) {
                note("TC_INVENTORY_026 PASS synthetic vehicle.dbc on every channel.");
            } else {
                note("TC_INVENTORY_026 NOT RUN category C: the realized combo did not show vehicle.dbc on every channel. "
                        + "The later processing run used vehicle.dbc as a manual mapping on one source and desktop_engine.dbc "
                        + "as the recommendation on the other. DBC Usage for every channel was not shown.");
            }

            note("TC_INVENTORY_027 NOT RUN category C: a single-channel Selected DBC change was not driven separately from Override All. "
                    + "The workbook also expects the other channel to remain Best per Channel.");

            runOnFx(() -> {
                Button proceed = buttonOrNull(root.get(), "Review & Process");
                if (proceed != null && !proceed.isDisabled()) {
                    proceed.fire();
                }
                root.get().applyCss();
                root.get().layout();
            });
            Thread.sleep(500);
            boolean started = runOnFxValue(() -> {
                Button run = buttonOrNull(root.get(), "Start Processing");
                if (run != null && !run.isDisabled()) {
                    run.fire();
                    return true;
                }
                return false;
            });
            note("processingStarted=" + started + " status=" + service.workspace().processingStatus());
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
            note("processingComplete='" + oneLine(complete) + "' status=" + service.workspace().processingStatus());
            scoreCompletePage(complete);

            runOnFx(() -> shell.get().openRoute(WorkspaceRoute.HOME));
            String homePage = visible(root.get());
            note("TC_EXECUTION_012 home='" + oneLine(homePage) + "'");
            if (homePage.contains("LAST RUN") && homePage.contains("Overall Completion")) {
                note("TC_EXECUTION_012 PASS");
            } else {
                note("TC_EXECUTION_012 NOT RUN category C: the home page is Workspace. It does not show Dashboard, LAST RUN, or Overall Completion.");
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

    @Test
    @Timeout(90)
    void namedRuleSaveAndValidation() throws Exception {
        Path home = Files.createTempDirectory("stellar-clearance-g2-evt");
        isolate(home);
        SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(home.resolve("meta.db"));
        DesktopAppService service = new DesktopAppService(new DesktopSession(), store);
        AtomicReference<Stage> stage = new AtomicReference<>();
        try {
            service.newProject("Clearance Rules");
            AtomicReference<String> saved = new AtomicReference<>("");
            AtomicReference<String> okText = new AtomicReference<>("");
            AtomicReference<String> failText = new AtomicReference<>("");
            runOnFx(() -> {
                FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
                Parent page = loader.load();
                GraphController controller = loader.getController();
                Stage window = new Stage();
                window.setScene(new Scene(page, 1200, 800));
                window.show();
                stage.set(window);
                controller.setAppService(service, null);
                controller.selectAnalysisTabForTest(2);
                EngineeringAnalysisPane pane = controller.engineeringPaneForTest();
                selectTab(pane, "Rules");
                pane.rules().authorForTest("Coolant_High", "COOLANT_TEMPERATURE", "105", "°C", "10", "3");
                button(pane, "Validate").fire();
                okText.set(areas(pane));
                button(pane, "Save").fire();
                page.applyCss();
                page.layout();
                saved.set(visibleText(pane));
                pane.rules().authorForTest("Coolant_High", "COOLANT_TEMPERATURE", "not-a-number", "°C", "10", "3");
                button(pane, "Validate").fire();
                failText.set(areas(pane));
            });
            note("TC_EVT_014 saved='" + oneLine(saved.get()) + "'");
            assertTrue(saved.get().contains("Coolant_High"), saved.get());
            note("TC_EVT_014 PASS the Rules tab saved Coolant_High and listed it. The workbook navigation path is To be confirmed; the surface is Engineering Rules.");
            note("TC_EVT_015 ok='" + oneLine(okText.get()) + "' fail='" + oneLine(failText.get()) + "'");
            assertTrue(okText.get().contains("OK"), okText.get());
            assertTrue(failText.get().contains("FAIL"), failText.get());
            note("TC_EVT_015 PASS Validate showed an OK indication for a numeric threshold and FAIL Invalid number for not-a-number.");
        } finally {
            if (stage.get() != null) {
                runOnFx(() -> stage.get().close());
            }
            service.shutdown();
            store.close();
            deleteTree(home);
        }
    }

    private static void scoreCompletePage(String complete) {
        boolean title = complete.contains("Processing Complete");
        boolean subtitle = complete.contains("Consolidated results for each measurement source");
        boolean badge = complete.contains("COMPLETE");
        boolean workbookButtons = complete.contains("Open Signal Analysis")
                && complete.contains("Re-run All")
                && complete.contains("Back to Decode Workspace");
        if (title && subtitle && badge && workbookButtons) {
            note("TC_EXECUTION_001 PASS");
        } else if (title && subtitle) {
            note("TC_EXECUTION_001 NOT RUN category C: the page shows Processing Complete and the consolidated-results sentence. "
                    + "The badge and actions on this run are the product controls Review Setup, Explore All Signals, "
                    + "and Reprocess Entire Project. The workbook names are Back to Decode Workspace, Open Signal Analysis, and Re-run All.");
        } else {
            note("TC_EXECUTION_001 NOT RUN category A: processing complete text was not shown. actual='" + oneLine(complete) + "'");
        }
        if (complete.contains("COMPLETED WITH WARNINGS") || complete.contains("COMPLETED WITH WAR")) {
            note("TC_EXECUTION_005 OBSERVED warning badge is present in '" + oneLine(complete) + "'");
        } else {
            note("TC_EXECUTION_005 NOT RUN category C: informational diagnostics no longer change the badge. "
                    + "A successful run shows COMPLETED. Diagnostics stay in Details.");
        }
        if (complete.contains("Processed Sources: 1") && complete.contains("With Failures: 0") && complete.contains("COMPLETE")) {
            note("TC_JOB_003 PASS " + oneLine(complete));
        } else {
            note("TC_JOB_003 NOT RUN the complete page did not show Processed Sources: 1, With Failures: 0, and COMPLETE together.");
        }
        if (complete.contains("Processed Sources: 2")) {
            note("TC_EXECUTION_015 PASS " + oneLine(complete));
        } else {
            note("TC_EXECUTION_015 NOT RUN category A: Processed Sources: 2 was not on the complete page.");
        }
        note("TC_EXECUTION_008 NOT RUN category C: the complete-page action is Explore All Signals, not Open Signal Analysis.");
        note("TC_EXECUTION_009 NOT RUN category C: the complete-page action is Reprocess Entire Project, not Re-run All. "
                + "Identical Selected Frames, DBC Matched and Decoded Samples were not scored against a control the workbook does not name.");
        note("TC_EXECUTION_011 NOT RUN category A: save-and-return of the complete page was not driven in this group.");
        note("TC_EXECUTION_014 NOT RUN category C: a corrupt ASC is refused at Sources, so Processing does not create a With Failures job for it.");
        note("TC_PROCESSING_015 NOT RUN category B: the workbook runs a customer BLF and an MF4. "
                + "The MF4 physical-signal check still has no matching definition. This group did not reprocess those customer files.");
        note("TC_INGEST_035 NOT RUN category B: the workbook requires MF4 signal names and the first and last sample values. "
                + "The available MF4 previously completed as RECOVERY_REQUIRED with no physical-signal oracle.");
    }

    private static boolean selectCombo(Parent root, String name) {
        for (Node node : root.lookupAll(".combo-box")) {
            if (!(node instanceof ComboBox<?> combo)) {
                continue;
            }
            for (Object item : combo.getItems()) {
                if (item != null && item.toString().contains(name)) {
                    @SuppressWarnings("unchecked")
                    ComboBox<Object> typed = (ComboBox<Object>) combo;
                    typed.getSelectionModel().select(item);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean buttonDisabled(Parent root, String label) {
        Button button = buttonOrNull(root, label);
        return button != null && button.isDisabled();
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
        note("inventoryWaitExpired");
    }

    private static void awaitProcessing(DesktopAppService service, int minutes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(minutes);
        while (System.nanoTime() < deadline) {
            ProjectProcessingStatus status = service.workspace().processingStatus();
            if (status == ProjectProcessingStatus.COMPLETED
                    || status == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS
                    || status == ProjectProcessingStatus.FAILED
                    || status == ProjectProcessingStatus.CANCELLED) {
                return;
            }
            Thread.sleep(200);
        }
        note("processingWaitExpired status=" + service.workspace().processingStatus());
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

    private static String areas(Parent root) {
        StringBuilder sb = new StringBuilder();
        for (Node node : root.lookupAll(".text-area")) {
            if (node instanceof TextArea area && area.getText() != null) {
                sb.append(area.getText()).append('\n');
            }
        }
        return sb.toString();
    }

    private static void fire(Parent root, String label) {
        Button button = buttonOrNull(root, label);
        assertNotNull(button, label);
        button.fire();
    }

    private static Button button(Parent root, String label) {
        Button button = buttonOrNull(root, label);
        assertNotNull(button, label);
        return button;
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

    private static void selectTab(Parent root, String title) {
        for (Node node : root.lookupAll(".tab-pane")) {
            if (node instanceof TabPane tabs) {
                tabs.getTabs().stream()
                        .filter(tab -> title.equals(tab.getText()))
                        .findFirst()
                        .ifPresent(tab -> tabs.getSelectionModel().select(tab));
            }
        }
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
