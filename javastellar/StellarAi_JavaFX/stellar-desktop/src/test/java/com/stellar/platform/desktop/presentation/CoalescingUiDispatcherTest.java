package com.stellar.platform.desktop.presentation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoalescingUiDispatcherTest {

    @Test
    void coalescesManySubmitsIntoOnePendingTask() {
        List<Runnable> queued = new ArrayList<>();
        CoalescingUiDispatcher dispatcher = new CoalescingUiDispatcher(queued::add, 150_000_000L);
        AtomicInteger runs = new AtomicInteger();
        for (int i = 0; i < 100; i++) {
            dispatcher.submit(runs::incrementAndGet);
        }
        assertEquals(1, queued.size(), "only one pending UI task");
        queued.getFirst().run();
        assertEquals(1, runs.get());
    }

    @Test
    void immediateSubmitRunsLatestTask() {
        List<Runnable> queued = new ArrayList<>();
        CoalescingUiDispatcher dispatcher = new CoalescingUiDispatcher(queued::add, 150_000_000L);
        AtomicInteger value = new AtomicInteger();
        dispatcher.submit(() -> value.set(1));
        dispatcher.submitImmediate(() -> value.set(99));
        assertTrue(queued.size() >= 1);
        queued.getFirst().run();
        if (queued.size() > 1) {
            queued.get(1).run();
        }
        assertEquals(99, value.get());
    }

    @Test
    void rateLimitedFinalSubmitIsNotOrphaned() throws Exception {
        // Synchronous UI updater: deferred flush must still deliver the latest task.
        CoalescingUiDispatcher dispatcher = new CoalescingUiDispatcher(Runnable::run, 120_000_000L);
        AtomicReference<String> value = new AtomicReference<>("none");
        CountDownLatch ready = new CountDownLatch(1);

        dispatcher.submit(() -> value.set("progress"));
        assertEquals("progress", value.get());

        dispatcher.submit(() -> {
            value.set("READY");
            ready.countDown();
        });

        assertTrue(ready.await(2, TimeUnit.SECONDS),
                "throttled final submit must eventually flush; was orphaned as " + value.get());
        assertEquals("READY", value.get());
    }

    @Test
    void submitImmediateBypassesThrottleForTerminalPublish() {
        List<Runnable> queued = new ArrayList<>();
        CoalescingUiDispatcher dispatcher = new CoalescingUiDispatcher(queued::add, 150_000_000L);
        AtomicReference<String> value = new AtomicReference<>();
        dispatcher.submit(() -> value.set("progress"));
        queued.getFirst().run();
        dispatcher.submitImmediate(() -> value.set("READY"));
        assertTrue(queued.size() >= 2, "immediate must queue despite throttle window");
        queued.get(1).run();
        assertEquals("READY", value.get());
    }

    @Test
    void submitImmediateQueuesFlushEvenWhenACoalesceIsPending() {
        List<Runnable> queued = new ArrayList<>();
        CoalescingUiDispatcher dispatcher = new CoalescingUiDispatcher(queued::add, 10_000_000_000L);
        AtomicReference<String> value = new AtomicReference<>();
        dispatcher.submit(() -> value.set("progress"));
        assertEquals(1, queued.size());
        dispatcher.submitImmediate(() -> value.set("READY"));
        assertTrue(queued.size() >= 2, "terminal must not wait for the delayed coalesce thread");
        queued.get(0).run();
        queued.get(queued.size() - 1).run();
        assertEquals("READY", value.get());
    }
}
