package com.stellar.platform.storage.metadata;

/** Lifecycle of one processing batch. Only {@link #COMMITTED} batches are safe to resume past. */
public enum ProcessingBatchStatus {
    /** Decoding in progress; the artifact segment is not yet flushed. */
    OPEN,
    /** Artifact segment flushed and durable. */
    COMMITTED,
    /** Abandoned after a crash or cancel. Staging state must be discarded, never reused. */
    ABANDONED
}
