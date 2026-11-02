package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.FileValidationStatus;
import com.stellar.platform.desktop.project.ProjectInventoryJobState;
import com.stellar.platform.desktop.project.WorkspaceFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sources shows five engineer-facing statuses. Internal validation and inventory-job enums
 * must never reach the user, and each internal state must land on exactly one of them.
 */
class SourceStatusResolverTest {

    private static WorkspaceFile measurement(String name) {
        return new WorkspaceFile(Path.of(name).toAbsolutePath(), FileKind.MEASUREMENT_MF4, 1024);
    }

    @Test
    void queuedAndRunningInspectionReadsAsReading() {
        WorkspaceFile file = measurement("queued.mf4");
        for (ProjectInventoryJobState state : new ProjectInventoryJobState[]{
                ProjectInventoryJobState.ADDED,
                ProjectInventoryJobState.WAITING,
                ProjectInventoryJobState.INDEXING}) {
            assertEquals(SourceStatus.READING,
                    SourceStatusResolver.resolve(file, state, false, true, false),
                    "job state " + state);
        }
    }

    @Test
    void discoveredChannelsMakeTheSourceReady() {
        WorkspaceFile file = measurement("ready.mf4");
        assertEquals(SourceStatus.READY,
                SourceStatusResolver.resolve(file, ProjectInventoryJobState.READY, true, true, false));
    }

    @Test
    void alreadyProcessedSourceReadsAsProcessed() {
        WorkspaceFile file = measurement("processed.mf4");
        assertEquals(SourceStatus.PROCESSED,
                SourceStatusResolver.resolve(file, ProjectInventoryJobState.READY, true, true, true));
    }

    @Test
    void processedLifecycleWinsEvenWhenInventoryFoundNoChannels() {
        WorkspaceFile file = measurement("empty-but-processed.mf4");
        assertEquals(SourceStatus.PROCESSED,
                SourceStatusResolver.resolve(file, ProjectInventoryJobState.READY, false, true, true));
    }

    @Test
    void failedInspectionReadsAsNeedsAttention() {
        WorkspaceFile file = measurement("broken.mf4");
        assertEquals(SourceStatus.NEEDS_ATTENTION,
                SourceStatusResolver.resolve(file, ProjectInventoryJobState.FAILED, false, true, false));
        assertEquals(SourceStatus.NEEDS_ATTENTION,
                SourceStatusResolver.resolve(
                        file, ProjectInventoryJobState.REQUIRES_ATTENTION, false, true, false));
    }

    @Test
    void formatThatCannotBeReadYetNeedsAttentionRatherThanLookingHealthy() {
        WorkspaceFile unknown = new WorkspaceFile(
                Path.of("legacy.xyz").toAbsolutePath(), FileKind.UNKNOWN, 64);
        assertEquals(SourceStatus.NEEDS_ATTENTION,
                SourceStatusResolver.resolve(unknown, ProjectInventoryJobState.UNSUPPORTED, false, true, false));
    }

    @Test
    void missingFileIsUnavailableAndNeverSilentlyReady() {
        WorkspaceFile file = measurement("moved.mf4");
        // Even a fully inspected, fully processed source is Unavailable once its file is gone.
        assertEquals(SourceStatus.UNAVAILABLE,
                SourceStatusResolver.resolve(file, ProjectInventoryJobState.READY, true, false, true));
    }

    @Test
    void invalidRestoredSourceNeedsAttention() {
        WorkspaceFile file = measurement("invalid.mf4");
        file.setStatus(FileValidationStatus.INVALID, "unreadable");
        assertEquals(SourceStatus.NEEDS_ATTENTION,
                SourceStatusResolver.resolve(file, ProjectInventoryJobState.READY, true, true, false));
    }

    @Test
    void directDatStaysReadyWhileMf4WithoutInventoryIsStillReading() {
        WorkspaceFile dat = new WorkspaceFile(Path.of("capture.dat").toAbsolutePath(), FileKind.MEASUREMENT_DAT, 1024);
        dat.setStatus(FileValidationStatus.READY, "Direct processing. DBC is not required.");
        WorkspaceFile mf4 = measurement("vehicle.mf4");
        SourceStatus datStatus = SourceStatusResolver.resolve(dat, ProjectInventoryJobState.ADDED, false, true, false);
        SourceStatus mf4Status = SourceStatusResolver.resolve(mf4, ProjectInventoryJobState.ADDED, false, true, false);
        assertEquals(SourceStatus.READY, datStatus);
        assertEquals(SourceStatus.READING, mf4Status);
        assertTrue(datStatus.usableForDecode());
        assertFalse(mf4Status.usableForDecode());
    }

    @Test
    void onlyReadyAndProcessedSourcesCanContinueToDecode() {
        assertTrue(SourceStatus.READY.usableForDecode());
        assertTrue(SourceStatus.PROCESSED.usableForDecode());
        assertFalse(SourceStatus.READING.usableForDecode());
        assertFalse(SourceStatus.NEEDS_ATTENTION.usableForDecode());
        assertFalse(SourceStatus.UNAVAILABLE.usableForDecode());
    }

    @Test
    void userFacingLabelsDoNotLeakInternalEnumNames() {
        assertEquals("Reading", SourceStatus.READING.displayLabel());
        assertEquals("Ready", SourceStatus.READY.displayLabel());
        assertEquals("Needs Attention", SourceStatus.NEEDS_ATTENTION.displayLabel());
        assertEquals("Unavailable", SourceStatus.UNAVAILABLE.displayLabel());
        assertEquals("Processed", SourceStatus.PROCESSED.displayLabel());
    }
}
