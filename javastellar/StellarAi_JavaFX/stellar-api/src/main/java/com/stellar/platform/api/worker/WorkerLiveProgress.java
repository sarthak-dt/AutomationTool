package com.stellar.platform.api.worker;

import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.ProcessingStatistics;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Turns the pipeline's per-batch statistics callbacks into at most one in-job PROGRESS event per
 * interval. Intermediate snapshots are dropped, never queued; the next emitted event always
 * carries the latest frame count.
 */
final class WorkerLiveProgress implements ProcessingProgressListener {

    static final long DEFAULT_INTERVAL_NANOS = 1_000_000_000L;

    private final LocalPlanWorkerEngine.EventSink sink;
    private final String taskId;
    private final long pid = ProcessHandle.current().pid();
    private final long intervalNanos;
    private final LongSupplier clock;
    private long lastEmitNanos;
    private boolean emitted;

    WorkerLiveProgress(LocalPlanWorkerEngine.EventSink sink, String taskId) {
        this(sink, taskId, DEFAULT_INTERVAL_NANOS, System::nanoTime);
    }

    WorkerLiveProgress(LocalPlanWorkerEngine.EventSink sink, String taskId, long intervalNanos, LongSupplier clock) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.taskId = taskId;
        this.intervalNanos = intervalNanos;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onStatistics(ProcessingStatistics statistics) {
        if (statistics == null) {
            return;
        }
        long now = clock.getAsLong();
        if (emitted && now - lastEmitNanos < intervalNanos) {
            return;
        }
        emitted = true;
        lastEmitNanos = now;
        sink.emit(new WorkerMessage(
                WorkerProtocol.PROGRESS, WorkerProtocol.VERSION, null, pid,
                taskId, null, null, WorkerProtocol.STAGE_JOB_PROGRESS, statistics.sourceFramesRead(), null, null,
                null, null, null, null, null, null, null, true));
    }
}
