package com.stellar.platform.api.worker;

import com.stellar.platform.api.support.ProcessingPlanDtoMapper;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.storage.ArtifactStorageRefs;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PACKAGED_RUNTIME_TEST — uses the jpackage app-image binaries/runtime/classpath.
 * This is not FULL_GUI_MANUAL_TEST.
 */
@Tag("release-acceptance")
@EnabledIf("appImagePresent")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PackagedRuntimeIntegrationTest {

    @TempDir
    Path temp;

    private Path spacesJunction;

    static boolean appImagePresent() {
        return PackagedAppImage.present();
    }

    @AfterEach
    void clear() {
        unlinkSpacesJunction();
        AnalysisStorageRoots.clear();
        System.clearProperty("jpackage.app-path");
        System.clearProperty(WorkerJavaRuntime.PROPERTY);
        System.clearProperty(WorkerClasspath.OVERRIDE_PROPERTY);
    }

    @Test
    @Order(1)
    @Timeout(90)
    void packagedWorkerProcessesMf4WithBundledRuntime() throws Exception {
        Path image = requireImage();
        bindPackagedRuntime(image);

        String classpath = WorkerClasspath.resolve();
        String lower = classpath.replace('\\', '/').toLowerCase();
        assertFalse(lower.contains("/.m2/"), classpath);
        assertFalse(lower.contains("target/classes"), classpath);
        assertTrue(lower.contains("stellar-parser-mf4"), classpath);
        assertTrue(lower.contains("stellar-processing-core"), classpath);
        assertTrue(lower.contains("sqlite-jdbc"), classpath);
        assertTrue(lower.contains("stellar-dbc-core"), classpath);
        assertTrue(lower.contains("stellar-analysis-core"), classpath);

        long parentPid = ProcessHandle.current().pid();
        Path analysis = temp.resolve("analysis");
        Files.createDirectories(analysis);
        AnalysisStorageRoots.setUserSelectedRoot(analysis);
        Path db = temp.resolve("project/.stellar/stellar.db");
        Files.createDirectories(db.getParent());
        String projectId = UUID.randomUUID().toString();
        Path measurement = writeMf4(temp.resolve("packaged.mf4"), 16, 0x123);
        Path definition = writeDbc(temp.resolve("packaged.dbc"));
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata(projectId, "Packaged", Instant.now(), Instant.now(), "DECODE", null));
        }
        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "plan-pkg",
                Instant.now(),
                List.of(new ProcessingJob(
                        ProcessingJobKey.of("src-1", 0),
                        MeasurementSourceFormat.MF4,
                        MappingLifecycle.CONFIRMED,
                        "def-1",
                        "sample.dbc",
                        1L, 1L, 0L, 0L, 1.0, 0L, 1.0,
                        null, true, List.of())),
                ProcessingPlanReadiness.READY,
                List.of());
        ProcessingTaskDescriptor task = ProcessingTaskDescriptor.plan(
                "task-pkg",
                projectId,
                "run-pkg",
                db.toAbsolutePath().toString(),
                analysis.toAbsolutePath().toString(),
                ProcessingPlanDtoMapper.toJson(plan),
                Map.of("src-1", measurement.toAbsolutePath().toString()),
                Map.of("def-1", definition.toAbsolutePath().toString()),
                true);

        try (ProcessIsolatedLocalWorker worker = ProcessIsolatedLocalWorker.create()) {
            assertNotEquals(parentPid, worker.workerPid(), "Desktop PID must differ from worker PID");
            assertTrue(worker.ping());
            assertTrue(worker.health().alive());
            assertTrue(WorkerJavaRuntime.isBundledRuntime(worker.javaExecutable(), image),
                    "java=" + worker.javaExecutable() + " image=" + image);
            WorkerMessage done = worker.submitTask(task).get(60, TimeUnit.SECONDS);
            assertEquals(WorkerProtocol.COMPLETED, done.type(), done.message());
            worker.shutdown();
            assertFalse(ProcessHandle.of(worker.workerPid()).map(ProcessHandle::isAlive).orElse(false),
                    "worker must exit on normal shutdown");
        }
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertTrue(store.findValidArtifact(projectId, "src-1", 0, "def-1").isPresent());
        }
    }

    @Test
    @Order(2)
    @Timeout(90)
    void packagedWorkerCrashResumesFromCheckpoint() throws Exception {
        Path image = requireImage();
        bindPackagedRuntime(image);
        Path analysis = temp.resolve("crash-analysis");
        Files.createDirectories(analysis);
        AnalysisStorageRoots.setUserSelectedRoot(analysis);
        Path db = temp.resolve("crash-project/.stellar/stellar.db");
        Files.createDirectories(db.getParent());
        String projectId = UUID.randomUUID().toString();
        Path measurement = writeMf4(temp.resolve("crash.mf4"), 24, 0x123);
        Path definition = writeDbc(temp.resolve("crash.dbc"));
        Path baselineMf4 = writeMf4(temp.resolve("baseline.mf4"), 24, 0x123);
        Path baselineDb = temp.resolve("baseline-project/.stellar/stellar.db");
        Files.createDirectories(baselineDb.getParent());
        String baselineProjectId = UUID.randomUUID().toString();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(baselineDb)) {
            store.upsertProject(new ProjectMetadata(baselineProjectId, "Baseline", Instant.now(), Instant.now(), "DECODE", null));
        }
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata(projectId, "Crash", Instant.now(), Instant.now(), "DECODE", null));
        }
        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "plan-crash",
                Instant.now(),
                List.of(new ProcessingJob(
                        ProcessingJobKey.of("src-1", 0),
                        MeasurementSourceFormat.MF4,
                        MappingLifecycle.CONFIRMED,
                        "def-1",
                        "sample.dbc",
                        1L, 1L, 0L, 0L, 1.0, 0L, 1.0,
                        null, true, List.of())),
                ProcessingPlanReadiness.READY,
                List.of());
        ProcessingTaskDescriptor baselineTask = ProcessingTaskDescriptor.plan(
                "task-baseline",
                baselineProjectId,
                "run-baseline",
                baselineDb.toAbsolutePath().toString(),
                analysis.toAbsolutePath().toString(),
                ProcessingPlanDtoMapper.toJson(plan),
                Map.of("src-1", baselineMf4.toAbsolutePath().toString()),
                Map.of("def-1", definition.toAbsolutePath().toString()),
                true).withProcessingBatchSize(8);
        ProcessingTaskDescriptor crashTask = ProcessingTaskDescriptor.plan(
                "task-crash",
                projectId,
                "run-crash",
                db.toAbsolutePath().toString(),
                analysis.toAbsolutePath().toString(),
                ProcessingPlanDtoMapper.toJson(plan),
                Map.of("src-1", measurement.toAbsolutePath().toString()),
                Map.of("def-1", definition.toAbsolutePath().toString()),
                true).withProcessingBatchSize(8);
        ProcessingTaskDescriptor resumeTask = ProcessingTaskDescriptor.plan(
                "task-resume",
                projectId,
                "run-resume",
                db.toAbsolutePath().toString(),
                analysis.toAbsolutePath().toString(),
                ProcessingPlanDtoMapper.toJson(plan),
                Map.of("src-1", measurement.toAbsolutePath().toString()),
                Map.of("def-1", definition.toAbsolutePath().toString()),
                false).withProcessingBatchSize(8);

        String uninterrupted;
        try (ProcessIsolatedLocalWorker baseline = ProcessIsolatedLocalWorker.create()) {
            WorkerMessage done = baseline.submitTask(baselineTask).get(45, TimeUnit.SECONDS);
            assertEquals(WorkerProtocol.COMPLETED, done.type(), done.message());
            uninterrupted = fingerprint(baselineProjectId);
            baseline.shutdown();
        }

        long parentPid = ProcessHandle.current().pid();
        AtomicInteger checkpoints = new AtomicInteger();
        ProcessIsolatedLocalWorker worker = ProcessIsolatedLocalWorker.create();
        try {
            assertNotEquals(parentPid, worker.workerPid());
            try {
                worker.submitTask(crashTask, incoming -> {
                    if (WorkerProtocol.CHECKPOINT_COMMITTED.equals(incoming.type())
                            && checkpoints.incrementAndGet() == 1) {
                        worker.destroyForcibly();
                    }
                }).get(40, TimeUnit.SECONDS);
            } catch (Exception expected) {
                assertTrue(ProcessHandle.current().isAlive(), "desktop JVM must survive packaged worker kill");
            }
            assertTrue(checkpoints.get() >= 1, "CHECKPOINT_COMMITTED must be observed before kill");
        } finally {
            worker.shutdown();
        }
        Thread.sleep(400L);
        try (SqliteProjectMetadataStore store = openSqlite(db)) {
            assertTrue(store.findProcessingCheckpoint(ProcessingJobKey.of("src-1", 0).toString()).isPresent(),
                    "durable checkpoint must exist after packaged worker kill");
            new LocalWorkerSupervisor(store).markRunningJobsInterrupted(projectId, "packaged worker killed");
        }
        try (ProcessIsolatedLocalWorker restarted = ProcessIsolatedLocalWorker.create()) {
            assertNotEquals(parentPid, restarted.workerPid());
            WorkerMessage done = restarted.submitTask(resumeTask).get(45, TimeUnit.SECONDS);
            assertEquals(WorkerProtocol.COMPLETED, done.type(), done.message());
            restarted.shutdown();
        }
        assertEquals(uninterrupted, fingerprint(projectId),
                "packaged crash-resume samples must equal uninterrupted packaged processing");
    }

    @Test
    @Order(3)
    @Timeout(90)
    void packagedWorkerLaunchSurvivesPathWithSpaces() throws Exception {
        Path image = requireImage();
        Path spacesRoot = temp.resolve("Stellar AI RC Test").resolve("StellarAI");
        Files.createDirectories(spacesRoot.getParent());
        int link = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                spacesRoot.toAbsolutePath().toString(), image.toAbsolutePath().toString())
                .inheritIO()
                .start()
                .waitFor();
        assertEquals(0, link, "junction to spaced path must succeed");
        spacesJunction = spacesRoot;
        bindPackagedRuntime(spacesRoot);

        try (ProcessIsolatedLocalWorker worker = ProcessIsolatedLocalWorker.create()) {
            assertTrue(worker.ping());
            assertTrue(worker.javaExecutable().toString().contains("Stellar AI RC Test"));
            worker.shutdown();
            assertFalse(ProcessHandle.of(worker.workerPid()).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    private static Path requireImage() {
        Path image = PackagedAppImage.locate().orElse(null);
        assumeTrue(image != null, "jpackage APP-IMAGE is not present under release/StellarAI-<current product version>");
        return image;
    }

    private void unlinkSpacesJunction() {
        if (spacesJunction == null) {
            return;
        }
        Path junction = spacesJunction;
        spacesJunction = null;
        try {
            new ProcessBuilder("cmd.exe", "/c", "rmdir", junction.toAbsolutePath().toString())
                    .start()
                    .waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private static String fingerprint(String projectId) {
        Path root = ArtifactStorageRefs.jobRoot(projectId, "src-1", 0, "def-1");
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
            return store.losslessStore().totalSampleCount() + ":" + bySignal;
        }
    }

    private static SqliteProjectMetadataStore openSqlite(Path db) throws InterruptedException {
        IllegalStateException last = null;
        for (int i = 0; i < 40; i++) {
            try {
                return new SqliteProjectMetadataStore(db);
            } catch (IllegalStateException ex) {
                last = ex;
                Thread.sleep(150L);
            }
        }
        throw last;
    }

    private static void bindPackagedRuntime(Path image) {
        Path exe = PackagedAppImage.launcher(image);
        Path java = PackagedAppImage.javaExecutable(image);
        System.setProperty("jpackage.app-path", exe.toAbsolutePath().toString());
        System.setProperty(WorkerJavaRuntime.PROPERTY, java.toAbsolutePath().toString());
        int packaged = FirstPartyRuntimeModules.readPackagedSchemaVersion(PackagedAppImage.appDirectory(image));
        int current = SqliteProjectMetadataStore.currentSchemaVersion();
        assumeTrue(packaged == current,
                "Packaged APP-IMAGE metadata schema is v" + packaged
                        + " but this source is v" + current
                        + ". Rebuild with: powershell -NoProfile -ExecutionPolicy Bypass"
                        + " -File .\\BUILD_WINDOWS_TESTER_INSTALLER.ps1 -SkipMaven -AppImageOnly");
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
}
