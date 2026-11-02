package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ProcessingPageMode;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.processing.ParserRegistry;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replaces the retired diagnostics-pane coverage with Processing Selection host smoke tests.
 */
class ProcessingDiagnosticsPaneTest {

    @BeforeAll
    static void initJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(30)
    void processingView_loadsSelectionRoot_andSupportsReviewRoundTrip() throws Exception {
        runOnFxThread(() -> {
            Path temp = Files.createTempDirectory("proc-diag-replace");
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
            Path mf4 = temp.resolve("sample.mf4");
            Files.writeString(mf4, "stub");
            WorkspaceFile measurement = new WorkspaceFile(mf4, FileKind.MEASUREMENT_MF4, 10);
            service.workspace().addFile(measurement);
            CanChannelInventory channel = new CanChannelInventory(
                    1, "Channel 1", 42, 1, 1, 0, 0,
                    List.of(new CanMessageInventory(new CanMessageKey(1, 0x100, false), 10, 8, 8, false)));
            service.sourceInventoryModel().put(measurement.id(), new CanSourceInventory(
                    mf4, "sample.mf4", MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                    1, 42, 1, true, List.of(channel), List.of()));
            DefinitionSourceId defId = DefinitionSourceId.generate();
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    SourceChannelKey.of(measurement.projectSourceId(), 1),
                    ChannelMappingStatus.CONFIRMED,
                    ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    defId, "x.dbc", defId, "x.dbc", 1, 1, 1.0));

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/processing-view.fxml"));
            Parent root = loader.load();
            assertNotNull(root);
            ProcessingController controller = loader.getController();
            controller.setAppService(service, null);

            Scene scene = new Scene(root, 1200, 800);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            root.applyCss();
            root.layout();

            assertNotNull(root.lookup("#processing-workspace-root"));
            assertNotNull(controller.selectionPaneForTest().lookup("#review-plan-button"));

            service.processingSelectionModel().select(SourceChannelRef.of(measurement.id(), 1));
            controller.refresh();
            Button review = (Button) controller.selectionPaneForTest().lookup("#review-plan-button");
            assertNotNull(review);
            review.fire();
            assertEquals(ProcessingPageMode.PLAN_REVIEW, service.processingSelectionModel().mode());
            assertSame(controller.workspacePaneForTest(),
                    controller.processingHostForTest().getChildren().getFirst());
            assertNotNull(root.lookup("#processing-run-button"));
            stage.close();
        });
    }

    private static void runOnFxThread(FxAction action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(25, TimeUnit.SECONDS), "FX action timed out");
        if (error.get() != null) {
            if (error.get() instanceof RuntimeException re) {
                throw re;
            }
            if (error.get() instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(error.get());
        }
    }

    @FunctionalInterface
    private interface FxAction {
        void run() throws Exception;
    }
}