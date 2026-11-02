package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.desktop.ui.WorkstationLayoutMetrics;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TableColumn;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineeringDiagnosticsLayoutTest {

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
    void findingsTableKeepsReadableMinimaAndDetailsStayUsable() throws Exception {
        runOnFx(() -> {
            EngineeringDiagnosticsPane pane = new EngineeringDiagnosticsPane();
            Scene scene = new Scene(new StackPane(pane), 1920, 1080);
            var css = EngineeringDiagnosticsPane.class.getResource("/css/stellar-desktop.css");
            if (css != null) {
                scene.getStylesheets().add(css.toExternalForm());
            }
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            pane.applyCss();
            pane.resize(1920, 1080);
            pane.layout();
            pane.applyResponsiveLayout(1920);

            assertTrue(pane.findingsTableForTest().getStyleClass().contains("diagnostics-findings-table"));
            double findingsMin = Math.max(pane.findingsTableForTest().getMinWidth(),
                    pane.findingsTableForTest().minWidth(-1));
            assertTrue(findingsMin >= WorkstationLayoutMetrics.CENTER_PANEL_MIN - 1,
                    "findings minWidth=" + findingsMin);
            assertTrue(pane.detailsPanelForTest().getMinWidth() >= WorkstationLayoutMetrics.SIDE_PANEL_MIN - 1);
            assertEquals(3, pane.splitForTest().getItems().size());
            for (TableColumn<?, ?> column : pane.findingsTableForTest().getColumns()) {
                assertTrue(column.getMinWidth() >= 78, column.getText());
            }
            assertTrue(pane.findingsTableForTest().getColumns().get(3).getMinWidth()
                    >= WorkstationLayoutMetrics.FINDINGS_SUMMARY_MIN - 1);

            pane.applyResponsiveLayout(1280);
            assertEquals(3, pane.splitForTest().getItems().size());
            assertTrue(pane.findingsDurationColumnForTest().isVisible());
            assertTrue(pane.findingsReviewColumnForTest().isVisible());

            pane.applyResponsiveLayout(1366);
            assertEquals(3, pane.splitForTest().getItems().size());
            assertTrue(pane.findingsDurationColumnForTest().isVisible());
            stage.close();
        });
    }

    @Test
    @Timeout(45)
    void layoutPassDoesNotReloadFindings() throws Exception {
        runOnFx(() -> {
            EngineeringDiagnosticsPane pane = new EngineeringDiagnosticsPane();
            AtomicInteger findingsLoads = new AtomicInteger();
            pane.findingsTableForTest().itemsProperty().addListener((obs, a, b) -> findingsLoads.incrementAndGet());
            int before = findingsLoads.get();
            pane.applyResponsiveLayout(1280);
            pane.applyResponsiveLayout(1536);
            pane.applyResponsiveLayout(1920);
            assertEquals(before, findingsLoads.get());
        });
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
