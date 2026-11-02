package com.stellar.platform.api.worker;

import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.storage.metadata.ProcessingJobMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("release-acceptance")
class WorkerCrashJobLifecycleTest {

    @TempDir
    Path temp;

    @Test
    @Timeout(45)
    void workerDeathMarksRunningJobInterruptedAndDesktopSurvives() throws Exception {
        Path db = temp.resolve("stellar.db");
        String projectId = "crash-proj";
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata(projectId, "Crash", Instant.now(), Instant.now(), "DECODE", null));
            store.upsertProcessingJob(new ProcessingJobMetadata(
                    "job-crash", projectId, "run-crash", "src-1", 0, "def-1",
                    JobExecutionStatus.RUNNING, 1, 3, 0.2d, "DECODE", "w",
                    Instant.now(), Instant.now(), null, null, ""));
            ProcessIsolatedLocalWorker worker = ProcessIsolatedLocalWorker.create();
            long parentPid = ProcessHandle.current().pid();
            try {
                assertTrue(worker.workerPid() != parentPid);
                CompletableFuture<WorkerMessage> inFlight = CompletableFuture.supplyAsync(() -> {
                    try {
                        return worker.submitTask(ProcessingTaskDescriptor.sleep(20_000L)).get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                Thread.sleep(400L);
                worker.destroyForcibly();
                assertFalse(worker.isAlive());
                assertTrue(ProcessHandle.current().isAlive(), "desktop/test JVM must survive worker death");
                inFlight.orTimeout(8, TimeUnit.SECONDS).exceptionally(ex -> null).join();
                int marked = new LocalWorkerSupervisor(store).markRunningJobsInterrupted(
                        projectId, "worker process terminated");
                assertEquals(1, marked);
                assertEquals(JobExecutionStatus.INTERRUPTED,
                        store.listUnfinishedProcessingJobs(projectId).getFirst().status());
            } finally {
                worker.shutdown();
            }
            try (ProcessIsolatedLocalWorker restarted = ProcessIsolatedLocalWorker.create()) {
                assertTrue(restarted.isAlive());
                assertTrue(restarted.ping());
            }
        }
    }
}
