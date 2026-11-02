package com.stellar.platform.api.worker;

import com.stellar.platform.api.support.ProcessingPlanDtoMapper;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("release-acceptance")
class IsolatedPlanExecutionTest {

    @TempDir
    Path temp;

    @AfterEach
    void clearStorage() {
        AnalysisStorageRoots.clear();
    }

    @Test
    void inProcessEnginePersistsReusableArtifact() throws Exception {
        Path analysis = temp.resolve("analysis-inproc");
        Files.createDirectories(analysis);
        AnalysisStorageRoots.setUserSelectedRoot(analysis);
        Path projectRoot = temp.resolve("project-inproc");
        Path db = projectRoot.resolve(".stellar").resolve("stellar.db");
        Files.createDirectories(db.getParent());
        String projectId = UUID.randomUUID().toString();
        Path measurement = copyAsc(temp.resolve("inputs-inproc").resolve("sample.asc"));
        Path definition = writeDbc(temp.resolve("inputs-inproc").resolve("sample.dbc"));
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata(projectId, "InProcess", Instant.now(), Instant.now(), "DECODE", null));
        }
        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "plan-inproc",
                Instant.now(),
                List.of(new ProcessingJob(
                        ProcessingJobKey.of("src-1", 0),
                        MeasurementSourceFormat.ASC,
                        MappingLifecycle.CONFIRMED,
                        "def-1",
                        "sample.dbc",
                        1L, 1L, 0L, 4L, 1.0, 1L, 1.0,
                        null, true, List.of())),
                ProcessingPlanReadiness.READY,
                List.of());
        ProcessingTaskDescriptor task = ProcessingTaskDescriptor.plan(
                "task-inproc",
                projectId,
                "run-inproc",
                db.toAbsolutePath().toString(),
                analysis.toAbsolutePath().toString(),
                ProcessingPlanDtoMapper.toJson(plan),
                Map.of("src-1", measurement.toAbsolutePath().toString()),
                Map.of("def-1", definition.toAbsolutePath().toString()),
                true);
        LocalPlanWorkerEngine.Outcome outcome = new LocalPlanWorkerEngine().execute(task, null, null);
        assertEquals("SUCCESS", outcome.status(), outcome.toJson());
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertTrue(store.findValidArtifact(projectId, "src-1", 0, "def-1").isPresent(),
                    "in-process engine must persist a reusable artifact");
        }
    }

    @Test
    void inProcessEngineCompletesNativeOnlyNoDbcPlan() throws Exception {
        Path analysis = temp.resolve("analysis-native");
        Files.createDirectories(analysis);
        AnalysisStorageRoots.setUserSelectedRoot(analysis);
        Path projectRoot = temp.resolve("project-native");
        Path db = projectRoot.resolve(".stellar").resolve("stellar.db");
        Files.createDirectories(db.getParent());
        String projectId = UUID.randomUUID().toString();
        Path measurement = copyAsc(temp.resolve("inputs-native").resolve("sample.asc"));
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata(projectId, "NativeOnly", Instant.now(), Instant.now(), "DECODE", null));
        }
        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "plan-native",
                Instant.now(),
                List.of(new ProcessingJob(
                        ProcessingJobKey.of("src-1", 0),
                        MeasurementSourceFormat.ASC,
                        MappingLifecycle.NO_MATCH,
                        null,
                        "",
                        1L, 0L, 1L, 1L, 0.0, 0L, 0.0,
                        null, true, List.of())),
                ProcessingPlanReadiness.READY,
                List.of());
        ProjectProcessingPlan reconstructed = ProcessingPlanDtoMapper.toDomain(
                ProcessingPlanDtoMapper.toDto(plan));
        assertTrue(reconstructed.jobs().getFirst().executable());
        assertTrue(reconstructed.jobs().getFirst().definitionSourceId().isEmpty());
        assertEquals(MappingLifecycle.NO_MATCH, reconstructed.jobs().getFirst().mappingLifecycle());

        ProcessingTaskDescriptor task = ProcessingTaskDescriptor.plan(
                "task-native",
                projectId,
                "run-native",
                db.toAbsolutePath().toString(),
                analysis.toAbsolutePath().toString(),
                ProcessingPlanDtoMapper.toJson(plan),
                Map.of("src-1", measurement.toAbsolutePath().toString()),
                Map.of(),
                true);
        LocalPlanWorkerEngine.Outcome outcome = new LocalPlanWorkerEngine().execute(task, null, null);
        assertEquals("SUCCESS", outcome.status(), outcome.toJson());
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertTrue(store.findValidArtifact(projectId, "src-1", 0, "").isPresent(),
                    "native-only engine must persist an artifact under an empty definition id");
        }
    }

    @Test
    @Timeout(60)
    void isolatedWorkerDecodesPlanInADifferentPid() throws Exception {
        Path analysis = temp.resolve("analysis");
        Files.createDirectories(analysis);
        AnalysisStorageRoots.setUserSelectedRoot(analysis);
        Path projectRoot = temp.resolve("project");
        Path db = projectRoot.resolve(".stellar").resolve("stellar.db");
        Files.createDirectories(db.getParent());
        String projectId = UUID.randomUUID().toString();
        Path measurement = copyAsc(temp.resolve("inputs").resolve("sample.asc"));
        Path definition = writeDbc(temp.resolve("inputs").resolve("sample.dbc"));
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata(projectId, "Isolated", Instant.now(), Instant.now(), "DECODE", null));
        }

        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "plan-iso",
                Instant.now(),
                List.of(new ProcessingJob(
                        ProcessingJobKey.of("src-1", 0),
                        MeasurementSourceFormat.ASC,
                        MappingLifecycle.CONFIRMED,
                        "def-1",
                        "sample.dbc",
                        1L, 1L, 0L, 4L, 1.0, 1L, 1.0,
                        null, true, List.of())),
                ProcessingPlanReadiness.READY,
                List.of());

        long parentPid = ProcessHandle.current().pid();
        try (ProcessIsolatedLocalWorker worker = ProcessIsolatedLocalWorker.create()) {
            assertNotEquals(parentPid, worker.workerPid());
            assertTrue(worker.ping(), "PING must be answered with PONG");
            assertTrue(worker.health().alive());
            Path java = worker.javaExecutable();
            assertTrue(java.getFileName().toString().toLowerCase().startsWith("java"));
            assertTrue(java.startsWith(Path.of(System.getProperty("java.home")).toAbsolutePath().normalize()));
            ProcessingTaskDescriptor task = ProcessingTaskDescriptor.plan(
                    "task-iso",
                    projectId,
                    "run-iso",
                    db.toAbsolutePath().toString(),
                    analysis.toAbsolutePath().toString(),
                    ProcessingPlanDtoMapper.toJson(plan),
                    Map.of("src-1", measurement.toAbsolutePath().toString()),
                    Map.of("def-1", definition.toAbsolutePath().toString()),
                    true);
            WorkerMessage done = worker.submitTask(task, incoming -> {
                if (incoming.task() != null && !WorkerProtocol.START_JOB.equals(incoming.type())) {
                    throw new AssertionError("progress/event retained task payload type=" + incoming.type());
                }
            }).get(45, TimeUnit.SECONDS);
            assertEquals(WorkerProtocol.COMPLETED, done.type(),
                    "isolated PLAN must complete in the worker JVM: type=" + done.type()
                            + " classification=" + done.classification()
                            + " message=" + done.message()
                            + " token=" + done.token());
        }

        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertTrue(store.findValidArtifact(projectId, "src-1", 0, "def-1").isPresent(),
                    "worker must persist a reusable artifact the desktop can reopen");
        }
    }

    private static Path copyAsc(Path target) throws Exception {
        Files.createDirectories(target.getParent());
        try (var in = IsolatedPlanExecutionTest.class.getResourceAsStream("/asc/classical_basic.asc")) {
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
