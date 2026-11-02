package com.stellar.platform.api.worker;

import com.stellar.platform.api.support.WorkerResourceLimits;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalWorkerBoundaryTest {

    @Test
    void inProcessWorkerIsBoundedAndNotProcessIsolated() throws Exception {
        WorkerResourceLimits limits = new WorkerResourceLimits(1, 1, 1, 1);
        try (InProcessLocalWorker worker = new InProcessLocalWorker("nft-worker", limits)) {
            assertEquals(LocalWorkerIsolation.IN_PROCESS, worker.isolation());
            assertFalse(worker.processIsolationAvailable());

            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> first = worker.submit(() -> {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            // Queue capacity 1 and one running → second submit fills queue; third rejects.
            Future<?> second = worker.submit(() -> { });
            assertThrows(RejectedExecutionException.class, () -> worker.submit(() -> { }));
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void processIsolatedFactoryStartsARealChild() {
        try (ProcessIsolatedLocalWorker worker = ProcessIsolatedLocalWorker.create()) {
            assertEquals(LocalWorkerIsolation.PROCESS_ISOLATED, worker.isolation());
            assertTrue(worker.processIsolationAvailable());
            assertTrue(worker.isAlive());
        }
    }

    @Test
    void shutdownStopsAcceptingWork() throws Exception {
        InProcessLocalWorker worker = new InProcessLocalWorker(
                "shutdown-worker", new WorkerResourceLimits(1, 1, 4, 1));
        AtomicInteger ran = new AtomicInteger();
        worker.submit(ran::incrementAndGet).get(5, TimeUnit.SECONDS);
        worker.shutdown();
        assertThrows(Exception.class, () -> worker.submit(ran::incrementAndGet).get(1, TimeUnit.SECONDS));
        assertEquals(1, ran.get());
    }
}
