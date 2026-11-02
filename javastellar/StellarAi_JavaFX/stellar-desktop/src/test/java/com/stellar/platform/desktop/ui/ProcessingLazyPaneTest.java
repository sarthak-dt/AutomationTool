package com.stellar.platform.desktop.ui;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingLazyPaneTest {
    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException alreadyStarted) { started.countDown(); }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    void legacyMigrationPanesAreNotConstructedWithNormalProcessingController() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger materialized = new AtomicInteger(-1);
        Platform.runLater(() -> {
            try {
                ProcessingController controller = new ProcessingController();
                materialized.set(controller.materializedLegacyPaneCountForTest());
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(0, materialized.get());
    }
}
