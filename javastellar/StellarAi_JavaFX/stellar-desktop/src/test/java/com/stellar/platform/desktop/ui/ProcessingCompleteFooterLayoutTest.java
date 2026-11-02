package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver.DisplayLabel;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ObservedTimingMetadata;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableView;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Processing Complete footer stays under the source table at demo resolutions. */
class ProcessingCompleteFooterLayoutTest {

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
    @Timeout(40)
    void footerStaysBelowScrollingTableAtDemoResolutions() throws Exception {
        assertFooter("1280x720", 1280, 720);
        assertFooter("1366x768", 1366, 768);
    }

    private static void assertFooter(String label, double width, double height) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            Stage stage = new Stage();
            try {
                ProcessingWorkspacePane pane = new ProcessingWorkspacePane();
                pane.backButtonForTest().setText("Review Setup");
                pane.openAnalysisButtonForTest().setText("Explore All Signals");
                pane.openAnalysisButtonForTest().setVisible(true);
                pane.openAnalysisButtonForTest().setManaged(true);
                pane.rerunButtonForTest().setVisible(true);
                pane.rerunButtonForTest().setManaged(true);
                pane.jobsTableForTest().getItems().setAll(probeRows(24));

                Scene scene = new Scene(pane, width, height);
                var css = ProcessingWorkspacePane.class.getResource("/css/stellar-desktop.css");
                assertNotNull(css, label);
                scene.getStylesheets().add(css.toExternalForm());
                stage.setScene(scene);
                stage.show();
                pane.applyCss();
                pane.layout();
                pane.resize(width, height);
                pane.layout();

                TableView<?> table = pane.jobsTableForTest();
                Region actions = (Region) pane.lookup(".processing-workspace-actions");
                assertNotNull(actions, label);
                Bounds tableBounds = table.localToScene(table.getBoundsInLocal());
                Bounds actionBounds = actions.localToScene(actions.getBoundsInLocal());

                assertTrue(tableBounds.getHeight() > 80, label + " table collapsed");
                assertTrue(tableBounds.getMaxY() <= actionBounds.getMinY() + 1,
                        label + " footer overlaps table: table " + tableBounds + " actions " + actionBounds);
                assertTrue(actionBounds.getMinY() >= 0, label);
                assertTrue(actionBounds.getMaxY() <= height + 1, label + " footer clipped");
                assertTrue(actionBounds.getMaxX() <= width + 1, label + " footer wider than window");
                assertTrue(tableBounds.getWidth() <= width + 1, label + " table wider than window");
                assertTrue(tableBounds.getHeight() < 24 * 44,
                        label + " table grew to every row instead of scrolling");

                double headerHeight = table.lookupAll(".column-header").stream()
                        .mapToDouble(node -> node.getBoundsInLocal().getHeight())
                        .max()
                        .orElse(0);
                assertTrue(headerHeight >= 16, label + " table header height " + headerHeight);

                ScrollBar vertical = verticalBar(table);
                assertNotNull(vertical, label + " vertical scrollbar");
                assertTrue(vertical.isVisible(), label + " scrollbar hidden");
                assertTrue(vertical.getMax() > vertical.getMin(), label + " scrollbar has no range");

                for (Button button : List.of(
                        pane.backButtonForTest(),
                        pane.openAnalysisButtonForTest(),
                        pane.rerunButtonForTest())) {
                    assertTrue(button.isVisible(), label + " " + button.getText());
                    assertFalse(button.isDisabled(), label + " " + button.getText());
                    Bounds bounds = button.localToScene(button.getBoundsInLocal());
                    assertTrue(bounds.getWidth() > 40 && bounds.getHeight() > 16, label + " " + button.getText());
                    assertTrue(bounds.getMinX() >= -1 && bounds.getMaxX() <= width + 1, label + " " + button.getText());
                    assertTrue(bounds.getMinY() >= actionBounds.getMinY() - 1, label + " " + button.getText());
                    assertFalse(intersects(bounds, tableBounds), label + " " + button.getText() + " covers a row");
                }
            } catch (Throwable t) {
                error.set(t);
            } finally {
                stage.close();
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS), label);
        if (error.get() != null) {
            throw new AssertionError(label, error.get());
        }
    }

    private static List<ProcessingWorkspacePane.JobView> probeRows(int count) {
        java.util.ArrayList<ProcessingWorkspacePane.JobView> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String sourceId = "00000000-0000-0000-0000-" + String.format("%012d", i);
            ProcessingJob job = new ProcessingJob(
                    ProcessingJobKey.of(sourceId, 1),
                    MeasurementSourceFormat.MF4,
                    MappingLifecycle.CONFIRMED,
                    "dbc",
                    "demo.dbc",
                    1,
                    1,
                    0,
                    10,
                    1.0,
                    new ObservedTimingMetadata(null, null, "", ""),
                    true,
                    List.of());
            DisplayLabel label = new DisplayLabel(
                    ProjectSourceId.of(sourceId),
                    "Source " + i,
                    "source-" + i + ".mf4",
                    "source-" + i + ".mf4",
                    "");
            rows.add(ProcessingWorkspacePane.JobView.fromPlan(i, job, label));
        }
        return rows;
    }

    private static ScrollBar verticalBar(TableView<?> table) {
        for (Node node : table.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == javafx.geometry.Orientation.VERTICAL) {
                return bar;
            }
        }
        return null;
    }

    private static boolean intersects(Bounds a, Bounds b) {
        return a.getMinX() < b.getMaxX()
                && a.getMaxX() > b.getMinX()
                && a.getMinY() < b.getMaxY()
                && a.getMaxY() > b.getMinY();
    }
}
