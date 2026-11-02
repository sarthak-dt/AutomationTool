package com.stellar.platform.api.support;

/**
 * Resource limits for LOCAL API / desktop worker pools.
 * Overridable via system properties; defaults favour bounded desktop use.
 */
public final class WorkerResourceLimits {

    public static final String PROP_PROCESSING_THREADS = "stellar.worker.processingThreads";
    public static final String PROP_INVENTORY_THREADS = "stellar.worker.inventoryThreads";
    public static final String PROP_QUEUE_CAPACITY = "stellar.worker.queueCapacity";
    public static final String PROP_MAX_CONCURRENT_JOBS = "stellar.worker.maxConcurrentJobs";

    private final int processingThreads;
    private final int inventoryThreads;
    private final int queueCapacity;
    private final int maxConcurrentJobs;

    public WorkerResourceLimits(
            int processingThreads,
            int inventoryThreads,
            int queueCapacity,
            int maxConcurrentJobs
    ) {
        this.processingThreads = Math.max(1, processingThreads);
        this.inventoryThreads = Math.max(1, inventoryThreads);
        this.queueCapacity = Math.max(1, queueCapacity);
        this.maxConcurrentJobs = Math.max(1, maxConcurrentJobs);
    }

    public static WorkerResourceLimits defaults() {
        return fromSystemProperties();
    }

    public static WorkerResourceLimits fromSystemProperties() {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int processing = Integer.getInteger(PROP_PROCESSING_THREADS, Math.min(2, cores));
        int inventory = Integer.getInteger(PROP_INVENTORY_THREADS, Math.min(4, cores));
        int queue = Integer.getInteger(PROP_QUEUE_CAPACITY, 32);
        int concurrent = Integer.getInteger(PROP_MAX_CONCURRENT_JOBS, processing);
        return new WorkerResourceLimits(processing, inventory, queue, concurrent);
    }

    public int processingThreads() {
        return processingThreads;
    }

    public int inventoryThreads() {
        return inventoryThreads;
    }

    public int queueCapacity() {
        return queueCapacity;
    }

    public int maxConcurrentJobs() {
        return maxConcurrentJobs;
    }
}
