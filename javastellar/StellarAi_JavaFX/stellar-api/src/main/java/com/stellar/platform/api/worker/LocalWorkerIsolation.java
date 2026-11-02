package com.stellar.platform.api.worker;

/**
 * Isolation mode for the local processing worker boundary.
 */
public enum LocalWorkerIsolation {
    /** Heavy decode/plan execution shares the caller JVM (desktop or server). */
    IN_PROCESS,
    /** Separate worker JVM reached only over loopback IPC. */
    PROCESS_ISOLATED
}
