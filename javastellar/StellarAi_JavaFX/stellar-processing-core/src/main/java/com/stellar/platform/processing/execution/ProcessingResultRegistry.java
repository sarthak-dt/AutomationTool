package com.stellar.platform.processing.execution;

import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Session-scoped independent job result registry.
 * Successful results replace prior entries only after completion (atomic put).
 * Failures do not overwrite a previously successful result for the same job key.
 * Cleared/replaced catalog handles that implement {@link AutoCloseable} are closed to
 * release graph-session resources during project switches.
 */
public final class ProcessingResultRegistry {

    private final Map<ProcessingJobKey, IndependentJobResult> byJob = new LinkedHashMap<>();
    private volatile MultiSourceProcessingResult latestBatch;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void clear() {
        for (IndependentJobResult result : List.copyOf(byJob.values())) {
            closeCatalogHandle(result);
        }
        byJob.clear();
        latestBatch = null;
        fire();
    }

    /**
     * Drops every job for one measurement. Remaining results and the last run elapsed stay
     * as they were. Catalog handles for the removed source are closed.
     */
    public int removeSource(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return 0;
        }
        int removed = 0;
        for (ProcessingJobKey key : List.copyOf(byJob.keySet())) {
            if (sourceId.equals(key.projectSourceId())) {
                closeCatalogHandle(byJob.remove(key));
                removed++;
            }
        }
        if (removed > 0 && latestBatch != null) {
            List<IndependentJobResult> kept = latestBatch.jobResults().stream()
                    .filter(result -> result != null && !sourceId.equals(result.projectSourceId()))
                    .toList();
            latestBatch = new MultiSourceProcessingResult(
                    latestBatch.planId(),
                    latestBatch.createdAt(),
                    latestBatch.status(),
                    kept,
                    latestBatch.diagnostics(),
                    latestBatch.elapsed());
        }
        if (removed > 0) {
            fire();
        }
        return removed;
    }

    public void publishBatch(MultiSourceProcessingResult batch) {
        Objects.requireNonNull(batch, "batch");
        this.latestBatch = batch;
        for (IndependentJobResult result : batch.jobResults()) {
            publishJob(result);
        }
        fire();
    }

    /** Sets the latest batch view without republishing/closing job catalog handles. */
    public void attachLatestBatch(MultiSourceProcessingResult batch) {
        this.latestBatch = Objects.requireNonNull(batch, "batch");
        fire();
    }

    /**
     * Atomically store a completed job result.
     * Failed/cancelled results do not displace an existing successful result for the same job key.
     */
    public void publishJob(IndependentJobResult result) {
        Objects.requireNonNull(result, "result");
        IndependentJobResult previous = byJob.get(result.jobKey());
        if (previous != null && previous.successful() && !result.successful()) {
            return;
        }
        if (previous != null && previous != result) {
            Object prevHandle = previous.catalogHandle().orElse(null);
            Object nextHandle = result.catalogHandle().orElse(null);
            if (prevHandle != null && prevHandle != nextHandle) {
                closeCatalogHandle(previous);
            }
        }
        byJob.put(result.jobKey(), result);
        fire();
    }

    /**
     * Drops one job, including a successful result, and closes its catalog handle.
     * Force-reprocess uses this so a stale open store cannot rewrite a newer generation.
     */
    public void removeJob(ProcessingJobKey key) {
        if (key == null) {
            return;
        }
        IndependentJobResult removed = byJob.remove(key);
        if (removed == null) {
            return;
        }
        closeCatalogHandle(removed);
        fire();
    }

    public Optional<IndependentJobResult> get(ProcessingJobKey key) {
        return Optional.ofNullable(byJob.get(key));
    }

    public Optional<IndependentJobResult> get(SourceChannelRef channel) {
        if (channel == null) {
            return Optional.empty();
        }
        return get(ProcessingJobKey.of(channel));
    }

    public List<IndependentJobResult> all() {
        return List.copyOf(byJob.values());
    }

    public Optional<MultiSourceProcessingResult> latestBatch() {
        return Optional.ofNullable(latestBatch);
    }

    private void fire() {
        for (Runnable listener : List.copyOf(listeners)) {
            listener.run();
        }
    }

    private static void closeCatalogHandle(IndependentJobResult result) {
        if (result == null) return;
        Object handle = result.catalogHandle().orElse(null);
        if (handle instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Project switch / shutdown must not fail because a disposable cache close failed.
            }
        }
    }
}
