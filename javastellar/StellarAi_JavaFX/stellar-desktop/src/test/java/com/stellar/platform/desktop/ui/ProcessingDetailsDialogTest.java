package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.ProcessingDetailsPresentation;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnosticCode;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import javafx.application.Platform;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingDetailsDialogTest {

    @BeforeAll
    static void initJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        CountDownLatch configured = new CountDownLatch(1);
        Platform.runLater(() -> {
            Platform.setImplicitExit(false);
            configured.countDown();
        });
        assertTrue(configured.await(5, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(45)
    void dialogIsStructuredAndFitsLaptopScreen() throws Exception {
        runOnFx(() -> {
            ProcessingDetailsPresentation.Model model = sampleModel(12);
            Stage stage = ProcessingDetailsDialog.show(null, model);
            assertEquals("Processing Details", stage.getTitle());
            assertTrue(stage.getWidth() <= 1280 - 16);
            assertTrue(stage.getHeight() <= 720 - 16);
            assertTrue(stage.getScene().getRoot().getStyleClass().contains("processing-details-dialog"));
            TabPane tabs = (TabPane) stage.getScene().lookup(".processing-details-tabs");
            assertEquals(3, tabs.getTabs().size());
            assertEquals("Summary", tabs.getTabs().get(0).getText());
            assertEquals("Warnings / Diagnostics", tabs.getTabs().get(1).getText());
            assertEquals("Raw Log", tabs.getTabs().get(2).getText());
            @SuppressWarnings("unchecked")
            TableView<Object> table = (TableView<Object>) stage.getScene().lookup(".processing-details-table");
            assertFalse(table.getItems().isEmpty());
            assertTrue(table.getItems().size() < 12, "repeated codes are grouped");
            stage.close();
        });
    }

    @Test
    @Timeout(45)
    void escapeClosesAndCopyTextIncludesRaw() throws Exception {
        runOnFx(() -> {
            ProcessingDetailsPresentation.Model model = sampleModel(1);
            Stage stage = ProcessingDetailsDialog.show(null, model);
            assertTrue(stage.isShowing());
            stage.getScene().getRoot().fireEvent(
                    new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                            "", "", KeyCode.ESCAPE, false, false, false, false));
            assertFalse(stage.isShowing());
            String copy = ProcessingDetailsPresentation.copyText(model);
            assertTrue(copy.contains("Raw Details"));
            assertTrue(copy.contains("UNMATCHED_CAN_ID"));
            assertTrue(copy.contains("Elapsed: 00:03"));
        });
    }

    @Test
    @Timeout(45)
    void longMessageDoesNotRequireAGiantDialog() throws Exception {
        runOnFx(() -> {
            String longMsg = "Effective recovery boundary extended because the last unfinalized DT "
                    + "sector continued beyond the declared recording window. ".repeat(8);
            IndependentJobResult job = job(List.of(pipeline("EXTENDED_UNFINALIZED_DT_RECOVERY", longMsg)));
            ProcessingDetailsPresentation.Model model = ProcessingDetailsPresentation.fromJobs(
                    "Source 1", "a.mf4", "COMPLETED", "2",
                    "11_bit_OBD2 2.dbc", "10", "8", "4", "00:03", List.of(job));
            Stage stage = ProcessingDetailsDialog.show(null, model);
            assertTrue(stage.getHeight() <= 720);
            assertTrue(model.groups().getFirst().message().contains("Effective recovery"));
            stage.close();
        });
    }

    private static ProcessingDetailsPresentation.Model sampleModel(int unmatched) {
        List<ProcessingPlanDiagnostic> diags = new ArrayList<>();
        for (int i = 0; i < unmatched; i++) {
            diags.add(pipeline("UNMATCHED_CAN_ID", "no matching DBC message"));
        }
        diags.add(pipeline("OUT_OF_RANGE_PHYSICAL_VALUE", "outside min/max"));
        return ProcessingDetailsPresentation.fromJobs(
                "Source 1", "trace.mf4", "COMPLETED", "2",
                "11_bit_OBD2 2.dbc", "100", "80", "12", "00:03", List.of(job(diags)));
    }

    private static IndependentJobResult job(List<ProcessingPlanDiagnostic> diagnostics) {
        return new IndependentJobResult(
                ProcessingJobKey.of("src-a", 1), "d", "11_bit_OBD2 2.dbc",
                MappingLifecycle.CONFIRMED, JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                100, 100, 100, 80, 20, 12,
                1L, 2L, null, Duration.ofSeconds(3), diagnostics,
                "PIPELINE_DIAGNOSTIC/UNMATCHED_CAN_ID: no matching DBC message", null);
    }

    private static ProcessingPlanDiagnostic pipeline(String code, String message) {
        return ProcessingPlanDiagnostic.of(
                ProcessingPlanDiagnosticCode.PIPELINE_DIAGNOSTIC,
                SourceChannelRef.of("src-a", 1),
                code + ": " + message);
    }

    private static void runOnFx(FxBody body) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(40, TimeUnit.SECONDS), "JavaFX task timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @FunctionalInterface
    private interface FxBody {
        void run() throws Exception;
    }
}
