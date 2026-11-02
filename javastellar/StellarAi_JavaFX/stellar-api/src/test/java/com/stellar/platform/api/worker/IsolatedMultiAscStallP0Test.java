package com.stellar.platform.api.worker;

import com.stellar.platform.api.support.ProcessingPlanDtoMapper;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.storage.metadata.ProcessingJobMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0: twenty sequential ASC jobs through the isolated worker must each terminalize.
 * Reproduces the undrained worker stdout/stderr pipe stall (1 of 20 never advances).
 */
class IsolatedMultiAscStallP0Test {

    private static final int JOB_COUNT = 20;

    @TempDir
    Path temp;

    @AfterEach
    void clearStorage() {
        AnalysisStorageRoots.clear();
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void twentyAscSourcesEachTerminalizeOnce() throws Exception {
        Path analysis = temp.resolve("analysis");
        Files.createDirectories(analysis);
        AnalysisStorageRoots.setUserSelectedRoot(analysis);
        Path db = temp.resolve("project").resolve(".stellar").resolve("stellar.db");
        Files.createDirectories(db.getParent());
        String projectId = UUID.randomUUID().toString();
        Path definition = writeDbc(temp.resolve("inputs").resolve("sample.dbc"));
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata(projectId, "multi-asc", Instant.now(), Instant.now(), "DECODE", null));
        }

        List<ProcessingJob> jobs = new ArrayList<>();
        Map<String, String> sources = new LinkedHashMap<>();
        for (int i = 0; i < JOB_COUNT; i++) {
            String sourceId = "src-" + i;
            Path measurement = copyAsc(temp.resolve("inputs").resolve("run-" + i + ".asc"));
            sources.put(sourceId, measurement.toAbsolutePath().toString());
            jobs.add(new ProcessingJob(
                    ProcessingJobKey.of(sourceId, 1),
                    MeasurementSourceFormat.ASC,
                    MappingLifecycle.CONFIRMED,
                    "def-1",
                    "sample.dbc",
                    1L, 1L, 0L, 1L, 1.0, 1L, 1.0,
                    null, true, List.of()));
        }

        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "plan-20-asc", Instant.now(), jobs, ProcessingPlanReadiness.READY, List.of());
        try (ProcessIsolatedLocalWorker worker = ProcessIsolatedLocalWorker.create()) {
            ProcessingTaskDescriptor task = ProcessingTaskDescriptor.plan(
                    "task-20-asc",
                    projectId,
                    "run-20-asc",
                    db.toAbsolutePath().toString(),
                    analysis.toAbsolutePath().toString(),
                    ProcessingPlanDtoMapper.toJson(plan),
                    sources,
                    Map.of("def-1", definition.toAbsolutePath().toString()),
                    true);
            WorkerMessage done = worker.submitTask(task).get(40, TimeUnit.SECONDS);
            assertEquals(WorkerProtocol.COMPLETED, done.type(),
                    "20-ASC isolated PLAN must reach a worker terminal: type=" + done.type()
                            + " classification=" + done.classification()
                            + " message=" + done.message());
        }

        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            List<ProcessingJobMetadata> persisted = store.listProcessingJobs(projectId);
            assertEquals(JOB_COUNT, persisted.size(), "each ASC source must persist exactly one job row");
            Set<String> sourceIds = persisted.stream()
                    .map(ProcessingJobMetadata::sourceId)
                    .collect(Collectors.toSet());
            assertEquals(JOB_COUNT, sourceIds.size(), "20 distinct source identities");
            for (ProcessingJobMetadata job : persisted) {
                assertTrue(job.status().isTerminal(), job.jobId() + " status=" + job.status());
                assertTrue(job.status().isSuccessful(), job.jobId() + " status=" + job.status());
            }
            for (int i = 0; i < JOB_COUNT; i++) {
                String sourceId = "src-" + i;
                assertTrue(store.findValidArtifact(projectId, sourceId, 1, "def-1").isPresent(),
                        "missing artifact for " + sourceId);
            }
        }
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void validThenInvalidThenValidContinues() throws Exception {
        Path analysis = temp.resolve("analysis-abc");
        Files.createDirectories(analysis);
        AnalysisStorageRoots.setUserSelectedRoot(analysis);
        Path db = temp.resolve("project-abc").resolve(".stellar").resolve("stellar.db");
        Files.createDirectories(db.getParent());
        String projectId = UUID.randomUUID().toString();
        Path definition = writeDbc(temp.resolve("inputs-abc").resolve("sample.dbc"));
        Path validA = copyAsc(temp.resolve("inputs-abc").resolve("a.asc"));
        Path validC = copyAsc(temp.resolve("inputs-abc").resolve("c.asc"));
        Path missingB = temp.resolve("inputs-abc").resolve("missing-b.asc");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata(projectId, "abc", Instant.now(), Instant.now(), "DECODE", null));
        }
        List<ProcessingJob> jobs = List.of(
                job("src-a", 1),
                job("src-b", 1),
                job("src-c", 1));
        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "plan-abc", Instant.now(), jobs, ProcessingPlanReadiness.READY, List.of());
        try (ProcessIsolatedLocalWorker worker = ProcessIsolatedLocalWorker.create()) {
            ProcessingTaskDescriptor task = ProcessingTaskDescriptor.plan(
                    "task-abc",
                    projectId,
                    "run-abc",
                    db.toAbsolutePath().toString(),
                    analysis.toAbsolutePath().toString(),
                    ProcessingPlanDtoMapper.toJson(plan),
                    Map.of(
                            "src-a", validA.toAbsolutePath().toString(),
                            "src-b", missingB.toAbsolutePath().toString(),
                            "src-c", validC.toAbsolutePath().toString()),
                    Map.of("def-1", definition.toAbsolutePath().toString()),
                    true);
            WorkerMessage done = worker.submitTask(task).get(40, TimeUnit.SECONDS);
            assertTrue(WorkerProtocol.isTerminal(done.type()), "ABC plan must terminalize: " + done.type());
        }
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            Map<String, ProcessingJobMetadata> bySource = store.listProcessingJobs(projectId).stream()
                    .collect(Collectors.toMap(ProcessingJobMetadata::sourceId, j -> j, (a, b) -> a));
            assertTrue(bySource.get("src-a").status().isSuccessful(), "A=" + bySource.get("src-a").status());
            assertTrue(bySource.get("src-b").status().isTerminal(), "B=" + bySource.get("src-b").status());
            assertEquals(JobExecutionStatus.FAILED, bySource.get("src-b").status());
            assertTrue(bySource.get("src-c").status().isSuccessful(), "C=" + bySource.get("src-c").status());
            assertTrue(store.findValidArtifact(projectId, "src-a", 1, "def-1").isPresent());
            assertTrue(store.findValidArtifact(projectId, "src-c", 1, "def-1").isPresent());
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void cancelDuringPlanDoesNotInventSuccess() throws Exception {
        Path analysis = temp.resolve("analysis-cancel");
        Files.createDirectories(analysis);
        AnalysisStorageRoots.setUserSelectedRoot(analysis);
        Path db = temp.resolve("project-cancel").resolve(".stellar").resolve("stellar.db");
        Files.createDirectories(db.getParent());
        String projectId = UUID.randomUUID().toString();
        Path definition = writeDbc(temp.resolve("inputs-cancel").resolve("sample.dbc"));
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata(projectId, "cancel", Instant.now(), Instant.now(), "DECODE", null));
        }
        List<ProcessingJob> jobs = List.of(job("src-a", 1), job("src-b", 1), job("src-c", 1));
        Map<String, String> sources = Map.of(
                "src-a", copyAsc(temp.resolve("inputs-cancel").resolve("a.asc")).toAbsolutePath().toString(),
                "src-b", copyAsc(temp.resolve("inputs-cancel").resolve("b.asc")).toAbsolutePath().toString(),
                "src-c", copyAsc(temp.resolve("inputs-cancel").resolve("c.asc")).toAbsolutePath().toString());
        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "plan-cancel", Instant.now(), jobs, ProcessingPlanReadiness.READY, List.of());
        try (ProcessIsolatedLocalWorker worker = ProcessIsolatedLocalWorker.create()) {
            ProcessingTaskDescriptor task = ProcessingTaskDescriptor.plan(
                    "task-cancel",
                    projectId,
                    "run-cancel",
                    db.toAbsolutePath().toString(),
                    analysis.toAbsolutePath().toString(),
                    ProcessingPlanDtoMapper.toJson(plan),
                    sources,
                    Map.of("def-1", definition.toAbsolutePath().toString()),
                    true);
            Thread canceller = new Thread(() -> {
                try {
                    Thread.sleep(30L);
                    worker.requestCancel("task-cancel");
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }, "stellar-test-cancel");
            canceller.setDaemon(true);
            canceller.start();
            WorkerMessage done = worker.submitTask(task).get(25, TimeUnit.SECONDS);
            assertTrue(WorkerProtocol.isTerminal(done.type()), "cancel path must terminalize: " + done.type());
            if (WorkerProtocol.JOB_CANCELLED.equals(done.type())
                    || (done.message() != null && done.message().toLowerCase().contains("cancel"))) {
                assertTrue(WorkerProtocol.isTerminal(done.type()));
            } else {
                assertTrue(WorkerProtocol.isSuccessfulTerminal(done.type())
                        || WorkerProtocol.JOB_CANCELLED.equals(done.type())
                        || WorkerProtocol.FAILED.equals(done.type()),
                        "unexpected terminal " + done.type());
            }
        }
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            for (ProcessingJobMetadata job : store.listProcessingJobs(projectId)) {
                assertTrue(job.status().isTerminal()
                                || job.status() == JobExecutionStatus.RUNNING
                                || job.status() == JobExecutionStatus.INTERRUPTED,
                        job.sourceId() + " " + job.status());
                if (job.status() == JobExecutionStatus.SUCCESS) {
                    assertTrue(store.findValidArtifact(projectId, job.sourceId(), 1, "def-1").isPresent(),
                            "SUCCESS without artifact " + job.sourceId());
                }
            }
        }
    }

    private static ProcessingJob job(String sourceId, int channel) {
        return new ProcessingJob(
                ProcessingJobKey.of(sourceId, channel),
                MeasurementSourceFormat.ASC,
                MappingLifecycle.CONFIRMED,
                "def-1",
                "sample.dbc",
                1L, 1L, 0L, 1L, 1.0, 1L, 1.0,
                null, true, List.of());
    }

    private static Path copyAsc(Path target) throws Exception {
        Files.createDirectories(target.getParent());
        try (var in = IsolatedMultiAscStallP0Test.class.getResourceAsStream("/asc/classical_basic.asc")) {
            if (in == null) {
                Files.writeString(target, """
                        date Thu Jan 01 12:00:00.000 am 2026
                        base hex timestamps absolute
                        Begin Triggerblock Thu Jan 01 12:00:00.000 am 2026
                          0.100000 1 123 Rx d 8 01 02 03 04 05 06 07 08
                        End TriggerBlock
                        """);
            } else {
                Files.copy(in, target);
            }
        }
        return target;
    }

    private static Path writeDbc(Path target) throws Exception {
        Files.createDirectories(target.getParent());
        Files.writeString(target, """
                VERSION ""
                NS_ :
                BS_:
                BU_: ECU
                BO_ 291 TestMsg: 8 ECU
                 SG_ Sig1 : 0|8@1+ (1,0) [0|255] "" ECU
                """);
        return target;
    }
}
