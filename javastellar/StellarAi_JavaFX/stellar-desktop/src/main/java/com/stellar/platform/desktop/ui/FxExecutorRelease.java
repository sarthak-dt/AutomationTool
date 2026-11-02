package com.stellar.platform.desktop.ui;

import javafx.application.Platform;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounds how long the JavaFX application thread will wait for a background worker.
 * A file or SQLite call does not abort on interrupt, so a multi-second join on the
 * application thread freezes clicks and page changes until the join times out.
 *
 * <p>Several pools share one deadline. A stuck pool is counted until it actually
 * terminates; the application thread does not stay parked for each pool in turn.</p>
 */
public final class FxExecutorRelease {

    /** Visible-feedback budget shared by every pool shut down in one call. */
    public static final long APPLICATION_THREAD_BUDGET_MS = 20L;

    /** Off the application thread, tests and process shutdown may wait for a clean close. */
    public static final long BACKGROUND_BUDGET_MS = 2_000L;

    private static final AtomicInteger ABANDONED_POOLS = new AtomicInteger();

    private FxExecutorRelease() {
    }

    /** Pools that missed the join budget and have not terminated yet. */
    public static int abandonedPools() {
        return ABANDONED_POOLS.get();
    }

    public static void shutdown(ExecutorService executor) {
        shutdownAll(executor);
    }

    public static void shutdownAll(ExecutorService... executors) {
        if (executors == null || executors.length == 0) {
            return;
        }
        List<ExecutorService> pending = new ArrayList<>();
        for (ExecutorService executor : executors) {
            if (executor == null || executor.isShutdown()) {
                continue;
            }
            executor.shutdownNow();
            pending.add(executor);
        }
        if (pending.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                onApplicationThread() ? APPLICATION_THREAD_BUDGET_MS : BACKGROUND_BUDGET_MS);
        List<ExecutorService> stillRunning = new ArrayList<>();
        for (ExecutorService executor : pending) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                stillRunning.add(executor);
                continue;
            }
            try {
                if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                    stillRunning.add(executor);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                stillRunning.add(executor);
                int from = pending.indexOf(executor) + 1;
                for (int i = from; i < pending.size(); i++) {
                    stillRunning.add(pending.get(i));
                }
                break;
            }
        }
        if (stillRunning.isEmpty()) {
            return;
        }
        ABANDONED_POOLS.addAndGet(stillRunning.size());
        Thread watcher = new Thread(() -> {
            for (ExecutorService executor : stillRunning) {
                try {
                    executor.awaitTermination(30, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    ABANDONED_POOLS.decrementAndGet();
                }
            }
        }, "stellar-executor-release");
        watcher.setDaemon(true);
        watcher.start();
    }

    public static boolean onApplicationThread() {
        try {
            return Platform.isFxApplicationThread();
        } catch (IllegalStateException toolkitNotStarted) {
            return false;
        }
    }
}
