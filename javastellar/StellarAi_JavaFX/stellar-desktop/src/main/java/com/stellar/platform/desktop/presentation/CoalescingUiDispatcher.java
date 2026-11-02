package com.stellar.platform.desktop.presentation;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Desktop-only coalescing dispatcher. Processing continues at full speed; JavaFX
 * refresh is limited to one pending task and a human-scale minimum interval.
 *
 * <p>Important: a rate-limited {@link #submit(Runnable)} must never orphan the latest
 * task. Terminal updates should use {@link #submitImmediate(Runnable)}.
 */
public final class CoalescingUiDispatcher {

    public static final long DEFAULT_INTERVAL_NANOS = 150_000_000L;

    private final Consumer<Runnable> uiUpdater;
    private final long intervalNanos;
    private final AtomicBoolean pending = new AtomicBoolean(false);
    private final AtomicLong lastFlushNanos = new AtomicLong(0L);
    private final AtomicReference<Runnable> latest = new AtomicReference<>();

    public CoalescingUiDispatcher(Consumer<Runnable> uiUpdater) {
        this(uiUpdater, DEFAULT_INTERVAL_NANOS);
    }

    public CoalescingUiDispatcher(Consumer<Runnable> uiUpdater, long intervalNanos) {
        this.uiUpdater = Objects.requireNonNull(uiUpdater, "uiUpdater");
        this.intervalNanos = Math.max(0L, intervalNanos);
    }

    public void submit(Runnable task) {
        Objects.requireNonNull(task, "task");
        latest.set(task);
        schedule(false);
    }

    public void submitImmediate(Runnable task) {
        Objects.requireNonNull(task, "task");
        latest.set(task);
        // Terminal updates must not wait for a delayed coalesce pulse. Always
        // queue a flush; JavaFX serialises runLater so a concurrent in-flight
        // flush still observes {@link #latest}.
        pending.set(true);
        uiUpdater.accept(this::flush);
    }

    private void schedule(boolean immediate) {
        if (!pending.compareAndSet(false, true)) {
            // A flush is already queued/deferred; it will observe the updated {@link #latest}.
            return;
        }
        if (immediate || intervalNanos == 0L) {
            uiUpdater.accept(this::flush);
            return;
        }
        long last = lastFlushNanos.get();
        long now = System.nanoTime();
        if (last != 0L && now - last < intervalNanos) {
            long waitNanos = intervalNanos - (now - last);
            Thread delayed = new Thread(() -> {
                try {
                    long millis = waitNanos / 1_000_000L;
                    int nanos = (int) (waitNanos % 1_000_000L);
                    Thread.sleep(millis, nanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                uiUpdater.accept(this::flush);
            }, "stellar-ui-coalesce");
            delayed.setDaemon(true);
            delayed.start();
            return;
        }
        uiUpdater.accept(this::flush);
    }

    private void flush() {
        try {
            Runnable task = latest.getAndSet(null);
            if (task != null) {
                task.run();
            }
            lastFlushNanos.set(System.nanoTime());
        } finally {
            pending.set(false);
            if (latest.get() != null) {
                schedule(false);
            }
        }
    }
}
