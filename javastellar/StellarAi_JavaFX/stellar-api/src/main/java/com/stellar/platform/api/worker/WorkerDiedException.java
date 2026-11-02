package com.stellar.platform.api.worker;

/**
 * The isolated worker JVM died while a job was in flight.
 * Callers must mark the durable job INTERRUPTED — never pretend it completed.
 */
public final class WorkerDiedException extends IllegalStateException {

    public WorkerDiedException(String message, Throwable cause) {
        super(message, cause);
    }
}
