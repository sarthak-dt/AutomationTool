package com.stellar.platform.api.worker;

import com.stellar.platform.api.support.BoundedExecutors;
import com.stellar.platform.api.support.WorkerResourceLimits;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process local worker: bounded thread pool in the same JVM as the caller.
 * Does <strong>not</strong> claim separate-process isolation.
 */
public final class InProcessLocalWorker implements LocalWorkerBoundary {

    private final ExecutorService executor;
    private final AtomicInteger submitted = new AtomicInteger();
    private final String name;

    public InProcessLocalWorker(String name, WorkerResourceLimits limits) {
        this.name = Objects.requireNonNullElse(name, "stellar-local-worker");
        WorkerResourceLimits safe = limits == null ? WorkerResourceLimits.defaults() : limits;
        this.executor = BoundedExecutors.newFixed(
                this.name,
                Math.min(safe.processingThreads(), safe.maxConcurrentJobs()),
                safe.queueCapacity()
        );
    }

    public static InProcessLocalWorker forProcessing() {
        return new InProcessLocalWorker("stellar-api-process", WorkerResourceLimits.defaults());
    }

    public static InProcessLocalWorker forInventory() {
        WorkerResourceLimits limits = WorkerResourceLimits.defaults();
        return new InProcessLocalWorker(
                "stellar-api-inventory",
                new WorkerResourceLimits(
                        limits.inventoryThreads(),
                        limits.inventoryThreads(),
                        limits.queueCapacity(),
                        limits.inventoryThreads()
                )
        );
    }

    @Override
    public LocalWorkerIsolation isolation() {
        return LocalWorkerIsolation.IN_PROCESS;
    }

    @Override
    public boolean processIsolationAvailable() {
        return false;
    }

    @Override
    public Future<?> submit(Runnable task) {
        Objects.requireNonNull(task, "task");
        submitted.incrementAndGet();
        return executor.submit(() -> {
            try {
                task.run();
            } finally {
                submitted.decrementAndGet();
            }
        });
    }

    @Override
    public int activeApproximate() {
        if (executor instanceof ThreadPoolExecutor tpe) {
            return tpe.getActiveCount() + tpe.getQueue().size();
        }
        return Math.max(0, submitted.get());
    }

    @Override
    public void shutdown() {
        BoundedExecutors.shutdownQuietly(executor, name, 5L);
    }

    /** Package/test access to the underlying pool. */
    public ExecutorService executor() {
        return executor;
    }
}
