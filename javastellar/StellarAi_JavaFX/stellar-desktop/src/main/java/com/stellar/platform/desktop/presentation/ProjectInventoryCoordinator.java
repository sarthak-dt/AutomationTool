package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ProjectInventoryJobState;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.domain.inventory.CanInventoryDiagnosticCode;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.SimpleCancellationToken;
import com.stellar.platform.processing.ProcessingRequest;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * MF-3 project-level inventory orchestration.
 * <p>
 * Queues per-{@link ProjectSourceId} inventory jobs and executes them with bounded source-level
 * parallelism through the existing {@link SourceInventoryRunner} / parser SPI path. The queue
 * remains coordinator-owned, so hundreds of files do not become hundreds of executor tasks.
 * Contains no MF4/ASC/BLF parsing logic.
 */
public final class ProjectInventoryCoordinator {

    private static final System.Logger LOG = System.getLogger(ProjectInventoryCoordinator.class.getName());


    public record InventoryProgress(
            ProjectSourceId sourceId,
            ProjectInventoryJobState jobState,
            CanSourceInventory inventory,
            long generation,
            String detail
    ) {
        public InventoryProgress(
                ProjectSourceId sourceId,
                ProjectInventoryJobState jobState,
                CanSourceInventory inventory,
                long generation
        ) {
            this(sourceId, jobState, inventory, generation, null);
        }
    }

    private record QueuedJob(
            ProjectSourceId sourceId,
            Path path,
            long generation,
            Consumer<Runnable> uiUpdater
    ) {
    }

    private final SourceInventoryRunner inventoryRunner;
    private final ExecutorService executor;
    private final Consumer<InventoryProgress> progressSink;
    /** Runs on the inventory worker when a job reaches a terminal state, before the worker goes idle. */
    private Consumer<InventoryProgress> terminalCommit = progress -> {
    };
    private final Map<String, ProjectInventoryJobState> states = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> generations = new ConcurrentHashMap<>();
    private final Map<String, SimpleCancellationToken> tokens = new ConcurrentHashMap<>();
    private final Queue<QueuedJob> queue = new ArrayDeque<>();
    private final int maxConcurrentJobs;
    private final Object lock = new Object();
    /** Guarded by {@link #lock}. */
    private int activeDrainers;

    public ProjectInventoryCoordinator(
            SourceInventoryRunner inventoryRunner,
            ExecutorService executor,
            Consumer<InventoryProgress> progressSink
    ) {
        this(inventoryRunner, executor, progressSink, 1);
    }

    public ProjectInventoryCoordinator(
            SourceInventoryRunner inventoryRunner,
            ExecutorService executor,
            Consumer<InventoryProgress> progressSink,
            int maxConcurrentJobs
    ) {
        this.inventoryRunner = Objects.requireNonNull(inventoryRunner, "inventoryRunner");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.progressSink = Objects.requireNonNull(progressSink, "progressSink");
        this.maxConcurrentJobs = Math.max(1, maxConcurrentJobs);
    }

    public void setTerminalCommit(Consumer<InventoryProgress> commit) {
        this.terminalCommit = commit == null ? progress -> {
        } : commit;
    }

    public void registerSource(ProjectSourceId sourceId, ProjectInventoryJobState initial) {
        Objects.requireNonNull(sourceId, "sourceId");
        states.put(sourceId.value(), initial == null ? ProjectInventoryJobState.ADDED : initial);
        generations.computeIfAbsent(sourceId.value(), id -> new AtomicLong(0L));
    }

    /**
     * Enqueues inventory for a measurement source if it is not already READY / in-flight.
     * Already-READY sources are left untouched (add-after-ready semantics).
     */
    public void enqueue(WorkspaceFile measurement, Consumer<Runnable> uiUpdater) {
        Objects.requireNonNull(measurement, "measurement");
        if (!measurement.kind().measurement()) {
            return;
        }
        ProjectSourceId sourceId = measurement.projectSourceId();
        if (!measurement.kind().implemented()) {
            registerSource(sourceId, ProjectInventoryJobState.UNSUPPORTED);
            publish(sourceId, ProjectInventoryJobState.UNSUPPORTED, null, generationOf(sourceId.value()));
            return;
        }
        ProjectInventoryJobState current = state(sourceId);
        if (current == ProjectInventoryJobState.READY
                || current == ProjectInventoryJobState.INDEXING
                || current == ProjectInventoryJobState.WAITING) {
            return;
        }
        long gen = bumpGeneration(sourceId.value());
        registerSource(sourceId, ProjectInventoryJobState.WAITING);
        Consumer<Runnable> ui = uiUpdater == null ? Runnable::run : uiUpdater;
        publishUi(ui, () -> progressSink.accept(
                new InventoryProgress(sourceId, ProjectInventoryJobState.WAITING, null, gen)));
        synchronized (lock) {
            queue.removeIf(job -> job.sourceId().equals(sourceId));
            queue.add(new QueuedJob(sourceId, measurement.path(), gen, ui));
        }
        ensureDraining();
    }

    public void cancelAndForget(ProjectSourceId sourceId) {
        if (sourceId == null) {
            return;
        }
        String id = sourceId.value();
        bumpGeneration(id);
        SimpleCancellationToken token = tokens.remove(id);
        if (token != null) {
            token.cancel();
        }
        synchronized (lock) {
            queue.removeIf(job -> job.sourceId().equals(sourceId));
        }
        states.remove(id);
        generations.remove(id);
    }

    public void clear() {
        synchronized (lock) {
            queue.clear();
        }
        for (SimpleCancellationToken token : tokens.values()) {
            token.cancel();
        }
        tokens.clear();
        states.clear();
        generations.clear();
    }

    public ProjectInventoryJobState state(ProjectSourceId sourceId) {
        if (sourceId == null) {
            return ProjectInventoryJobState.ADDED;
        }
        return states.getOrDefault(sourceId.value(), ProjectInventoryJobState.ADDED);
    }

    public Map<ProjectSourceId, ProjectInventoryJobState> snapshotStates() {
        Map<ProjectSourceId, ProjectInventoryJobState> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ProjectInventoryJobState> e : states.entrySet()) {
            copy.put(ProjectSourceId.of(e.getKey()), e.getValue());
        }
        return Map.copyOf(copy);
    }

    public long generationOf(String sourceId) {
        AtomicLong gen = generations.get(sourceId);
        return gen == null ? 0L : gen.get();
    }

    public Optional<ProjectInventoryJobState> stateOptional(String sourceId) {
        return Optional.ofNullable(states.get(sourceId));
    }

    /**
     * Waits until queued and running inventory jobs have published their terminal state.
     * The inventory worker must not call this; it would wait for itself.
     */
    public boolean awaitIdle(long timeoutMillis) {
        String thread = Thread.currentThread().getName();
        if (thread != null && thread.startsWith("stellar-source-inventory")) {
            return false;
        }
        long deadline = System.nanoTime() + Math.max(0L, timeoutMillis) * 1_000_000L;
        while (true) {
            if (!inventoryBusy()) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private boolean inventoryBusy() {
        synchronized (lock) {
            if (activeDrainers > 0 || !queue.isEmpty()) {
                return true;
            }
        }
        for (ProjectInventoryJobState state : states.values()) {
            if (state == ProjectInventoryJobState.WAITING || state == ProjectInventoryJobState.INDEXING) {
                return true;
            }
        }
        return false;
    }

    private long bumpGeneration(String sourceId) {
        return generations.computeIfAbsent(sourceId, id -> new AtomicLong(0L)).incrementAndGet();
    }

    private void ensureDraining() {
        while (true) {
            synchronized (lock) {
                if (queue.isEmpty() || activeDrainers >= maxConcurrentJobs) {
                    return;
                }
                // Reserve exactly one worker before submission. Reserving the whole batch up-front
                // can strand the counter if an executor rejects a later submission during shutdown.
                activeDrainers++;
            }
            try {
                executor.execute(this::drainQueue);
            } catch (RuntimeException submitFailure) {
                synchronized (lock) {
                    activeDrainers = Math.max(0, activeDrainers - 1);
                }
                throw submitFailure;
            }
        }
    }

    private void drainQueue() {
        try {
            while (true) {
                QueuedJob job;
                synchronized (lock) {
                    job = queue.poll();
                }
                if (job == null) {
                    return;
                }
                runJob(job);
            }
        } finally {
            boolean hasQueuedWork;
            synchronized (lock) {
                activeDrainers = Math.max(0, activeDrainers - 1);
                hasQueuedWork = !queue.isEmpty();
            }
            if (hasQueuedWork) {
                ensureDraining();
            }
        }
    }

    private void runJob(QueuedJob job) {
        String id = job.sourceId().value();
        if (job.generation() != generationOf(id)) {
            return;
        }
        SimpleCancellationToken token = new SimpleCancellationToken();
        tokens.put(id, token);
        states.put(id, ProjectInventoryJobState.INDEXING);
        long startedNanos = System.nanoTime();
        publishUi(job.uiUpdater(), () -> progressSink.accept(
                new InventoryProgress(job.sourceId(), ProjectInventoryJobState.INDEXING, null, job.generation())));
        CoalescingUiDispatcher dispatcher = new CoalescingUiDispatcher(job.uiUpdater());
        AtomicBoolean terminalPublished = new AtomicBoolean(false);
        try {
            ReaderContext context = new ReaderContext(ProcessingRequest.DEFAULT_BATCH_SIZE, token);
            CanSourceInventory finalInventory = inventoryRunner.inventory(
                    id,
                    job.path(),
                    context,
                    inventory -> {
                        if (job.generation() != generationOf(id)) {
                            return;
                        }
                        ProjectInventoryJobState mapped = mapStatus(inventory.status(), inventory);
                        states.put(id, mapped);
                        boolean terminal = mapped.terminal();
                        if (terminal) {
                            terminalPublished.set(true);
                        }
                        Runnable publish = () -> progressSink.accept(new InventoryProgress(
                                job.sourceId(), mapped, inventory, job.generation()));
                        if (terminal) {
                            dispatcher.submitImmediate(publish);
                        } else {
                            dispatcher.submit(publish);
                        }
                    }
            );
            if (job.generation() != generationOf(id)) {
                return;
            }
            ProjectInventoryJobState mapped = mapStatus(finalInventory.status(), finalInventory);
            states.put(id, mapped);
            long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
            LOG.log(System.Logger.Level.INFO,
                    "Inventory {0} {1}: format={2}, channels={3}, uniqueIds={4}, frames={5}, elapsedMs={6}, status={7}",
                    job.path().getFileName(),
                    job.sourceId().value(),
                    finalInventory.format(),
                    finalInventory.totalCanChannels(),
                    finalInventory.totalUniqueMessageIds(),
                    finalInventory.totalCanFrames(),
                    elapsedMs,
                    mapped);
            commitTerminal(new InventoryProgress(job.sourceId(), mapped, finalInventory, job.generation()));
            if (!terminalPublished.get()) {
                dispatcher.submitImmediate(() -> progressSink.accept(new InventoryProgress(
                        job.sourceId(), mapped, finalInventory, job.generation())));
            }
        } catch (RuntimeException ex) {
            if (job.generation() != generationOf(id)) {
                return;
            }
            states.put(id, ProjectInventoryJobState.FAILED);
            String detail = ex.getMessage() == null ? "Inventory failed" : ex.getMessage();
            commitTerminal(new InventoryProgress(
                    job.sourceId(), ProjectInventoryJobState.FAILED, null, job.generation(), detail));
            dispatcher.submitImmediate(() -> progressSink.accept(new InventoryProgress(
                    job.sourceId(), ProjectInventoryJobState.FAILED, null, job.generation(), detail)));
        } catch (OutOfMemoryError oom) {
            if (job.generation() != generationOf(id)) {
                return;
            }
            LOG.log(System.Logger.Level.ERROR,
                    "Inventory ran out of memory for " + job.path().getFileName()
                            + ". The file is marked failed and the remaining batch continues.");
            states.put(id, ProjectInventoryJobState.FAILED);
            try {
                dispatcher.submitImmediate(() -> progressSink.accept(new InventoryProgress(
                        job.sourceId(), ProjectInventoryJobState.FAILED, null, job.generation(),
                        "Inventory stopped because memory was exhausted while reading this file. "
                                + "Other files in the project can still be inventoried.")));
            } catch (Throwable publishFailure) {
                LOG.log(System.Logger.Level.ERROR, "Could not publish the inventory memory failure", publishFailure);
            }
        } finally {
            tokens.remove(id, token);
        }
    }

    private void commitTerminal(InventoryProgress progress) {
        if (progress == null || progress.generation() != generationOf(progress.sourceId().value())) {
            return;
        }
        try {
            terminalCommit.accept(progress);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Channel coverage was not committed: "
                    + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
        }
    }

    private void publish(
            ProjectSourceId sourceId,
            ProjectInventoryJobState state,
            CanSourceInventory inventory,
            long generation
    ) {
        progressSink.accept(new InventoryProgress(sourceId, state, inventory, generation));
    }

    private static void publishUi(Consumer<Runnable> ui, Runnable action) {
        if (ui == null) {
            action.run();
        } else {
            ui.accept(action);
        }
    }

    static ProjectInventoryJobState mapStatus(CanInventoryStatus status) {
        return mapStatus(status, null);
    }

    static ProjectInventoryJobState mapStatus(CanInventoryStatus status, CanSourceInventory inventory) {
        if (status == null) {
            return ProjectInventoryJobState.INDEXING;
        }
        if (status == CanInventoryStatus.FAILED
                && inventory != null
                && inventory.diagnostics().stream().anyMatch(d ->
                d.code() == CanInventoryDiagnosticCode.CAN_PAYLOAD_UNREADABLE
                        || d.code() == CanInventoryDiagnosticCode.UNSUPPORTED_CAN_STRUCTURE)) {
            return ProjectInventoryJobState.REQUIRES_ATTENTION;
        }
        return switch (status) {
            case INVENTORY_READY -> ProjectInventoryJobState.READY;
            case FAILED -> ProjectInventoryJobState.FAILED;
            case CANCELLED -> ProjectInventoryJobState.CANCELLED;
            case FILE_ACCEPTED, DISCOVERING_CHANNELS, CHANNELS_DISCOVERED, INDEXING_MESSAGE_IDS
                    -> ProjectInventoryJobState.INDEXING;
        };
    }
}
