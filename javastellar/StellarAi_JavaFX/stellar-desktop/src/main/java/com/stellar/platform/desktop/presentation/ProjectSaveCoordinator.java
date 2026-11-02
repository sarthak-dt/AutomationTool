package com.stellar.platform.desktop.presentation;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Authoritative dirty / saving / failed state for one open project.
 *
 * <p>Controllers must not invent their own persistence rules. High-frequency graph writes are
 * coalesced; a failed autosave does not hammer storage until Retry or explicit Save.</p>
 */
public final class ProjectSaveCoordinator implements AutoCloseable {

    public static final long AUTOSAVE_DEBOUNCE_MS = 750L;

    private final AtomicReference<ProjectSaveState> state = new AtomicReference<>(ProjectSaveState.CLEAN);
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicBoolean autosaveSuspended = new AtomicBoolean();
    private final AtomicBoolean graphPersistPending = new AtomicBoolean();
    private final AtomicReference<Runnable> pendingAutosave = new AtomicReference<>();
    private final AtomicReference<String> boundProjectId = new AtomicReference<>();
    private final AtomicLong sessionGeneration = new AtomicLong();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Object saveLock = new Object();
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private volatile ScheduledFuture<?> autosaveFuture;

    public ProjectSaveCoordinator() {
        ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "stellar-project-autosave");
            t.setDaemon(true);
            return t;
        });
        exec.setRemoveOnCancelPolicy(true);
        this.scheduler = exec;
        this.ownsScheduler = true;
    }

    ProjectSaveCoordinator(ScheduledExecutorService scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = false;
    }

    public ProjectSaveState state() {
        return state.get();
    }

    public boolean hasUnsavedChanges() {
        ProjectSaveState current = state.get();
        return current == ProjectSaveState.DIRTY || current == ProjectSaveState.SAVE_FAILED;
    }

    public boolean isSaving() {
        return state.get() == ProjectSaveState.SAVING;
    }

    public String lastError() {
        return lastError.get();
    }

    public boolean autosaveSuspended() {
        return autosaveSuspended.get();
    }

    public boolean graphPersistPending() {
        return graphPersistPending.get();
    }

    public Object saveLock() {
        return saveLock;
    }

    /** User-facing status. Never reports Saved after a failed write. */
    public String userStatusLabel() {
        return switch (state.get()) {
            case SAVING -> "Saving…";
            case SAVE_FAILED -> "Save failed";
            case CLEAN -> "Saved";
            case DIRTY -> "";
        };
    }

    public String boundProjectId() {
        return boundProjectId.get();
    }

    public long sessionGeneration() {
        return sessionGeneration.get();
    }

    /**
     * Bind this coordinator to one durable project. Cancels any previous project's debounce
     * and clears SAVE_FAILED / DIRTY leftover from that session.
     */
    public void attach(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            detach();
            return;
        }
        cancelPendingAutosave();
        boundProjectId.set(projectId);
        sessionGeneration.incrementAndGet();
        restoreClean();
    }

    /** No active project: pending writes are dropped and dirty/failed state cannot leak. */
    public void detach() {
        cancelPendingAutosave();
        boundProjectId.set(null);
        sessionGeneration.incrementAndGet();
        restoreClean();
    }

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public void markLogicalDirty() {
        ProjectSaveState previous = state.getAndUpdate(current ->
                current == ProjectSaveState.SAVING ? current : ProjectSaveState.DIRTY);
        if (previous != ProjectSaveState.DIRTY && previous != ProjectSaveState.SAVING) {
            notifyListeners();
        }
    }

    public void noteGraphLogicalChange() {
        graphPersistPending.set(true);
        markLogicalDirty();
    }

    public void graphPersistFinished(boolean success) {
        graphPersistPending.set(false);
        // Graph writes alone must not clear dirty/SAVING — only explicit Save / markClean does.
        if (!success) {
            return;
        }
    }

    public void beginSave() {
        state.set(ProjectSaveState.SAVING);
        notifyListeners();
    }

    public void markClean() {
        lastError.set(null);
        autosaveSuspended.set(false);
        graphPersistPending.set(false);
        pendingAutosave.set(null);
        cancelScheduledAutosave();
        state.set(ProjectSaveState.CLEAN);
        notifyListeners();
    }

    public void markFailed(Throwable error) {
        String message = error == null || error.getMessage() == null
                ? (error == null ? "Persistence failed" : error.toString())
                : error.getMessage();
        lastError.set(message);
        autosaveSuspended.set(true);
        cancelScheduledAutosave();
        state.set(ProjectSaveState.SAVE_FAILED);
        notifyListeners();
    }

    /**
     * Coalesce a later persist of the latest durable state. After a failed autosave this is a
     * no-op until Retry / explicit Save.
     */
    public void scheduleAutosave(Runnable persist, long delayMs) {
        if (persist == null || autosaveSuspended.get()) {
            return;
        }
        final String projectId = boundProjectId.get();
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        final long generation = sessionGeneration.get();
        pendingAutosave.set(() -> {
            if (sessionGeneration.get() != generation || !projectId.equals(boundProjectId.get())) {
                return;
            }
            persist.run();
        });
        cancelScheduledAutosave();
        long delay = Math.max(50L, delayMs);
        autosaveFuture = scheduler.schedule(() -> {
            Runnable work = pendingAutosave.getAndSet(null);
            if (work != null && !autosaveSuspended.get()) {
                try {
                    work.run();
                } catch (RuntimeException ignored) {
                    // persist implementations record failure themselves
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    public void flushPendingAutosave() {
        cancelScheduledAutosave();
        Runnable work = pendingAutosave.getAndSet(null);
        if (work != null) {
            work.run();
        }
    }

    public void cancelPendingAutosave() {
        pendingAutosave.set(null);
        cancelScheduledAutosave();
    }

    public boolean hasPendingAutosave() {
        return pendingAutosave.get() != null || (autosaveFuture != null && !autosaveFuture.isDone());
    }

    public void restoreClean() {
        lastError.set(null);
        autosaveSuspended.set(false);
        graphPersistPending.set(false);
        pendingAutosave.set(null);
        cancelScheduledAutosave();
        state.set(ProjectSaveState.CLEAN);
        notifyListeners();
    }

    private void cancelScheduledAutosave() {
        ScheduledFuture<?> future = autosaveFuture;
        if (future != null) {
            future.cancel(false);
            autosaveFuture = null;
        }
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public void close() {
        cancelPendingAutosave();
        listeners.clear();
        if (ownsScheduler) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    int listenerCountForTest() {
        return listeners.size();
    }

    boolean schedulerTerminatedForTest() {
        return !ownsScheduler || scheduler.isTerminated();
    }
}
