package com.stellar.platform.api.support;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded worker pools — no unbounded {@code CachedThreadPool} growth.
 */
public final class BoundedExecutors {

    private BoundedExecutors() {
    }

    public static ExecutorService newFixed(
            String threadNamePrefix,
            int maxThreads,
            int queueCapacity
    ) {
        Objects.requireNonNull(threadNamePrefix, "threadNamePrefix");
        int threads = Math.max(1, maxThreads);
        int queue = Math.max(1, queueCapacity);
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, threadNamePrefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads,
                threads,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue),
                factory,
                (task, pool) -> {
                    throw new RejectedExecutionException(
                            "Worker queue full (" + queue + ") for " + threadNamePrefix
                                    + "; reduce concurrent jobs or raise stellar.worker.queueCapacity");
                }
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    public static void shutdownQuietly(ExecutorService executor, String name, long timeoutSeconds) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(Math.max(1L, timeoutSeconds), TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(Math.max(1L, timeoutSeconds), TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
