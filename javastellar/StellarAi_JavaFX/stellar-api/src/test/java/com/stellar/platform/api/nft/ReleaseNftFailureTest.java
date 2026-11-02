package com.stellar.platform.api.nft;

import com.stellar.platform.api.support.ProcessingPlanDtoMapper;
import com.stellar.platform.api.worker.LocalPlanWorkerEngine;
import com.stellar.platform.api.worker.ProcessingTaskDescriptor;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.storage.metadata.AuditEventType;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.processing.graph.LosslessGraphSignalStore;
import com.stellar.platform.processing.runtime.LocalResourceManager;
import com.stellar.platform.storage.ArtifactStorageRefs;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.ProcessingCheckpointMetadata;
import com.stellar.platform.storage.metadata.ProcessingCheckpointState;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("release-nft")
class ReleaseNftFailureTest {

    @TempDir
    Path temp;

    @AfterEach
    void clear() {
        AnalysisStorageRoots.clear();
    }

    @Test
    void lowDiskAdmissionUsesSpecificMessage() {
        LocalResourceManager tight = new LocalResourceManager(1024L);
        LocalResourceManager.Admission admission = tight.admit(temp, 2048L, 0, 8);
        assertFalse(admission.allowed());
        assertEquals("Insufficient analysis storage space", admission.reason());
        System.out.println("PHASE9C_FAIL lowDisk reason=" + admission.reason());
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void storageLossCorruptArtifactCancelAndRepeatedCrash() throws Exception {
        Path storageHome = temp.resolve("storage-home");
        Path analysis = storageHome.resolve("analysis");
        Files.createDirectories(analysis);
        AnalysisStorageRoots.setUserSelectedRoot(analysis);
        Path dbc = GeneratedValidMf4Writer.writeDbc(temp.resolve("f.dbc"));
        Path mf4 = GeneratedValidMf4Writer.write(temp.resolve("f.mf4"), 4000);

        Path db = temp.resolve("p/.stellar/stellar.db");
        Files.createDirectories(db.getParent());
        String projectId = UUID.randomUUID().toString();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata(projectId, "fail", Instant.now(), Instant.now(), "DECODE", null));
        }
        ProcessingTaskDescriptor task = task(projectId, db, analysis, mf4, dbc, true)
                .withProcessingBatchSize(256);
        LocalPlanWorkerEngine.Outcome ok = new LocalPlanWorkerEngine().execute(task, null, null);
        assertEquals("SUCCESS", ok.status(), ok.toJson());
        Path artifact = ArtifactStorageRefs.jobRoot(projectId, "src-1", 0, "def-1");
        assertTrue(Files.isDirectory(artifact));
        assertTrue(LosslessGraphSignalStore.hasValidIndex(artifact));

        Path moved = temp.resolve("storage-offline");
        Files.move(storageHome, moved);
        AnalysisStorageRoots.setUserSelectedRoot(analysis);
        LocalResourceManager.Admission missing = new LocalResourceManager().admit(analysis, 1024L, 0, 8);
        assertFalse(missing.allowed(), "missing storage must not silently admit via a live parent");
        assertTrue(missing.reason().toLowerCase().contains("analysis storage"), missing.reason());
        boolean openFailed = false;
        String openMsg = "";
        try {
            GraphSignalSessionStore.openExisting(artifact).close();
        } catch (RuntimeException ex) {
            openFailed = true;
            openMsg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        }
        System.out.println("PHASE9C_FAIL storageUnavailable admit=" + missing.reason()
                + " openFailed=" + openFailed + " openMsg=" + openMsg);
        assertTrue(openFailed || !LosslessGraphSignalStore.hasValidIndex(artifact));

        Files.move(moved, storageHome);
        AnalysisStorageRoots.setUserSelectedRoot(analysis);
        try (GraphSignalSessionStore restored = GraphSignalSessionStore.openExisting(artifact)) {
            assertTrue(restored.losslessStore().totalSampleCount() > 0);
        }

        Path index = artifact.resolve(LosslessGraphSignalStore.INDEX_FILE_NAME);
        Path committed = artifact.resolve(LosslessGraphSignalStore.COMMITTED_INDEX_FILE_NAME);
        if (Files.exists(index)) {
            Files.write(index, new byte[] {0x00, 0x01, 0x02});
        }
        if (Files.exists(committed)) {
            Files.delete(committed);
        }
        assertFalse(LosslessGraphSignalStore.hasValidIndex(artifact)
                && LosslessGraphSignalStore.segmentsReadable(artifact));
        System.out.println("PHASE9C_FAIL corruptArtifact hasValidIndex="
                + LosslessGraphSignalStore.hasValidIndex(artifact)
                + " segmentsReadable=" + LosslessGraphSignalStore.segmentsReadable(artifact));

        Path cdb = temp.resolve("cancel/.stellar/stellar.db");
        Files.createDirectories(cdb.getParent());
        String cancelId = UUID.randomUUID().toString();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(cdb)) {
            store.upsertProject(new ProjectMetadata(cancelId, "cancel", Instant.now(), Instant.now(), "DECODE", null));
        }
        AtomicBoolean cancel = new AtomicBoolean(true);
        LocalPlanWorkerEngine.Outcome cancelled = new LocalPlanWorkerEngine().execute(
                task(cancelId, cdb, analysis, mf4, dbc, true).withProcessingBatchSize(64), null, cancel);
        System.out.println("PHASE9C_FAIL cancel status=" + cancelled.status());
        assertNotEquals("SUCCESS", cancelled.status());

        Path crashDb = temp.resolve("crash/.stellar/stellar.db");
        Files.createDirectories(crashDb.getParent());
        String crashId = UUID.randomUUID().toString();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(crashDb)) {
            store.upsertProject(new ProjectMetadata(crashId, "crash", Instant.now(), Instant.now(), "DECODE", null));
        }
        ProcessingTaskDescriptor crashTask = task(crashId, crashDb, analysis, mf4, dbc, true)
                .withProcessingBatchSize(256)
                .withInterruptAfterCheckpoints(1);
        new LocalPlanWorkerEngine().execute(crashTask, null, null);
        new LocalPlanWorkerEngine().execute(
                task(crashId, crashDb, analysis, mf4, dbc, false).withProcessingBatchSize(256)
                        .withInterruptAfterCheckpoints(1), null, null);
        LocalPlanWorkerEngine.Outcome resumed = new LocalPlanWorkerEngine().execute(
                task(crashId, crashDb, analysis, mf4, dbc, false).withProcessingBatchSize(256), null, null);
        LocalPlanWorkerEngine.Outcome uninterrupted = new LocalPlanWorkerEngine().execute(
                task(UUID.randomUUID().toString(), crashDb, analysis, mf4, dbc, true)
                        .withProcessingBatchSize(256), null, null);
        Path crashArt = ArtifactStorageRefs.jobRoot(crashId, "src-1", 0, "def-1");
        long resumeSamples = 0L;
        if (Files.isDirectory(crashArt)) {
            try (GraphSignalSessionStore s = GraphSignalSessionStore.openExisting(crashArt)) {
                resumeSamples = s.losslessStore().totalSampleCount();
            }
        }
        System.out.println("PHASE9C_FAIL repeatedCrash resume=" + resumed.status()
                + " uninterrupted=" + uninterrupted.status() + " resumeSamples=" + resumeSamples);
        assertEquals("SUCCESS", resumed.status(), resumed.toJson());
        assertEquals(4000L, resumeSamples);

        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(crashDb)) {
            long audit = store.listRecentAuditEvents(crashId, 200).stream()
                    .filter(e -> e.eventType() == AuditEventType.PROCESSING_CHECKPOINTED)
                    .count();
            int recent = store.listRecentAuditEvents(crashId, 200).size();
            System.out.println("PHASE9C_FAIL auditCheckpointEvents=" + audit
                    + " totalRecent=" + recent);
            assertEquals(0L, audit, "mid-file checkpoints must not write user audit rows");
            assertTrue(recent < 40, "user audit must stay job-scoped, not batch-scoped");
        }

        Path wal = crashDb.resolveSibling(crashDb.getFileName().toString() + "-wal");
        long walBytes = Files.exists(wal) ? Files.size(wal) : 0L;
        System.out.println("PHASE9C_FAIL walBytes=" + walBytes + " present=" + Files.exists(wal));

        Path corruptDb = temp.resolve("corrupt-cp/.stellar/stellar.db");
        Files.createDirectories(corruptDb.getParent());
        String corruptId = UUID.randomUUID().toString();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(corruptDb)) {
            store.upsertProject(new ProjectMetadata(corruptId, "ccp", Instant.now(), Instant.now(), "DECODE", null));
        }
        ProcessingTaskDescriptor cTask = task(corruptId, corruptDb, analysis, mf4, dbc, true)
                .withProcessingBatchSize(256)
                .withInterruptAfterCheckpoints(1);
        new LocalPlanWorkerEngine().execute(cTask, null, null);
        String jobId = ProcessingJobKey.of("src-1", 0).toString();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(corruptDb)) {
            ProcessingCheckpointMetadata cp = store.findProcessingCheckpoint(jobId).orElseThrow();
            store.upsertProcessingCheckpoint(new ProcessingCheckpointMetadata(
                    cp.checkpointId(), cp.projectId(), cp.jobId(), cp.sourceId(),
                    cp.sourceFingerprint(), cp.dbcFingerprint(), cp.planFingerprint(), cp.artifactVersion(),
                    cp.inputPosition(), cp.batchSequence(), cp.lastTimestampNanos(),
                    "this is not a cursor", cp.committedArtifactRef(),
                    ProcessingCheckpointState.RESUMABLE, Instant.now()));
        }
        LocalPlanWorkerEngine.Outcome afterCorrupt = new LocalPlanWorkerEngine().execute(
                task(corruptId, corruptDb, analysis, mf4, dbc, false).withProcessingBatchSize(256), null, null);
        System.out.println("PHASE9C_FAIL corruptCheckpoint status=" + afterCorrupt.status());
        assertEquals("SUCCESS", afterCorrupt.status(), afterCorrupt.toJson());
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(corruptDb)) {
            assertTrue(store.listRecentAuditEvents(corruptId, 50).stream()
                    .anyMatch(e -> e.eventType() == AuditEventType.CHECKPOINT_INVALIDATED));
        }
    }

    private static ProcessingTaskDescriptor task(
            String projectId, Path db, Path analysis, Path mf4, Path dbc, boolean force
    ) {
        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "fail", Instant.now(),
                List.of(new ProcessingJob(
                        ProcessingJobKey.of("src-1", 0),
                        MeasurementSourceFormat.MF4,
                        MappingLifecycle.CONFIRMED,
                        "def-1", "f.dbc",
                        1L, 1L, 0L, 0L, 1.0, 0L, 1.0,
                        null, true, List.of())),
                ProcessingPlanReadiness.READY, List.of());
        return ProcessingTaskDescriptor.plan(
                "t-" + projectId.substring(0, 8),
                projectId,
                "r-" + projectId.substring(0, 8),
                db.toAbsolutePath().toString(),
                analysis.toAbsolutePath().toString(),
                ProcessingPlanDtoMapper.toJson(plan),
                Map.of("src-1", mf4.toAbsolutePath().toString()),
                Map.of("def-1", dbc.toAbsolutePath().toString()),
                force);
    }
}
