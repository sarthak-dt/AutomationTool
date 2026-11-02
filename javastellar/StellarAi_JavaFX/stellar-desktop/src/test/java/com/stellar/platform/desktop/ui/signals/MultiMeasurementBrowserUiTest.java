package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.desktop.presentation.MeasurementCatalogScope;
import com.stellar.platform.desktop.presentation.MeasurementCatalogScopeTest;
import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiMeasurementBrowserUiTest {

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
    @Timeout(45)
    void measurementComboListsAllFourRecordingsAndFiltersA() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                SignalsScreenModel model = new SignalsScreenModel();
                model.replaceCatalog(MeasurementCatalogScopeTest.fourFileStore());
                SignalBrowserPane pane = new SignalBrowserPane();
                pane.setModel(model);
                pane.refreshFromModel();
                assertEquals(MeasurementCatalogScope.allMeasurementsLabel(4),
                        pane.measurementOptionsForTest().getFirst());
                assertTrue(pane.measurementOptionsForTest().contains("MF4_A.mf4"));
                assertTrue(pane.measurementOptionsForTest().contains("MF4_D.mf4"));
                assertTrue(pane.countTextForTest().contains("4 measurements"));
                pane.selectMeasurementForTest("MF4_A.mf4");
                pane.refreshFromModel();
                assertTrue(pane.catalogTable().getItems().stream()
                        .allMatch(e -> "src-A".equals(e.key().sourceFile())));
                assertFalse(pane.catalogTable().getItems().isEmpty());
                String count = pane.countTextForTest();
                assertTrue(count.matches(".*\\d+ signals? for 1 measurement.*"), count);
                assertFalse(count.toLowerCase().contains("catalog"));
                assertTrue(pane.selectedMeasurementRef().identity().equals("src-A")
                        || pane.selectedMeasurementRef().displayName().equals("MF4_A.mf4"));
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(40, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }
}
