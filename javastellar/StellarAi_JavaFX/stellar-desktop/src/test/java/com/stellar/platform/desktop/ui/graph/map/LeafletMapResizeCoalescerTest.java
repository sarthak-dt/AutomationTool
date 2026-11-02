package com.stellar.platform.desktop.ui.graph.map;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeafletMapResizeCoalescerTest {

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
    @Timeout(10)
    void onlyTheLastScheduledInvalidateRuns() throws Exception {
        LeafletMapResizeCoalescer coalescer = new LeafletMapResizeCoalescer();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            coalescer.schedule(runs::incrementAndGet);
            coalescer.schedule(runs::incrementAndGet);
            coalescer.schedule(() -> {
                runs.incrementAndGet();
                done.countDown();
            });
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertEquals(1, runs.get(), "resize pulses must coalesce to one invalidate");
        assertEquals(3, coalescer.generation());
    }
}
