package com.stellar.platform.api.worker;

/**
 * Versioned local IPC contract. Messages are single-line JSON objects, never Java serialization.
 */
public final class WorkerProtocol {

    public static final int VERSION = 1;
    public static final String READY_PREFIX = "STELLAR_WORKER_READY";

    public static final String HELLO = "HELLO";
    public static final String HELLO_OK = "HELLO_OK";
    public static final String PING = "PING";
    public static final String PONG = "PONG";
    public static final String HEALTH = "HEALTH";
    public static final String HEALTH_OK = "HEALTH_OK";
    public static final String START_JOB = "START_JOB";
    public static final String CANCEL_JOB = "CANCEL_JOB";
    public static final String QUERY_JOB = "QUERY_JOB";
    public static final String JOB_STATUS = "JOB_STATUS";
    public static final String PROGRESS = "PROGRESS";
    public static final String CHECKPOINT = "CHECKPOINT";
    public static final String CHECKPOINT_COMMITTED = "CHECKPOINT_COMMITTED";
    public static final String COMPLETED = "COMPLETED";
    public static final String JOB_ACCEPTED = "JOB_ACCEPTED";
    public static final String JOB_STARTED = "JOB_STARTED";
    public static final String JOB_WARNING = "JOB_WARNING";
    public static final String JOB_COMPLETED = "JOB_COMPLETED";
    public static final String JOB_FAILED = "JOB_FAILED";
    public static final String JOB_CANCELLED = "JOB_CANCELLED";
    public static final String WORKER_HEALTH = "WORKER_HEALTH";
    public static final String WARNING = "WARNING";
    public static final String FAILED = "FAILED";
    public static final String SHUTDOWN = "SHUTDOWN";
    public static final String SHUTDOWN_OK = "SHUTDOWN_OK";

    /** PROGRESS stage for throttled in-job updates; {@code batchSequence} carries source frames read. */
    public static final String STAGE_JOB_PROGRESS = "JOB_PROGRESS";
    /** PROGRESS stage when a job starts; {@code message} carries the job key. */
    public static final String STAGE_JOB_STARTED = "JOB_STARTED";

    public static boolean isTerminal(String type) {
        if (type == null) {
            return false;
        }
        return COMPLETED.equals(type)
                || JOB_COMPLETED.equals(type)
                || FAILED.equals(type)
                || JOB_FAILED.equals(type)
                || JOB_CANCELLED.equals(type);
    }

    /** True when the worker finished the task successfully (warnings may still be present). */
    public static boolean isSuccessfulTerminal(String type) {
        return COMPLETED.equals(type) || JOB_COMPLETED.equals(type);
    }

    private WorkerProtocol() {
    }

    public static String readyLine(int port, long pid, String token) {
        return READY_PREFIX + " port=" + port + " pid=" + pid + " token=" + token + " protocol=" + VERSION;
    }
}
