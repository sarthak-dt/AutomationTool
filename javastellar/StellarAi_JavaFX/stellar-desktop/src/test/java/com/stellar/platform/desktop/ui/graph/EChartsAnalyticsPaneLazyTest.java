package com.stellar.platform.desktop.ui.graph;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EChartsAnalyticsPaneLazyTest {
    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException alreadyStarted) { started.countDown(); }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    void setSeriesDoesNotMaterializeWebKitUntilAnalyticsIsActivated() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean materialized = new AtomicBoolean(true);
        Platform.runLater(() -> {
            EChartsAnalyticsPane pane = new EChartsAnalyticsPane();
            pane.setSeries(List.of());
            materialized.set(pane.webViewMaterializedForTest());
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertFalse(materialized.get());
    }
}
