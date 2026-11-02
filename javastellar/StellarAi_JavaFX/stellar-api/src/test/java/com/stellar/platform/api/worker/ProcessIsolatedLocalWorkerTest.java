package com.stellar.platform.api.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessIsolatedLocalWorkerTest {

    @Test
    @Timeout(30)
    void childProcessHasDifferentPidAndLoopbackOnly() throws Exception {
        try (ProcessIsolatedLocalWorker worker = ProcessIsolatedLocalWorker.create()) {
            assertEquals(LocalWorkerIsolation.PROCESS_ISOLATED, worker.isolation());
            assertTrue(worker.processIsolationAvailable());
            assertTrue(worker.isAlive());
            assertNotEquals(ProcessHandle.current().pid(), worker.workerPid());
            assertTrue(worker.ping());
            WorkerHealthSnapshot health = worker.health();
            assertTrue(health.alive());
            assertEquals(worker.workerPid(), health.pid());
            assertTrue(health.heapMaxBytes() > 0L);
            assertTrue(InetAddress.getLoopbackAddress().isLoopbackAddress());
            WorkerMessage done = worker.submitTask(ProcessingTaskDescriptor.noop()).get(10, TimeUnit.SECONDS);
            assertEquals(WorkerProtocol.COMPLETED, done.type());
        }
    }

    @Test
    @Timeout(30)
    void healthDuringSubmitTaskDoesNotStealCompleted() throws Exception {
        try (ProcessIsolatedLocalWorker worker = ProcessIsolatedLocalWorker.create()) {
            Thread pinger = new Thread(() -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (System.nanoTime() < deadline && worker.isAlive()) {
                    worker.health();
                    worker.ping();
                    try {
                        Thread.sleep(20L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "stellar-health-during-task");
            pinger.setDaemon(true);
            pinger.start();
            WorkerMessage done = worker.submitTask(ProcessingTaskDescriptor.sleep(400L)).get(15, TimeUnit.SECONDS);
            pinger.join(2_000L);
            assertEquals(WorkerProtocol.COMPLETED, done.type(),
                    "HEALTH/PING must not consume the task COMPLETED message");
        }
    }

    @Test
    @Timeout(30)
    void runnableSubmitIsRejectedAndCrashIsVisible() throws Exception {
        ProcessIsolatedLocalWorker worker = ProcessIsolatedLocalWorker.create();
        try {
            assertThrows(UnsupportedOperationException.class, () -> worker.submit(() -> { }));
            long pid = worker.workerPid();
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            Thread.sleep(300L);
            assertFalse(worker.isAlive());
            assertFalse(worker.health().alive());
        } finally {
            worker.shutdown();
        }
    }

    @Test
    @Timeout(30)
    void shutdownStopsTheChild() {
        ProcessIsolatedLocalWorker worker = ProcessIsolatedLocalWorker.create();
        long pid = worker.workerPid();
        worker.shutdown();
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
}
