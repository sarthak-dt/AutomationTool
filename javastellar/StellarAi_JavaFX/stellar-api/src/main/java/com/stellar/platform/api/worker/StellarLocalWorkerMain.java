package com.stellar.platform.api.worker;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Separate-JVM entry point. No JavaFX. Binds only to the loopback address.
 */
public final class StellarLocalWorkerMain {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(true);
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final AtomicInteger QUEUE = new AtomicInteger();
    private static final AtomicReference<Instant> LAST_CHECKPOINT = new AtomicReference<>();
    private static final AtomicLong STARTED_AT = new AtomicLong(System.currentTimeMillis());
    private static final AtomicReference<String> CANCELLED = new AtomicReference<>("");
    private static final AtomicBoolean JOB_CANCEL_REQUESTED = new AtomicBoolean(false);
    private static final AtomicBoolean JOB_RUNNING = new AtomicBoolean(false);
    private static final Object WRITE_LOCK = new Object();

    private StellarLocalWorkerMain() {
    }

    public static void main(String[] args) throws Exception {
        String expectedToken = tokenFrom(args);
        long parentPid = parentPidFrom(args);
        startParentWatch(parentPid);
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
            if (server.getInetAddress().isAnyLocalAddress()) {
                throw new IllegalStateException("Worker refused to bind a wildcard address");
            }
            long pid = ProcessHandle.current().pid();
            System.out.println(WorkerProtocol.readyLine(server.getLocalPort(), pid, expectedToken));
            System.out.flush();
            try (Socket socket = server.accept();
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                socket.setTcpNoDelay(true);
                boolean authed = false;
                String line;
                while (RUNNING.get() && (line = in.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    WorkerMessage incoming;
                    try {
                        incoming = WorkerJson.MAPPER.readValue(line, WorkerMessage.class);
                    } catch (Exception e) {
                        write(out, fail(null, "INTERNAL_ERROR", "Invalid worker message: "
                                + (e.getMessage() == null ? e.toString() : e.getMessage())));
                        continue;
                    }
                    String type = incoming.type() == null ? "" : incoming.type().toUpperCase(Locale.ROOT);
                    if (!authed) {
                        if (!WorkerProtocol.HELLO.equals(type) || !Objects.equals(expectedToken, incoming.token())) {
                            write(out, fail(null, "INTERNAL_ERROR", "Authentication failed"));
                            return;
                        }
                        authed = true;
                        write(out, helloOk(pid));
                        continue;
                    }
                    try {
                        switch (type) {
                            case WorkerProtocol.PING -> write(out, WorkerMessage.ofType(WorkerProtocol.PONG));
                            case WorkerProtocol.HEALTH -> write(out, health(pid));
                            case WorkerProtocol.START_JOB -> {
                                if (!JOB_RUNNING.compareAndSet(false, true)) {
                                    write(out, fail(incoming.taskId(), "INTERNAL_ERROR", "Worker is already running a job"));
                                    break;
                                }
                                JOB_CANCEL_REQUESTED.set(false);
                                Thread job = new Thread(() -> {
                                    try {
                                        startJob(incoming, out);
                                    } catch (Exception e) {
                                        try {
                                            write(out, fail(incoming.taskId(), "INTERNAL_ERROR",
                                                    e.getMessage() == null ? e.toString() : e.getMessage()));
                                        } catch (IOException ignored) {
                                        }
                                    } finally {
                                        JOB_RUNNING.set(false);
                                    }
                                }, "stellar-worker-job");
                                job.setDaemon(true);
                                job.start();
                            }
                            case WorkerProtocol.CANCEL_JOB -> {
                                CANCELLED.set(incoming.taskId() == null ? "" : incoming.taskId());
                                JOB_CANCEL_REQUESTED.set(true);
                                write(out, WorkerMessage.ofType(WorkerProtocol.JOB_STATUS).withTaskId(incoming.taskId()));
                            }
                            case WorkerProtocol.QUERY_JOB -> write(out, WorkerMessage.ofType(WorkerProtocol.JOB_STATUS)
                                    .withTaskId(incoming.taskId()));
                            case WorkerProtocol.SHUTDOWN -> {
                                write(out, WorkerMessage.ofType(WorkerProtocol.SHUTDOWN_OK));
                                RUNNING.set(false);
                            }
                            default -> write(out, fail(incoming.taskId(), "INTERNAL_ERROR", "Unknown command " + type));
                        }
                    } catch (Exception e) {
                        write(out, fail(incoming.taskId(), "INTERNAL_ERROR",
                                e.getMessage() == null ? e.toString() : e.getMessage()));
                    }
                }
            }
        }
    }

    private static void startJob(WorkerMessage incoming, BufferedWriter out) throws IOException {
        ProcessingTaskDescriptor task = incoming.task();
        if (task == null) {
            write(out, fail(incoming.taskId(), "INTERNAL_ERROR", "START_JOB missing task descriptor"));
            return;
        }
        QUEUE.incrementAndGet();
        ACTIVE.incrementAndGet();
        QUEUE.decrementAndGet();
        try {
            write(out, WorkerMessage.ofType(WorkerProtocol.JOB_ACCEPTED).withTaskId(task.taskId()));
            write(out, WorkerMessage.ofType(WorkerProtocol.JOB_STARTED).withTaskId(task.taskId()));
            if (task.kind() == ProcessingTaskDescriptor.Kind.PLAN) {
                LocalPlanWorkerEngine.Outcome outcome = new LocalPlanWorkerEngine().execute(
                        task,
                        message -> {
                            try {
                                if (WorkerProtocol.CHECKPOINT.equals(message.type())
                                        || WorkerProtocol.CHECKPOINT_COMMITTED.equals(message.type())) {
                                    LAST_CHECKPOINT.set(Instant.now());
                                }
                                write(out, message);
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                        },
                        JOB_CANCEL_REQUESTED);
                LAST_CHECKPOINT.set(Instant.now());
                write(out, WorkerMessage.ofType(WorkerProtocol.COMPLETED)
                        .withTaskId(task.taskId())
                        .withToken(outcome.toJson()));
                return;
            }
            if (task.kind() == ProcessingTaskDescriptor.Kind.SLEEP && task.sleepMillis() > 0L) {
                long end = System.currentTimeMillis() + task.sleepMillis();
                while (System.currentTimeMillis() < end && RUNNING.get()) {
                    if (task.taskId().equals(CANCELLED.get())) {
                        write(out, WorkerMessage.ofType(WorkerProtocol.JOB_CANCELLED).withTaskId(task.taskId()));
                        return;
                    }
                    Thread.sleep(Math.min(50L, Math.max(1L, end - System.currentTimeMillis())));
                }
            }
            write(out, new WorkerMessage(
                    WorkerProtocol.PROGRESS, WorkerProtocol.VERSION, null, ProcessHandle.current().pid(),
                    task.taskId(), null, 1.0d, "COMPLETE", 0L, null, null,
                    null, null, null, ACTIVE.get(), QUEUE.get(), null, null, true));
            LAST_CHECKPOINT.set(Instant.now());
            write(out, new WorkerMessage(
                    WorkerProtocol.CHECKPOINT, WorkerProtocol.VERSION, null, ProcessHandle.current().pid(),
                    task.taskId(), null, 1.0d, "COMPLETE", 0L, null, null,
                    null, null, null, ACTIVE.get(), QUEUE.get(), null, Instant.now().toString(), true));
            write(out, WorkerMessage.ofType(WorkerProtocol.COMPLETED).withTaskId(task.taskId()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            write(out, fail(task.taskId(), "WORKER_UNRESPONSIVE", "Interrupted"));
        } catch (Exception ex) {
            String classification = classify(ex);
            write(out, fail(task.taskId(), classification, ex.getMessage() == null ? ex.toString() : ex.getMessage()));
        } catch (Throwable fatal) {
            write(out, fail(task.taskId(), "INTERNAL_ERROR", fatal.getClass().getSimpleName() + ": "
                    + (fatal.getMessage() == null ? fatal.toString() : fatal.getMessage())));
        } finally {
            ACTIVE.decrementAndGet();
        }
    }

    private static String classify(Exception ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("corrupt") || message.contains("malformed")) {
            return "CORRUPT_INPUT";
        }
        if (message.contains("not found") || message.contains("missing")) {
            return "INPUT_MISSING";
        }
        if (ex instanceof java.io.UncheckedIOException || message.contains("i/o") || message.contains("access denied")) {
            return "TRANSIENT_IO";
        }
        return "INTERNAL_ERROR";
    }

    private static WorkerMessage helloOk(long pid) {
        return new WorkerMessage(
                WorkerProtocol.HELLO_OK, WorkerProtocol.VERSION, null, pid, null, null, null, null, null,
                null, null, usedHeap(), maxHeap(), Runtime.getRuntime().availableProcessors(),
                ACTIVE.get(), QUEUE.get(), uptime(), null, true);
    }

    private static WorkerMessage health(long pid) {
        Instant checkpoint = LAST_CHECKPOINT.get();
        return new WorkerMessage(
                WorkerProtocol.HEALTH_OK, WorkerProtocol.VERSION, null, pid, null, null, null, null, null,
                null, null, usedHeap(), maxHeap(), Runtime.getRuntime().availableProcessors(),
                ACTIVE.get(), QUEUE.get(), uptime(),
                checkpoint == null ? null : checkpoint.toString(), true);
    }

    private static WorkerMessage fail(String taskId, String classification, String message) {
        return new WorkerMessage(
                WorkerProtocol.FAILED, WorkerProtocol.VERSION, null, ProcessHandle.current().pid(),
                taskId, null, null, null, null, message, classification,
                null, null, null, ACTIVE.get(), QUEUE.get(), uptime(), null, true);
    }

    private static void write(BufferedWriter out, WorkerMessage message) throws IOException {
        synchronized (WRITE_LOCK) {
            out.write(WorkerJson.MAPPER.writeValueAsString(message));
            out.write('\n');
            out.flush();
        }
    }

    private static String tokenFrom(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--token".equals(args[i])) {
                return args[i + 1];
            }
        }
        String env = System.getenv("STELLAR_WORKER_TOKEN");
        if (env == null || env.isBlank()) {
            throw new IllegalStateException("Worker token is required");
        }
        return env;
    }

    static long parentPidFrom(String[] args) {
        if (args == null) {
            return -1L;
        }
        for (int i = 0; i < args.length - 1; i++) {
            if ("--parent-pid".equals(args[i])) {
                try {
                    return Long.parseLong(args[i + 1]);
                } catch (NumberFormatException ex) {
                    return -1L;
                }
            }
        }
        return -1L;
    }

    static void startParentWatch(long parentPid) {
        if (parentPid <= 0L) {
            return;
        }
        Thread watch = new Thread(() -> {
            while (RUNNING.get()) {
                boolean alive = ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false);
                if (!alive) {
                    RUNNING.set(false);
                    System.exit(0);
                }
                try {
                    Thread.sleep(750L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "stellar-parent-watch");
        watch.setDaemon(true);
        watch.start();
    }

    private static long usedHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long maxHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
    }

    private static long uptime() {
        return System.currentTimeMillis() - STARTED_AT.get();
    }
}
