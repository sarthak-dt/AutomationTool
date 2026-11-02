package com.stellar.platform.api.worker;

import java.time.Instant;

/** Point-in-time worker process health. No sample payloads. */
public record WorkerHealthSnapshot(
        long pid,
        boolean alive,
        long heapUsedBytes,
        long heapMaxBytes,
        int availableProcessors,
        int activeJobs,
        int queueDepth,
        long uptimeMillis,
        Instant lastHeartbeat,
        Instant lastCheckpoint
) {
    public static WorkerHealthSnapshot dead() {
        return new WorkerHealthSnapshot(-1L, false, 0L, 0L, 0, 0, 0, 0L, null, null);
    }
}
