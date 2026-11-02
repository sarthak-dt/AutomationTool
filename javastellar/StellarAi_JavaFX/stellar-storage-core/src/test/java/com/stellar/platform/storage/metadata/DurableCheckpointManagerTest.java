package com.stellar.platform.storage.metadata;

import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableCheckpointManagerTest {

    @TempDir
    Path temp;

    @Test
    void staleFingerprintIsRejectedAndCommittedBatchIsTheResumePoint() {
        Path db = temp.resolve("cp.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p", "P", Instant.now(), Instant.now(), "SIGNALS", null));
            store.upsertProcessingJob(new ProcessingJobMetadata(
                    "job", "p", "run", "src", 1, "dbc", JobExecutionStatus.RUNNING,
                    1, 3, 0.4d, "DECODE", "w", Instant.now(), Instant.now(), null, null, ""));
            DurableCheckpointManager manager = new DurableCheckpointManager(store);
            store.upsertProcessingBatch(new ProcessingBatchMetadata(
                    "b0", "p", "job", 0, 0, 1000, 1000, 4000,
                    ProcessingBatchStatus.COMMITTED, "art/0", Instant.now(), Instant.now()));
            manager.commitBatch(
                    new ProcessingBatchMetadata(
                            "b1", "p", "job", 1, 1000, 2000, 1000, 4000,
                            ProcessingBatchStatus.COMMITTED, "art/1", Instant.now(), Instant.now()),
                    DurableCheckpointManager.resumable(
                            "p", "job", "src", "src-fp", "dbc-fp", "plan-fp",
                            2000, 1, 99L, "cursor-1", "art/1"));
            assertTrue(manager.resumableCheckpoint(
                    "job", "src-fp", "dbc-fp", "plan-fp", DurableCheckpointManager.ARTIFACT_VERSION).isPresent());
            assertFalse(manager.resumableCheckpoint(
                    "job", "src-fp", "dbc-fp", "plan-fp", ProcessingArtifactMetadata.ARTIFACT_VERSION_V1).isPresent());
            assertFalse(manager.resumableCheckpoint("job", "CHANGED", "dbc-fp", "plan-fp",
                    DurableCheckpointManager.ARTIFACT_VERSION).isPresent());
            assertEquals(1L, store.findLastCommittedBatch("job").orElseThrow().sequence());
        }
    }
}
