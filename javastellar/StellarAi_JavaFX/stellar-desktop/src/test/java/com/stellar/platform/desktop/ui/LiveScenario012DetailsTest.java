package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.DesktopLogging;
import com.stellar.platform.desktop.presentation.DecisionWorkspaceMode;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ProcessingPlanInputAssembler;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.testsupport.DesktopTestIsolation;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnosticCode;
import com.stellar.platform.domain.processing.plan.ProjectExecutionStatus;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenario 012 layout check with a completed processing result built in this test.
 * Does not open the shared E2E project or depend on an earlier scenario.
 */
class LiveScenario012DetailsTest {
    private static final Path EVIDENCE = Path.of(
            "D:/Reports/StellarAI_1.0.0_E2E/03_TestResults/LiveE2E/scenario-012.txt");
    private static DesktopAppService service;
    private static Stage stage;
    private static Scene scene;

    @BeforeAll
    static void startFx() throws Exception {
        Files.createDirectories(EVIDENCE.getParent());
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException already) {
            started.countDown();
        }
        assertTrue(started.await(15, TimeUnit.SECONDS));
    }

    @AfterAll
    static void shutdown() {
        try {
            if (service != null) {
                service.shutdown();
            }
            if (stage != null) {
                onFx(stage::close);
            }
        } catch (RuntimeException ignored) {
        }
        DesktopLogging.closeForTests();
        DesktopTestIsolation.resetProcessState();
    }

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void processingDetailsDialogFitsAndCopies() throws Exception {
        Files.createDirectories(EVIDENCE.getParent());
        AtomicReference<String> report = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                prepareCompletedProcessing();
                String title = processingTitle();
                if (!(title.startsWith("Processing Complete") || title.startsWith("Processing completed"))) {
                    throw new AssertionError("title was " + title);
                }
                Button opener = detailsButton();
                if (opener == null) {
                    throw new AssertionError("visible buttons: " + buttonTexts());
                }
                opener.fire();
                Stage dialog = detailsStage();
                if (dialog == null) {
                    throw new AssertionError("Processing Details did not open");
                }
                report.set(inspect(dialog));
                dialog.close();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "scenario 012 timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        String evidence = report.get();
        Files.writeString(EVIDENCE, evidence);
        assertTrue(evidence.startsWith("PASS"), evidence);
    }

    private static String inspect(Stage dialog) {
        List<String> failures = new ArrayList<>();
        Scene dialogScene = dialog.getScene();
        Parent root = dialogScene.getRoot();
        root.applyCss();
        root.layout();
        double sceneW = dialogScene.getWidth();
        double sceneH = dialogScene.getHeight();
        if (dialog.getWidth() > 1280 || dialog.getHeight() > 720) {
            failures.add("dialog exceeds 1280x720: " + dialog.getWidth() + "x" + dialog.getHeight());
        }
        if (dialog.getWidth() > stage.getWidth() + 8 || dialog.getHeight() > stage.getHeight() + 8) {
            failures.add("dialog larger than owner");
        }
        TabPane tabs = (TabPane) root.lookup(".tab-pane");
        if (tabs == null) {
            failures.add("tabs missing");
        } else {
            List<String> names = tabs.getTabs().stream().map(tab -> tab.getText()).toList();
            if (!names.contains("Summary") || !names.contains("Warnings / Diagnostics")
                    || !names.contains("Raw Log")) {
                failures.add("tabs=" + names);
            }
            tabs.getSelectionModel().select(0);
            root.applyCss();
            root.layout();
            boolean summaryText = root.lookupAll(".label").stream()
                    .filter(node -> node instanceof Label)
                    .map(node -> ((Label) node).getText())
                    .anyMatch(text -> text != null && text.length() > 2);
            if (!summaryText) {
                failures.add("summary empty");
            }
            tabs.getSelectionModel().select(1);
            root.applyCss();
            root.layout();
            TableView<?> table = null;
            for (Node node : root.lookupAll(".table-view")) {
                if (node instanceof TableView<?> candidate) {
                    table = candidate;
                    break;
                }
            }
            if (table == null || table.getItems().isEmpty()) {
                failures.add("diagnostics table empty");
            }
            tabs.getSelectionModel().select(2);
            root.applyCss();
            root.layout();
            TextArea raw = null;
            for (Node node : root.lookupAll(".text-area")) {
                if (node instanceof TextArea area) {
                    raw = area;
                    break;
                }
            }
            if (raw == null) {
                failures.add("raw log missing");
            } else {
                Bounds rawBounds = raw.localToScene(raw.getBoundsInLocal());
                if (rawBounds.getHeight() > sceneH + 2 || rawBounds.getWidth() > sceneW + 2) {
                    failures.add("raw log unbounded " + rawBounds.getWidth() + "x" + rawBounds.getHeight());
                }
                if (raw.getText() == null || raw.getText().isBlank()) {
                    failures.add("raw log blank");
                }
            }
        }
        Button copy = null;
        Button close = null;
        for (Node node : root.lookupAll(".button")) {
            if (!(node instanceof Button button) || !button.isVisible()) {
                continue;
            }
            Bounds bounds = button.localToScene(button.getBoundsInLocal());
            if (bounds.getMinX() < -1 || bounds.getMinY() < -1
                    || bounds.getMaxX() > sceneW + 1 || bounds.getMaxY() > sceneH + 1
                    || bounds.getWidth() < 8 || bounds.getHeight() < 8) {
                failures.add("clipped button '" + button.getText() + "' " + bounds);
            }
            if ("Copy Details".equals(button.getText())) {
                copy = button;
            }
            if ("Close".equals(button.getText())) {
                close = button;
            }
        }
        if (copy == null || close == null) {
            failures.add("Copy Details or Close missing");
        } else {
            copy.fire();
            String copied = Clipboard.getSystemClipboard().getString();
            if (copied == null || copied.isBlank()) {
                failures.add("clipboard empty");
            }
        }
        String header = failures.isEmpty()
                ? "PASS"
                : "FAIL " + String.join(" | ", failures);
        return header
                + " dialog=" + Math.round(dialog.getWidth()) + "x" + Math.round(dialog.getHeight())
                + " owner=" + Math.round(stage.getWidth()) + "x" + Math.round(stage.getHeight())
                + " catalog=" + (service.signalsModel().catalog() == null
                ? 0 : service.signalsModel().catalog().size());
    }

    private static Button detailsButton() {
        for (Node node : scene.getRoot().lookupAll(".button")) {
            if (node instanceof Button button && button.isVisible() && button.getText() != null) {
                String text = button.getText();
                if (text.equals("Details") || text.startsWith("Warnings")) {
                    return button;
                }
            }
        }
        return null;
    }

    private static String buttonTexts() {
        StringBuilder text = new StringBuilder();
        for (Node node : scene.getRoot().lookupAll(".button")) {
            if (node instanceof Button button && button.isVisible() && button.getText() != null) {
                text.append(button.getText()).append(" | ");
            }
        }
        return text.toString();
    }

    private static Stage detailsStage() {
        for (Window window : Window.getWindows()) {
            if (window instanceof Stage candidate && "Processing Details".equals(candidate.getTitle())) {
                return candidate;
            }
        }
        return null;
    }

    private static String processingTitle() {
        Object controller = scene == null ? null : scene.getRoot().getProperties().get("controller");
        if (controller instanceof ProcessingController processing && processing.workspacePaneForTest() != null) {
            return processing.workspacePaneForTest().titleForTest();
        }
        for (Node node : scene.getRoot().lookupAll(".label")) {
            if (node instanceof Label label && label.getText() != null
                    && (label.getText().startsWith("Processing Complete")
                    || label.getText().startsWith("Processing completed"))) {
                return label.getText();
            }
        }
        return "";
    }

    private static void prepareCompletedProcessing() throws Exception {
        Path home = Files.createTempDirectory("scenario-012-home");
        DesktopTestIsolation.isolateHome(home);
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        Path dir = Files.createTempDirectory("scenario-012-src");
        String name = "4BE883CD_00000975_00000001-63C7A554.MF4";
        Files.createDirectories(dir);
        Path path = dir.resolve(name);
        Files.writeString(path, "stub");
        WorkspaceFile file = new WorkspaceFile(path, FileKind.MEASUREMENT_MF4, 10);
        service.workspace().addFile(file);
        service.sourceInventoryModel().put(file.id(), new CanSourceInventory(
                path, name, MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                1, 40L, 1, true,
                List.of(new CanChannelInventory(1, "Channel 1", 40L, 1, 1, 0, 0,
                        List.of(new CanMessageInventory(new CanMessageKey(1, 0x7E8L, false), 40, 8, 8, false)))),
                List.of()));
        Path dbcPath = dir.resolve("scenario012.dbc");
        Files.writeString(dbcPath, "VERSION \"\"\n");
        WorkspaceFile dbc = new WorkspaceFile(dbcPath, FileKind.DEFINITION_DBC, 10);
        service.workspace().addFile(dbc);
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(file.projectSourceId(), 1),
                ChannelMappingStatus.CONFIRMED,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
                dbc.definitionSourceId(), "scenario012.dbc",
                dbc.definitionSourceId(), "scenario012.dbc",
                4, 1, 0.25));
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        var model = service.processingSelectionModel();
        model.captureDecisionOrigin(DecisionWorkspaceMode.DETAILED_MAPPING);
        model.select(SourceChannelRef.of(file.id(), 1));
        model.reviewPlan(new ProcessingPlanInputAssembler().assemble(service));
        ProjectProcessingPlan plan = model.reviewedPlan();
        model.beginExecution(plan);
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(name, "scenario012.dbc", 50);
        store.accept(List.of(new CanonicalSignal(
                "Sig012", "OBD2", 10L, 0x7E8, 1, 1.0, 1L, "rpm", null, SourceReference.ofLine(1))));
        java.util.ArrayList<IndependentJobResult> results = new java.util.ArrayList<>();
        for (ProcessingJob job : plan.jobs()) {
            IndependentJobResult result = new IndependentJobResult(
                    job.key(),
                    job.definitionSourceId().orElse(null),
                    "scenario012.dbc",
                    job.mappingLifecycle(),
                    JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                    40, 40, 40, 20, 0, 1,
                    1L, 2L, null, Duration.ofMillis(5),
                    List.of(ProcessingPlanDiagnostic.of(
                            ProcessingPlanDiagnosticCode.PIPELINE_DIAGNOSTIC,
                            SourceChannelRef.of(file.id(), job.channelId()),
                            "Frame count differed from inventory")),
                    "pipeline warning",
                    store);
            model.updateJobStatus(job.key(), result);
            results.add(result);
        }
        model.completeExecution(new MultiSourceProcessingResult(
                plan.planId(), Instant.now(), ProjectExecutionStatus.SUCCESS,
                results, List.of(), Duration.ofSeconds(1)));

        FXMLLoader loader = new FXMLLoader(ProcessingController.class.getResource("/fxml/processing-view.fxml"));
        Parent root = loader.load();
        ProcessingController processing = loader.getController();
        processing.setAppService(service, null);
        root.getProperties().put("controller", processing);
        scene = new Scene(root, 1280, 720);
        var css = ProcessingController.class.getResource("/css/stellar-desktop.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        stage = new Stage();
        stage.setScene(scene);
        stage.setWidth(1280);
        stage.setHeight(720);
        stage.show();
        root.applyCss();
        root.layout();
    }

    private static boolean waitUntil(long timeoutMs, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < end) {
            AtomicReference<Boolean> value = new AtomicReference<>(false);
            CountDownLatch latch = new CountDownLatch(1);
            Platform.runLater(() -> {
                try {
                    value.set(condition.getAsBoolean());
                } catch (Exception ignored) {
                    value.set(false);
                } finally {
                    latch.countDown();
                }
            });
            if (!latch.await(5, TimeUnit.SECONDS)) {
                return false;
            }
            if (Boolean.TRUE.equals(value.get())) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    private static void pulse() {
        try {
            waitUntil(800, () -> true);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void onFx(Runnable action) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("FX action timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
        if (error.get() != null) {
            throw new IllegalStateException(error.get());
        }
    }
}
