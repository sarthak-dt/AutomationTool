package com.stellar.platform.api.worker;

import com.stellar.platform.api.support.ProcessingPlanDtoMapper;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.ProcessingJobMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production isolated-worker path for BLF. Proves ServiceLoader discovery in the child JVM
 * and that BLF jobs terminalize without changing worker drain behaviour.
 */
class IsolatedBlfProcessingTest {

    @TempDir
    Path temp;

    @AfterEach
    void clearStorage() {
        AnalysisStorageRoots.clear();
    }

    @Test
    @Timeout(value = 40, unit = TimeUnit.SECONDS)
    void oneBlfJobSucceedsOnIsolatedWorker() throws Exception {
        runJobs(1);
    }

    @Test
    @Timeout(value = 40, unit = TimeUnit.SECONDS)
    void twoBlfJobsRemainIndependent() throws Exception {
        runJobs(2);
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void fiveBlfJobsEachTerminalizeOnce() throws Exception {
        runJobs(5);
    }

    private void runJobs(int count) throws Exception {
        Path analysis = temp.resolve("analysis-" + count);
        Files.createDirectories(analysis);
        AnalysisStorageRoots.setUserSelectedRoot(analysis);
        Path db = temp.resolve("project-" + count).resolve(".stellar").resolve("stellar.db");
        Files.createDirectories(db.getParent());
        String projectId = UUID.randomUUID().toString();
        Path definition = writeDbc(temp.resolve("inputs-" + count).resolve("sample.dbc"));
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata(projectId, "blf-" + count, Instant.now(), Instant.now(), "DECODE", null));
        }
        List<ProcessingJob> jobs = new ArrayList<>();
        Map<String, String> sources = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String sourceId = "blf-" + i;
            Path measurement = writeBlf(temp.resolve("inputs-" + count).resolve("run-" + i + ".blf"), 0x123, (byte) (i + 1));
            sources.put(sourceId, measurement.toAbsolutePath().toString());
            jobs.add(new ProcessingJob(
                    ProcessingJobKey.of(sourceId, 1),
                    MeasurementSourceFormat.BLF,
                    MappingLifecycle.CONFIRMED,
                    "def-1",
                    "sample.dbc",
                    1L, 1L, 0L, 1L, 1.0, 1L, 1.0,
                    null, true, List.of()));
        }
        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "plan-blf-" + count, Instant.now(), jobs, ProcessingPlanReadiness.READY, List.of());
        try (ProcessIsolatedLocalWorker worker = ProcessIsolatedLocalWorker.create()) {
            ProcessingTaskDescriptor task = ProcessingTaskDescriptor.plan(
                    "task-blf-" + count,
                    projectId,
                    "run-blf-" + count,
                    db.toAbsolutePath().toString(),
                    analysis.toAbsolutePath().toString(),
                    ProcessingPlanDtoMapper.toJson(plan),
                    sources,
                    Map.of("def-1", definition.toAbsolutePath().toString()),
                    true);
            WorkerMessage done = worker.submitTask(task).get(35, TimeUnit.SECONDS);
            assertEquals(WorkerProtocol.COMPLETED, done.type(),
                    "BLF isolated PLAN must reach a worker terminal: type=" + done.type()
                            + " classification=" + done.classification()
                            + " message=" + done.message());
        }
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            List<ProcessingJobMetadata> persisted = store.listProcessingJobs(projectId);
            assertEquals(count, persisted.size());
            for (ProcessingJobMetadata job : persisted) {
                assertTrue(job.status().isSuccessful(), job.jobId() + " status=" + job.status());
                assertTrue(store.findValidArtifact(projectId, job.sourceId(), 1, "def-1").isPresent(),
                        "missing artifact for " + job.sourceId());
            }
        }
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

    private static Path writeBlf(Path target, int canId, byte firstByte) throws Exception {
        byte[] payload = new byte[]{firstByte, 2, 3, 4, 5, 6, 7, 8};
        ByteBuffer can = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        can.putShort((short) 1);
        can.put((byte) 0);
        can.put((byte) 8);
        can.putInt(canId);
        can.put(payload);
        byte[] inner = lobj(1, 1, 0x00000002, 1_000_000L, can.array());
        byte[] stored = zlib(inner);
        ByteBuffer container = ByteBuffer.allocate(16 + stored.length).order(ByteOrder.LITTLE_ENDIAN);
        container.putShort((short) 2);
        container.putShort((short) 0);
        container.putInt(0);
        container.putInt(inner.length);
        container.putInt(0);
        container.put(stored);
        byte[] body = lobj(10, 1, 0, 0, container.array());
        ByteBuffer header = ByteBuffer.allocate(144).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'L', 'O', 'G', 'G'});
        header.putInt(144);
        header.putInt(0);
        header.put(new byte[]{5, 0, 0, 0, 1, 3, 0, 0});
        header.putLong(144 + body.length);
        header.putLong(144 + body.length);
        header.putInt(1);
        header.putInt(0);
        header.putShort((short) 2026);
        header.putShort((short) 1);
        header.putShort((short) 4);
        header.putShort((short) 1);
        header.putShort((short) 12);
        header.putShort((short) 0);
        header.putShort((short) 0);
        header.putShort((short) 0);
        byte[] all = new byte[144 + body.length];
        System.arraycopy(header.array(), 0, all, 0, 144);
        System.arraycopy(body, 0, all, 144, body.length);
        Files.createDirectories(target.getParent());
        Files.write(target, all);
        return target;
    }

    private static byte[] lobj(int type, int headerVersion, int flags, long timestamp, byte[] payload) {
        int extra = 16;
        int headerSize = 16 + extra;
        int objectSize = headerSize + payload.length;
        int aligned = (objectSize + 3) & ~3;
        ByteBuffer buf = ByteBuffer.allocate(aligned).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(new byte[]{'L', 'O', 'B', 'J'});
        buf.putShort((short) headerSize);
        buf.putShort((short) headerVersion);
        buf.putInt(objectSize);
        buf.putInt(type);
        buf.putInt(flags);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putLong(timestamp);
        buf.put(payload);
        return buf.array();
    }

    private static byte[] zlib(byte[] input) {
        Deflater deflater = new Deflater();
        deflater.setInput(input);
        deflater.finish();
        byte[] chunk = new byte[Math.max(64, input.length + 64)];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (!deflater.finished()) {
            int n = deflater.deflate(chunk);
            out.write(chunk, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }
}
