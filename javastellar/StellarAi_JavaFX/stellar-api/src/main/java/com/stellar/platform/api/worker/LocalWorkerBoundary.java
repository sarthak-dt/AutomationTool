package com.stellar.platform.api.worker;

import java.util.concurrent.Future;

/**
 * Boundary between UI/API adapters and heavy LOCAL processing.
 * <p>
 * Target architecture:
 * <pre>
 *   JavaFX / REST
 *        ↓
 *   LocalWorkerBoundary
 *        ↓
 *   processing engine (ProjectPlanExecutor / MeasurementDecodePipeline)
 * </pre>
 * {@link ProcessIsolatedLocalWorker} is the production-local default.
 * {@link InProcessLocalWorker} remains for tests and API-side inventory pools.
 */
public interface LocalWorkerBoundary extends AutoCloseable {

    LocalWorkerIsolation isolation();

    /** True only when a real process-isolated worker is wired (never faked). */
    boolean processIsolationAvailable();

    /**
     * Submit heavy work. Rejected when the bounded queue is full.
     */
    Future<?> submit(Runnable task);

    /** Active (queued + running) tasks approximate count. */
    int activeApproximate();

    void shutdown();

    @Override
    default void close() {
        shutdown();
    }
}
