package com.stellar.platform.desktop.presentation;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

/**
 * Project-switch timings. Stages name the lifecycle step, never a customer path or signal value.
 */
public final class ProjectSwitchTrace {

    private static final System.Logger LOG = System.getLogger(ProjectSwitchTrace.class.getName());
    private static final AtomicReference<Observer> OBSERVER = new AtomicReference<>();

    private ProjectSwitchTrace() {
    }

    /** Presentation-only. One shell observer; not a second project state machine. */
    @FunctionalInterface
    public interface Observer {
        void onStage(long generation, String stage, boolean cancelled);
    }

    public static void setObserver(Observer observer) {
        OBSERVER.set(observer);
    }

    public static void clearObserver(Observer observer) {
        OBSERVER.compareAndSet(observer, null);
    }

    public static long now() {
        return System.nanoTime();
    }

    public static void stage(long generation, String stage, long startedNanos, int artifacts, boolean cancelled) {
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
        boolean fx = false;
        try {
            fx = javafx.application.Platform.isFxApplicationThread();
        } catch (IllegalStateException toolkitNotStarted) {
            fx = false;
        }
        long heapBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        boolean onFx = fx;
        LOG.log(System.Logger.Level.INFO, () -> "project-switch gen=" + generation
                + " stage=" + stage
                + " ms=" + elapsedMs
                + " thread=" + Thread.currentThread().getName()
                + " fx=" + onFx
                + " heapBytes=" + heapBytes
                + " artifacts=" + artifacts
                + " cancelled=" + cancelled);
        Observer observer = OBSERVER.get();
        if (observer == null) {
            return;
        }
        try {
            observer.onStage(generation, stage, cancelled);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "project-switch observer failed", ex);
        }
    }
}
