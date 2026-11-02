package com.stellar.platform.desktop.presentation;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectSaveCoordinatorTest {

    @Test
    void logicalChangeMarksDirtyAndNeverReportsSaved() {
        ProjectSaveCoordinator coordinator = new ProjectSaveCoordinator();
        coordinator.markLogicalDirty();
        assertEquals(ProjectSaveState.DIRTY, coordinator.state());
        assertTrue(coordinator.hasUnsavedChanges());
        assertEquals("", coordinator.userStatusLabel());
        coordinator.close();
    }

    @Test
    void failedSaveNeverReportsSaved() {
        ProjectSaveCoordinator coordinator = new ProjectSaveCoordinator();
        coordinator.markFailed(new RuntimeException("disk unavailable"));
        assertEquals(ProjectSaveState.SAVE_FAILED, coordinator.state());
        assertEquals("Save failed", coordinator.userStatusLabel());
        assertTrue(coordinator.autosaveSuspended());
        coordinator.close();
    }

    @Test
    void successfulRetryClearsError() {
        ProjectSaveCoordinator coordinator = new ProjectSaveCoordinator();
        coordinator.markFailed(new RuntimeException("locked"));
        coordinator.beginSave();
        assertEquals("Saving…", coordinator.userStatusLabel());
        coordinator.markClean();
        assertEquals(ProjectSaveState.CLEAN, coordinator.state());
        assertEquals("Saved", coordinator.userStatusLabel());
        assertFalse(coordinator.autosaveSuspended());
        coordinator.close();
    }

    @Test
    void failedAutosaveDoesNotReschedule() {
        ProjectSaveCoordinator coordinator = new ProjectSaveCoordinator();
        AtomicInteger runs = new AtomicInteger();
        coordinator.markFailed(new RuntimeException("locked"));
        coordinator.scheduleAutosave(runs::incrementAndGet, 50);
        assertFalse(coordinator.hasPendingAutosave());
        assertEquals(0, runs.get());
        coordinator.close();
    }

    @Test
    void coordinatorSupportsNoActiveProjectState() {
        ProjectSaveCoordinator coordinator = new ProjectSaveCoordinator();
        assertEquals(ProjectSaveState.CLEAN, coordinator.state());
        assertTrue(coordinator.boundProjectId() == null);
        AtomicInteger runs = new AtomicInteger();
        coordinator.scheduleAutosave(runs::incrementAndGet, 50);
        assertFalse(coordinator.hasPendingAutosave());
        assertEquals(0, runs.get());
        coordinator.close();
    }

    @Test
    void projectResetCancelsOldDebouncedSave() throws Exception {
        ProjectSaveCoordinator coordinator = new ProjectSaveCoordinator();
        coordinator.attach("project-a");
        AtomicInteger runs = new AtomicInteger();
        coordinator.scheduleAutosave(runs::incrementAndGet, 200);
        assertTrue(coordinator.hasPendingAutosave());
        coordinator.detach();
        Thread.sleep(300);
        assertEquals(0, runs.get());
        assertFalse(coordinator.hasPendingAutosave());
        coordinator.close();
    }

    @Test
    void oldProjectAutosaveCannotRunAgainstNewProject() throws Exception {
        ProjectSaveCoordinator coordinator = new ProjectSaveCoordinator();
        coordinator.attach("project-a");
        AtomicInteger runs = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        coordinator.scheduleAutosave(() -> {
            runs.incrementAndGet();
            seen.set(coordinator.boundProjectId());
        }, 200);
        coordinator.attach("project-b");
        Thread.sleep(350);
        assertEquals(0, runs.get());
        assertEquals("project-b", coordinator.boundProjectId());
        assertTrue(seen.get() == null);
        coordinator.close();
    }

    @Test
    void saveFailureFromPreviousProjectDoesNotPoisonNewProject() {
        ProjectSaveCoordinator coordinator = new ProjectSaveCoordinator();
        coordinator.attach("project-a");
        coordinator.markFailed(new RuntimeException("disk unavailable"));
        assertEquals(ProjectSaveState.SAVE_FAILED, coordinator.state());
        coordinator.attach("project-b");
        assertEquals(ProjectSaveState.CLEAN, coordinator.state());
        assertTrue(coordinator.lastError() == null);
        assertFalse(coordinator.autosaveSuspended());
        coordinator.close();
    }

    @Test
    void parallelSaveRequestsShareOneLock() {
        ProjectSaveCoordinator coordinator = new ProjectSaveCoordinator();
        Object lock = coordinator.saveLock();
        synchronized (lock) {
            coordinator.beginSave();
            assertTrue(coordinator.isSaving());
        }
        coordinator.close();
    }

    @Test
    void closeJoinsOwnedSchedulerAndClearsListeners() {
        ProjectSaveCoordinator coordinator = new ProjectSaveCoordinator();
        coordinator.attach("project-a");
        coordinator.addListener(() -> { });
        coordinator.scheduleAutosave(() -> { }, 5_000);
        assertTrue(coordinator.hasPendingAutosave());
        assertEquals(1, coordinator.listenerCountForTest());
        coordinator.close();
        assertEquals(0, coordinator.listenerCountForTest());
        assertFalse(coordinator.hasPendingAutosave());
        assertTrue(coordinator.schedulerTerminatedForTest());
    }
}
