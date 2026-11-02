package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.desktop.ui.GraphController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphStatisticsReadableColumnsTest {

    @BeforeAll
    static void startFx() throws Exception {
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
    void statisticsTablesExposeReadableHeaders() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
                loader.load();
                var ns = loader.getNamespace();
                @SuppressWarnings("unchecked")
                TableView<?> summary = (TableView<?>) ns.get("summaryStatsTable");
                @SuppressWarnings("unchecked")
                TableView<?> compare = (TableView<?>) ns.get("cursorCompareTable");
                String summaryHeaders = summary.getColumns().stream()
                        .map(GraphStatisticsReadableColumnsTest::headerText)
                        .collect(Collectors.joining("|"));
                String compareHeaders = compare.getColumns().stream()
                        .map(GraphStatisticsReadableColumnsTest::headerText)
                        .collect(Collectors.joining("|"));
                assertTrue(summaryHeaders.contains("Signal"));
                assertTrue(summaryHeaders.contains("Unit"));
                assertTrue(summaryHeaders.contains("Samples"));
                assertTrue(summaryHeaders.contains("Min"));
                assertTrue(summaryHeaders.contains("Max"));
                assertTrue(summaryHeaders.contains("Mean"));
                assertTrue(summaryHeaders.contains("RMS"));
                assertFalse(summaryHeaders.contains("Va..."));
                assertTrue(compareHeaders.contains("A Time"));
                assertTrue(compareHeaders.contains("Δt") || compareHeaders.contains("Δ"));
                assertTrue(compare.getPlaceholder().toString().contains("Cursor")
                        || ((javafx.scene.control.Label) compare.getPlaceholder()).getText().contains("Cursor"));
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static String headerText(TableColumn<?, ?> column) {
        if (column.getGraphic() instanceof javafx.scene.control.Labeled labeled) {
            return labeled.getText() == null ? "" : labeled.getText();
        }
        return column.getText() == null ? "" : column.getText();
    }
}
