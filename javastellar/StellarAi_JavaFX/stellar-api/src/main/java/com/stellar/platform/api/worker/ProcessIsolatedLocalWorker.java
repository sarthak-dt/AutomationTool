package com.stellar.platform.api.worker;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real process-isolated local worker. Starts a child JVM, speaks JSON over 127.0.0.1 only,
 * and never falls back to {@link InProcessLocalWorker}.
 */
public final class ProcessIsolatedLocalWorker implements LocalWorkerBoundary {

    private final Process process;
    private final Socket socket;
    private final BufferedReader in;
    private final BufferedWriter out;
    private final long workerPid;
    private final String token;
    private final Path javaExecutable;
    private final String classpath;
    private final Thread shutdownHook;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicReference<Instant> lastHeartbeat = new AtomicReference<>(Instant.now());
    private final AtomicReference<WorkerHealthSnapshot> lastHealth =
            new AtomicReference<>(WorkerHealthSnapshot.dead());
    private final Object io = new Object();
    private final BlockingQueue<WorkerMessage> inbound = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, CompletableFuture<WorkerMessage>> controlWaiters =
            new ConcurrentHashMap<>();
    private final AtomicReference<Throwable> readerFailure = new AtomicReference<>();
    private volatile Thread reader;

    public static ProcessIsolatedLocalWorker create() {
        return create(WorkerClasspath.resolve());
    }

    public static ProcessIsolatedLocalWorker create(String classpath) {
        Objects.requireNonNull(classpath, "classpath");
        if (WorkerClasspath.isPackagedLaunch()) {
            WorkerClasspath.rejectDevelopmentPaths(classpath, "packaged worker classpath");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        Path java = WorkerJavaRuntime.executable();
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-cp");
        command.add(classpath);
        command.add(StellarLocalWorkerMain.class.getName());
        command.add("--token");
        command.add(token);
        command.add("--parent-pid");
        command.add(Long.toString(ProcessHandle.current().pid()));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot start local worker JVM using " + java + ". Processing will not fall back to in-process decode.",
                    e);
        }
        try {
            ReadyBanner ready = readReady(process, token, 15_000L);
            startMergedStdoutDrain(process);
            if (ready.pid() == ProcessHandle.current().pid()) {
                process.destroyForcibly();
                throw new IllegalStateException("Worker PID matched the desktop PID — isolation was not achieved");
            }
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), ready.port()), 5_000);
            if (socket.getInetAddress().isAnyLocalAddress()) {
                socket.close();
                process.destroyForcibly();
                throw new IllegalStateException("Worker socket is not loopback-only");
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            ProcessIsolatedLocalWorker worker = new ProcessIsolatedLocalWorker(
                    process, socket, in, out, ready.pid(), token, java, classpath);
            worker.handshake();
            worker.startReader();
            worker.installShutdownHook();
            return worker;
        } catch (RuntimeException e) {
            process.destroyForcibly();
            throw e;
        } catch (Exception e) {
            process.destroyForcibly();
            throw new IllegalStateException("Worker handshake failed", e);
        }
    }

    private ProcessIsolatedLocalWorker(
            Process process,
            Socket socket,
            BufferedReader in,
            BufferedWriter out,
            long workerPid,
            String token,
            Path javaExecutable,
            String classpath
    ) {
        this.process = process;
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.workerPid = workerPid;
        this.token = token;
        this.javaExecutable = javaExecutable;
        this.classpath = classpath;
        this.shutdownHook = new Thread(() -> {
            try {
                process.destroyForcibly();
            } catch (RuntimeException ignored) {
            }
        }, "stellar-worker-reaper");
    }

    void installShutdownHook() {
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
        }
    }

    @Override
    public LocalWorkerIsolation isolation() {
        return LocalWorkerIsolation.PROCESS_ISOLATED;
    }

    @Override
    public boolean processIsolationAvailable() {
        return true;
    }

    @Override
    public Future<?> submit(Runnable task) {
        throw new UnsupportedOperationException(
                "PROCESS_ISOLATED workers cannot transport Runnable. Use submitTask(ProcessingTaskDescriptor).");
    }

    public Future<WorkerMessage> submitTask(ProcessingTaskDescriptor task) {
        return submitTask(task, null);
    }

    public Future<WorkerMessage> submitTask(ProcessingTaskDescriptor task, java.util.function.Consumer<WorkerMessage> events) {
        Objects.requireNonNull(task, "task");
        ensureAlive();
        active.incrementAndGet();
        inbound.clear();
        try {
            write(WorkerMessage.ofType(WorkerProtocol.START_JOB).withTask(task));
            WorkerMessage completed = null;
            while (open.get()) {
                WorkerMessage incoming = takeInbound();
                lastHeartbeat.set(Instant.now());
                if (events != null) {
                    events.accept(incoming);
                }
                if (WorkerProtocol.isTerminal(incoming.type())) {
                    completed = incoming;
                    break;
                }
            }
            if (completed == null) {
                throw readerFailureOr(new IllegalStateException("Worker closed during task " + task.taskId()));
            }
            return CompletableFuture.completedFuture(completed);
        } catch (IOException e) {
            String diagnostic = drainStdout();
            if (!isAlive()) {
                throw new WorkerDiedException("Local worker process died during task " + task.taskId()
                        + diagnostic, e);
            }
            throw new IllegalStateException("Worker IPC failed" + diagnostic, e);
        } finally {
            active.decrementAndGet();
        }
    }

    /** Forcibly terminate the child JVM. Used by crash-recovery tests. */
    public void destroyForcibly() {
        removeShutdownHook();
        open.set(false);
        process.destroyForcibly();
        stopReader();
        try {
            process.waitFor(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Requests cooperative cancel of the in-flight job. Does not wait for a reply —
     * {@link #submitTask} owns the reader. Safe to call more than once.
     */
    public void requestCancel(String taskId) {
        if (!isAlive()) {
            return;
        }
        try {
            synchronized (io) {
                write(WorkerMessage.ofType(WorkerProtocol.CANCEL_JOB).withTaskId(taskId));
            }
        } catch (Exception ignored) {
        }
    }

    public WorkerHealthSnapshot health() {
        if (!isAlive()) {
            return WorkerHealthSnapshot.dead();
        }
        try {
            WorkerMessage reply = requestControl(WorkerProtocol.HEALTH, WorkerProtocol.HEALTH_OK, 5_000L);
            lastHeartbeat.set(Instant.now());
            WorkerHealthSnapshot snap = new WorkerHealthSnapshot(
                    reply.pid() == null ? workerPid : reply.pid(),
                    true,
                    nvl(reply.heapUsedBytes()),
                    nvl(reply.heapMaxBytes()),
                    reply.availableProcessors() == null ? 0 : reply.availableProcessors(),
                    reply.activeJobs() == null ? active.get() : reply.activeJobs(),
                    reply.queueDepth() == null ? 0 : reply.queueDepth(),
                    nvl(reply.uptimeMillis()),
                    Instant.now(),
                    reply.lastCheckpoint() == null ? null : Instant.parse(reply.lastCheckpoint()));
            lastHealth.set(snap);
            return snap;
        } catch (Exception e) {
            return WorkerHealthSnapshot.dead();
        }
    }

    public boolean ping() {
        if (!isAlive()) {
            return false;
        }
        try {
            WorkerMessage reply = requestControl(WorkerProtocol.PING, WorkerProtocol.PONG, 5_000L);
            lastHeartbeat.set(Instant.now());
            return WorkerProtocol.PONG.equals(reply.type());
        } catch (Exception e) {
            return false;
        }
    }

    public long workerPid() {
        return workerPid;
    }

    public Path javaExecutable() {
        return javaExecutable;
    }

    public String classpath() {
        return classpath;
    }

    public boolean isAlive() {
        return open.get() && process.isAlive();
    }

    public Instant lastHeartbeat() {
        return lastHeartbeat.get();
    }

    @Override
    public int activeApproximate() {
        return active.get();
    }

    @Override
    public void shutdown() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        removeShutdownHook();
        try {
            requestControl(WorkerProtocol.SHUTDOWN, WorkerProtocol.SHUTDOWN_OK, 3_000L);
        } catch (Exception ignored) {
        }
        stopReader();
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        process.destroy();
        try {
            process.waitFor(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private void handshake() throws IOException {
        write(WorkerMessage.ofType(WorkerProtocol.HELLO).withToken(token));
        WorkerMessage reply = readLine();
        if (!WorkerProtocol.HELLO_OK.equals(reply.type())) {
            throw new IllegalStateException("Worker HELLO failed: " + reply.type());
        }
        lastHeartbeat.set(Instant.now());
    }

    private void startReader() {
        Thread thread = new Thread(this::readLoop, "stellar-worker-ipc-reader");
        thread.setDaemon(true);
        reader = thread;
        thread.start();
    }

    private void readLoop() {
        try {
            while (open.get()) {
                WorkerMessage message = readLine();
                lastHeartbeat.set(Instant.now());
                String type = message.type() == null ? "" : message.type();
                if (isControlReply(type)) {
                    CompletableFuture<WorkerMessage> waiter = controlWaiters.remove(type);
                    if (waiter != null) {
                        waiter.complete(message);
                        continue;
                    }
                }
                inbound.put(message);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            readerFailure.compareAndSet(null, ex);
            for (CompletableFuture<WorkerMessage> waiter : controlWaiters.values()) {
                waiter.completeExceptionally(ex);
            }
            controlWaiters.clear();
            inbound.offer(WorkerMessage.ofType(WorkerProtocol.FAILED)
                    .withToken("READER_CLOSED"));
        }
    }

    private void stopReader() {
        Thread thread = reader;
        if (thread != null) {
            thread.interrupt();
        }
        inbound.offer(WorkerMessage.ofType(WorkerProtocol.FAILED).withToken("READER_CLOSED"));
    }

    private WorkerMessage takeInbound() throws IOException {
        try {
            while (open.get() || !inbound.isEmpty()) {
                WorkerMessage incoming = inbound.poll(200, TimeUnit.MILLISECONDS);
                if (incoming != null) {
                    if (WorkerProtocol.FAILED.equals(incoming.type())
                            && "READER_CLOSED".equals(incoming.token())) {
                        throw readerFailureOr(new IOException("Worker closed the connection"));
                    }
                    return incoming;
                }
                if (!isAlive()) {
                    throw readerFailureOr(new IOException("Worker closed the connection"));
                }
            }
            throw readerFailureOr(new IOException("Worker closed the connection"));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for worker", ie);
        }
    }

    private WorkerMessage requestControl(String requestType, String replyType, long timeoutMillis)
            throws Exception {
        CompletableFuture<WorkerMessage> waiter = new CompletableFuture<>();
        controlWaiters.put(replyType, waiter);
        try {
            write(WorkerMessage.ofType(requestType));
            return waiter.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } finally {
            controlWaiters.remove(replyType, waiter);
        }
    }

    private static boolean isControlReply(String type) {
        return WorkerProtocol.PONG.equals(type)
                || WorkerProtocol.HEALTH_OK.equals(type)
                || WorkerProtocol.SHUTDOWN_OK.equals(type)
                || WorkerProtocol.HELLO_OK.equals(type);
    }

    private RuntimeException readerFailureOr(RuntimeException fallback) {
        Throwable failure = readerFailure.get();
        if (failure instanceof RuntimeException re) {
            return re;
        }
        if (failure != null) {
            return new IllegalStateException(failure);
        }
        return fallback;
    }

    private IOException readerFailureOr(IOException fallback) {
        Throwable failure = readerFailure.get();
        if (failure instanceof IOException ioException) {
            return ioException;
        }
        if (failure != null) {
            return new IOException(failure);
        }
        return fallback;
    }

    private void removeShutdownHook() {
        if (shutdownHook == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException | SecurityException ignored) {
        }
    }

    private void write(WorkerMessage message) throws IOException {
        synchronized (io) {
            out.write(WorkerJson.MAPPER.writeValueAsString(message));
            out.write('\n');
            out.flush();
        }
    }

    private WorkerMessage readLine() throws IOException {
        String line = in.readLine();
        if (line == null) {
            throw new IOException("Worker closed the connection");
        }
        return WorkerJson.MAPPER.readValue(line, WorkerMessage.class);
    }

    private String drainStdout() {
        try {
            java.io.InputStream raw = process.getInputStream();
            int available = raw.available();
            if (available <= 0) {
                return "";
            }
            byte[] buf = raw.readNBytes(Math.min(available, 8_000));
            String text = new String(buf, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? "" : " stdout=" + text;
        } catch (Exception ignored) {
            return "";
        }
    }

    private void ensureAlive() {
        if (!isAlive()) {
            throw new IllegalStateException("Local worker is not running");
        }
    }

    private static long nvl(Long value) {
        return value == null ? 0L : value;
    }

    private record ReadyBanner(int port, long pid) {
    }

    /**
     * JUL ConsoleHandler writes to System.err, which is merged into stdout. After the READY
     * banner is consumed, nothing else reads that pipe. Twenty reconstructed-job INFO lines
     * fill the OS buffer and stall {@code stellar-worker-job} before ASC parse starts.
     */
    private static void startMergedStdoutDrain(Process process) {
        Thread drain = new Thread(() -> {
            try {
                process.getInputStream().transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {
            }
        }, "stellar-worker-stdout-drain");
        drain.setDaemon(true);
        drain.start();
    }

    private static ReadyBanner readReady(Process process, String token, long timeoutMillis) throws IOException {
        BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String line;
        while (System.currentTimeMillis() < deadline && (line = stdout.readLine()) != null) {
            if (!line.startsWith(WorkerProtocol.READY_PREFIX)) {
                continue;
            }
            int port = integerAfter(line, "port=");
            long pid = longAfter(line, "pid=");
            String reported = stringAfter(line, "token=");
            if (!token.equals(reported)) {
                throw new IllegalStateException("Worker token mismatch");
            }
            return new ReadyBanner(port, pid);
        }
        throw new IOException("Worker did not publish a READY banner");
    }

    private static int integerAfter(String line, String key) {
        return Integer.parseInt(stringAfter(line, key));
    }

    private static long longAfter(String line, String key) {
        return Long.parseLong(stringAfter(line, key));
    }

    private static String stringAfter(String line, String key) {
        int idx = line.indexOf(key);
        if (idx < 0) {
            throw new IllegalStateException("Missing " + key + " in " + line);
        }
        int start = idx + key.length();
        int end = line.indexOf(' ', start);
        return (end < 0 ? line.substring(start) : line.substring(start, end)).trim();
    }
}
