package com.stellar.platform.api.worker;

import com.stellar.platform.api.support.ProcessingPlanDtoMapper;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.storage.ArtifactStorageRefs;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.AuditEventType;
import com.stellar.platform.storage.metadata.ProcessingBatchStatus;
import com.stellar.platform.storage.metadata.ProcessingCheckpointMetadata;
import com.stellar.platform.storage.metadata.ProcessingCheckpointState;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("release-acceptance")
class Mf4CheckpointResumeTest {

    @TempDir
    Path temp;

    @AfterEach
    void clearStorage() {
        AnalysisStorageRoots.clear();
    }

    @Test
    void inProcessCrashResumeMatchesUninterruptedExactlyOnce() throws Exception {
        Harness h = harness("inproc", 24);
        Snapshot a = runToCompletion(h, true);
        long t0 = System.nanoTime();
        Snapshot baselineTimed = runToCompletion(h, true);
        long uninterruptedNs = System.nanoTime() - t0;
        assertEquals(a.fingerprint(), baselineTimed.fingerprint());

        Harness crash = harness("crash", 24);
        Files.copy(h.measurement, crash.measurement, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(h.definition, crash.definition, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        LocalPlanWorkerEngine.Outcome interrupted = new LocalPlanWorkerEngine().execute(
                crash.task(true).withProcessingBatchSize(8).withInterruptAfterCheckpoints(1), null, null);
        assertTrue(interrupted.status().contains("FAIL") || interrupted.status().contains("SUCCESS"),
                interrupted.toJson());
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(crash.db)) {
            assertTrue(store.findProcessingCheckpoint(crash.jobId()).isPresent());
            assertTrue(store.listProcessingBatches(crash.jobId()).stream()
                    .allMatch(b -> b.status() == ProcessingBatchStatus.COMMITTED));
        }

        LocalPlanWorkerEngine.Outcome resumed = new LocalPlanWorkerEngine().execute(
                crash.task(false).withProcessingBatchSize(8), null, null);
        assertEquals("SUCCESS", resumed.status(), resumed.toJson());
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(crash.db)) {
            long checkpointAudits = store.listRecentAuditEvents(crash.projectId(), 100).stream()
                    .filter(e -> e.eventType() == AuditEventType.PROCESSING_CHECKPOINTED)
                    .count();
            assertEquals(0L, checkpointAudits, "per-batch checkpoints must not flood user audit");
        }
        Snapshot b = snapshot(crash);
        assertEquals(a.fingerprint(), b.fingerprint(), "crash-resume output must equal uninterrupted");

        Harness again = harness("repeat", 24);
        Files.copy(h.measurement, again.measurement, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(h.definition, again.definition, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        new LocalPlanWorkerEngine().execute(again.task(true).withProcessingBatchSize(8).withInterruptAfterCheckpoints(1), null, null);
        new LocalPlanWorkerEngine().execute(again.task(false).withProcessingBatchSize(8).withInterruptAfterCheckpoints(1), null, null);
        LocalPlanWorkerEngine.Outcome third = new LocalPlanWorkerEngine().execute(
                again.task(false).withProcessingBatchSize(8), null, null);
        assertEquals("SUCCESS", third.status(), third.toJson());
        assertEquals(a.fingerprint(), snapshot(again).fingerprint());

        long t1 = System.nanoTime();
        runToCompletion(h, true);
        long checkpointedNs = System.nanoTime() - t1;
        System.out.println("P9A-CHECKPOINT-OVERHEAD uninterruptedNs=" + uninterruptedNs
                + " checkpointedNs=" + checkpointedNs
                + " fixtureRecords=24 (not a GB-scale measurement)");
    }

    @Test
    void newRuntimeResumesFromDurableCheckpoint() throws Exception {
        Harness h = harness("runtime", 24);
        Snapshot a = runToCompletion(h, true);
        Harness crash = harness("runtime-crash", 24);
        Files.copy(h.measurement, crash.measurement, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(h.definition, crash.definition, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        new LocalPlanWorkerEngine().execute(
                crash.task(true).withProcessingBatchSize(8).withInterruptAfterCheckpoints(1), null, null);
        LocalPlanWorkerEngine.Outcome resumed = new LocalPlanWorkerEngine().execute(
                crash.task(false).withProcessingBatchSize(8), null, null);
        assertEquals("SUCCESS", resumed.status(), resumed.toJson());
        assertEquals(a.fingerprint(), snapshot(crash).fingerprint());
    }

    @Test
    void corruptCursorRestartsAffectedSourceWithoutCrashing() throws Exception {
        Harness h = harness("corrupt-cursor", 16);
        new LocalPlanWorkerEngine().execute(
                h.task(true).withProcessingBatchSize(8).withInterruptAfterCheckpoints(1), null, null);
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(h.db)) {
            ProcessingCheckpointMetadata cp = store.findProcessingCheckpoint(h.jobId()).orElseThrow();
            store.upsertProcessingCheckpoint(new ProcessingCheckpointMetadata(
                    cp.checkpointId(), cp.projectId(), cp.jobId(), cp.sourceId(),
                    cp.sourceFingerprint(), cp.dbcFingerprint(), cp.planFingerprint(), cp.artifactVersion(),
                    cp.inputPosition(), cp.batchSequence(), cp.lastTimestampNanos(),
                    "this is not a cursor", cp.committedArtifactRef(),
                    ProcessingCheckpointState.RESUMABLE, Instant.now()));
        }
        LocalPlanWorkerEngine.Outcome outcome = new LocalPlanWorkerEngine().execute(
                h.task(false).withProcessingBatchSize(8), null, null);
        assertEquals("SUCCESS", outcome.status(), outcome.toJson());
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(h.db)) {
            assertTrue(store.listRecentAuditEvents(h.projectId(), 50).stream()
                    .anyMatch(e -> e.eventType() == AuditEventType.CHECKPOINT_INVALIDATED));
        }
    }

    @Test
    void multiSourceReuseResumeAndFreshStart() throws Exception {
        Path analysis = temp.resolve("ms-analysis");
        Files.createDirectories(analysis);
        AnalysisStorageRoots.setUserSelectedRoot(analysis);
        Path db = temp.resolve("ms-project/.stellar/stellar.db");
        Files.createDirectories(db.getParent());
        String projectId = UUID.randomUUID().toString();
        Path aMf4 = writeMf4(temp.resolve("ms-a.mf4"), 8, 0x123);
        Path bMf4 = writeMf4(temp.resolve("ms-b.mf4"), 24, 0x123);
        Path cMf4 = writeMf4(temp.resolve("ms-c.mf4"), 8, 0x123);
        Path dbc = writeDbc(temp.resolve("ms.dbc"));
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata(projectId, "MS", Instant.now(), Instant.now(), "DECODE", null));
        }
        ProjectProcessingPlan aOnly = plan("ms-a", List.of(job("src-a", 0)));
        ProcessingTaskDescriptor taskA = ProcessingTaskDescriptor.plan(
                "a", projectId, "run-a", db.toString(), analysis.toString(),
                ProcessingPlanDtoMapper.toJson(aOnly),
                Map.of("src-a", aMf4.toString()), Map.of("def-1", dbc.toString()), true)
                .withProcessingBatchSize(8);
        assertEquals("SUCCESS", new LocalPlanWorkerEngine().execute(taskA, null, null).status());

        ProjectProcessingPlan bOnly = plan("ms-b", List.of(job("src-b", 0)));
        ProcessingTaskDescriptor taskB = ProcessingTaskDescriptor.plan(
                "b", projectId, "run-b", db.toString(), analysis.toString(),
                ProcessingPlanDtoMapper.toJson(bOnly),
                Map.of("src-b", bMf4.toString()), Map.of("def-1", dbc.toString()), true)
                .withProcessingBatchSize(8).withInterruptAfterCheckpoints(1);
        new LocalPlanWorkerEngine().execute(taskB, null, null);

        ProjectProcessingPlan all = plan("ms-all", List.of(job("src-a", 0), job("src-b", 0), job("src-c", 0)));
        ProcessingTaskDescriptor taskAll = ProcessingTaskDescriptor.plan(
                "all", projectId, "run-all", db.toString(), analysis.toString(),
                ProcessingPlanDtoMapper.toJson(all),
                Map.of("src-a", aMf4.toString(), "src-b", bMf4.toString(), "src-c", cMf4.toString()),
                Map.of("def-1", dbc.toString()), false)
                .withProcessingBatchSize(8);
        LocalPlanWorkerEngine.Outcome outcome = new LocalPlanWorkerEngine().execute(taskAll, null, null);
        assertEquals("SUCCESS", outcome.status(), outcome.toJson());
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertEquals(JobExecutionStatus.SUCCESS,
                    store.findProcessingJob(job("src-a", 0).key().toString()).orElseThrow().status());
            assertTrue(store.findValidArtifact(projectId, "src-a", 0, "def-1").isPresent());
            assertTrue(store.findValidArtifact(projectId, "src-b", 0, "def-1").isPresent());
            assertTrue(store.findValidArtifact(projectId, "src-c", 0, "def-1").isPresent());
        }
    }

    @Test
    @Timeout(90)
    void isolatedWorkerCrashResumesFromCheckpoint() throws Exception {
        Harness baseline = harness("iso-base", 24);
        Snapshot a = runToCompletion(baseline, true);
        Harness h = harness(Files.createTempDirectory("stellar-p9a-iso"), "iso-crash", 24);
        Files.copy(baseline.measurement, h.measurement, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(baseline.definition, h.definition, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        long parentPid = ProcessHandle.current().pid();
        AtomicInteger checkpoints = new AtomicInteger();
        ProcessIsolatedLocalWorker worker = ProcessIsolatedLocalWorker.create();
        try {
            assertNotEquals(parentPid, worker.workerPid());
            try {
                worker.submitTask(
                        h.task(true).withProcessingBatchSize(8),
                        incoming -> {
                            if (WorkerProtocol.CHECKPOINT_COMMITTED.equals(incoming.type())
                                    && checkpoints.incrementAndGet() == 1) {
                                worker.destroyForcibly();
                            }
                        }).get(40, TimeUnit.SECONDS);
            } catch (Exception expected) {
                assertTrue(ProcessHandle.current().isAlive());
            }
            assertTrue(ProcessHandle.current().isAlive(), "desktop/test JVM must survive worker death");
        } finally {
            worker.shutdown();
        }
        try (SqliteProjectMetadataStore store = openSqlite(h.db)) {
            new LocalWorkerSupervisor(store).markRunningJobsInterrupted(h.projectId, "worker killed");
        }

        try (ProcessIsolatedLocalWorker restarted = ProcessIsolatedLocalWorker.create()) {
            WorkerMessage done = restarted.submitTask(h.task(false).withProcessingBatchSize(8))
                    .get(45, TimeUnit.SECONDS);
            assertEquals(WorkerProtocol.COMPLETED, done.type(),
                    "resume worker must complete: " + done.type() + " " + done.message());
        }
        assertEquals(a.fingerprint(), snapshot(h).fingerprint(), "isolated crash-resume must match uninterrupted");
    }

    private Snapshot runToCompletion(Harness h, boolean force) {
        LocalPlanWorkerEngine.Outcome outcome = new LocalPlanWorkerEngine().execute(
                h.task(force).withProcessingBatchSize(8), null, null);
        assertEquals("SUCCESS", outcome.status(), outcome.toJson());
        return snapshot(h);
    }

    private Snapshot snapshot(Harness h) {
        Path root = ArtifactStorageRefs.jobRoot(h.projectId, "src-1", 0, "def-1");
        try (GraphSignalSessionStore store = GraphSignalSessionStore.openExisting(root)) {
            Map<String, List<String>> bySignal = new TreeMap<>();
            for (var entry : store.snapshot().entries()) {
                SignalCatalogKey key = entry.key();
                List<SignalSamplePoint> points = store.losslessStore().queryRange(key, 0L, Long.MAX_VALUE);
                List<String> rows = new ArrayList<>();
                for (SignalSamplePoint p : points) {
                    rows.add(p.timestampNanos() + "|" + p.physicalValue() + "|" + p.rawValue() + "|" + p.textualValue());
                }
                bySignal.put(key.signalName() + "/" + key.messageName() + "/" + key.canId(), rows);
            }
            return new Snapshot(store.losslessStore().totalSampleCount(), bySignal);
        }
    }

    private Harness harness(String name, int records) throws Exception {
        return harness(temp, name, records);
    }

    private Harness harness(Path root, String name, int records) throws Exception {
        Path analysis = root.resolve(name + "-analysis");
        Files.createDirectories(analysis);
        AnalysisStorageRoots.setUserSelectedRoot(analysis);
        Path projectRoot = root.resolve(name + "-project");
        Path db = projectRoot.resolve(".stellar").resolve("stellar.db");
        Files.createDirectories(db.getParent());
        String projectId = UUID.randomUUID().toString();
        Path measurement = writeMf4(root.resolve(name + ".mf4"), records, 0x123);
        Path definition = writeDbc(root.resolve(name + ".dbc"));
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata(projectId, name, Instant.now(), Instant.now(), "DECODE", null));
        }
        return new Harness(name, projectId, db, analysis, measurement, definition);
    }

    private static SqliteProjectMetadataStore openSqlite(Path db) throws InterruptedException {
        IllegalStateException last = null;
        for (int i = 0; i < 20; i++) {
            try {
                return new SqliteProjectMetadataStore(db);
            } catch (IllegalStateException ex) {
                last = ex;
                Thread.sleep(150L);
            }
        }
        throw last;
    }

    private static ProcessingJob job(String sourceId, int channel) {
        return new ProcessingJob(
                ProcessingJobKey.of(sourceId, channel),
                MeasurementSourceFormat.MF4,
                MappingLifecycle.CONFIRMED,
                "def-1",
                "sample.dbc",
                1L, 1L, 0L, 0L, 1.0, 0L, 1.0,
                null, true, List.of());
    }

    private static ProjectProcessingPlan plan(String id, List<ProcessingJob> jobs) {
        return new ProjectProcessingPlan(id, Instant.now(), jobs, ProcessingPlanReadiness.READY, List.of());
    }

    private static Path writeMf4(Path file, int count, int canId) throws Exception {
        List<byte[]> records = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] data = new byte[23];
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            buf.put(0, (byte) 0);
            buf.putInt(2, canId);
            buf.put(6, (byte) 8);
            data[7] = (byte) i;
            buf.putLong(15, 1_000_000L * i);
            records.add(data);
        }
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long time = b.writeCn(0, b.writeTx("Timestamp"), 0, 2, 1, 0, 15, 0, 64, 0);
            long payload = b.writeCn(time, b.writeTx("DataBytes"), 0, 0, 0, 9, 7, 0, 64, 0);
            long dlc = b.writeCn(payload, b.writeTx("DLC"), 0, 0, 0, 0, 6, 0, 8, 0);
            long id = b.writeCn(dlc, b.writeTx("CAN_ID"), 0, 0, 0, 0, 2, 0, 11, 0);
            long dir = b.writeCn(id, b.writeTx("Direction"), 0, 0, 0, 0, 1, 0, 8, 0);
            long head = b.writeCn(dir, b.writeTx("BusChannel"), 0, 0, 0, 0, 0, 0, 8, 0);
            long cg = b.writeCg(0, head, 1, count, 0, 23, 0);
            int n = 23 * count;
            byte[] dtPayload = new byte[n];
            for (int i = 0; i < count; i++) {
                System.arraycopy(records.get(i), 0, dtPayload, i * 23, 23);
            }
            long dt = b.writeDt(dtPayload);
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
        return file;
    }

    private static Path writeDbc(Path target) throws Exception {
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

    private record Snapshot(long samples, Map<String, List<String>> bySignal) {
        String fingerprint() {
            return samples + ":" + bySignal;
        }
    }

    private record Harness(
            String name,
            String projectId,
            Path db,
            Path analysis,
            Path measurement,
            Path definition
    ) {
        String jobId() {
            return job("src-1", 0).key().toString();
        }

        ProcessingTaskDescriptor task(boolean force) {
            ProjectProcessingPlan plan = plan(name, List.of(job("src-1", 0)));
            return ProcessingTaskDescriptor.plan(
                    "task-" + name,
                    projectId,
                    "run-" + name,
                    db.toAbsolutePath().toString(),
                    analysis.toAbsolutePath().toString(),
                    ProcessingPlanDtoMapper.toJson(plan),
                    Map.of("src-1", measurement.toAbsolutePath().toString()),
                    Map.of("def-1", definition.toAbsolutePath().toString()),
                    force);
        }
    }
}
