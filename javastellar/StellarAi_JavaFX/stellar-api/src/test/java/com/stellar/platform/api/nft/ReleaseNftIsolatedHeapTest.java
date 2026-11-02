package com.stellar.platform.api.nft;

import com.stellar.platform.api.support.ProcessingPlanDtoMapper;
import com.stellar.platform.api.worker.ProcessIsolatedLocalWorker;
import com.stellar.platform.api.worker.ProcessingTaskDescriptor;
import com.stellar.platform.api.worker.WorkerHealthSnapshot;
import com.stellar.platform.api.worker.WorkerMessage;
import com.stellar.platform.api.worker.WorkerProtocol;
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

/**
 * Isolated Desktop vs Worker heap. Not a GB matrix — those remain COMBINED_JVM
 * measurements in {@code ReleaseNftLargeMf4Test}.
 */
@Tag("release-nft")
class ReleaseNftIsolatedHeapTest {

    @TempDir
    Path temp;

    @AfterEach
    void clear() {
        AnalysisStorageRoots.clear();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void isolatedWorkerHeapStaysBoundedOnGeneratedMf4() throws Exception {
        long desktopBaseline = ReleaseNftSupport.heapUsed();
        long target = Math.min(50L * 1024 * 1024, ReleaseNftSupport.maxInputBytes());
        if (target < 1024L * 1024) {
            System.out.println("PHASE9C_MEM_ISO NOT_TESTED reason=max-input-below-1MB");
            return;
        }
        Path analysis = temp.resolve("analysis");
        Files.createDirectories(analysis);
        AnalysisStorageRoots.setUserSelectedRoot(analysis);
        Path db = temp.resolve("p/.stellar/stellar.db");
        Files.createDirectories(db.getParent());
        String projectId = UUID.randomUUID().toString();
        long records = Math.max(1L, target / GeneratedValidMf4Writer.RECORD_BYTES);
        Path mf4 = GeneratedValidMf4Writer.write(temp.resolve("iso.mf4"), records);
        Path dbc = GeneratedValidMf4Writer.writeDbc(temp.resolve("iso.dbc"));
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata(projectId, "iso-heap", Instant.now(), Instant.now(), "DECODE", null));
        }
        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "iso", Instant.now(),
                List.of(new ProcessingJob(
                        ProcessingJobKey.of("src-1", 0),
                        MeasurementSourceFormat.MF4,
                        MappingLifecycle.CONFIRMED,
                        "def-1", "iso.dbc",
                        1L, 1L, 0L, 0L, 1.0, 0L, 1.0,
                        null, true, List.of())),
                ProcessingPlanReadiness.READY, List.of());
        ProcessingTaskDescriptor task = ProcessingTaskDescriptor.plan(
                "iso-heap", projectId, "run-iso",
                db.toAbsolutePath().toString(),
                analysis.toAbsolutePath().toString(),
                ProcessingPlanDtoMapper.toJson(plan),
                Map.of("src-1", mf4.toAbsolutePath().toString()),
                Map.of("def-1", dbc.toAbsolutePath().toString()),
                true).withProcessingBatchSize(4096);

        long desktopPeak = desktopBaseline;
        long desktopPostOp = desktopBaseline;
        long workerBaseline = 0L;
        long workerPeak = -1L;
        long workerPost = 0L;
        long workerMax = 0L;
        long workerPid = -1L;
        try (ProcessIsolatedLocalWorker worker = ProcessIsolatedLocalWorker.create()) {
            workerPid = worker.workerPid();
            assertNotEquals(ProcessHandle.current().pid(), workerPid);
            WorkerHealthSnapshot before = worker.health();
            workerBaseline = before.heapUsedBytes();
            workerMax = before.heapMaxBytes();
            Long jstat0 = ReleaseNftSupport.externalHeapUsedBytes(workerPid);
            try (ReleaseNftSupport.HeapSampler workerSampler = new ReleaseNftSupport.HeapSampler()
                    .startExternal(workerPid, 8)) {
                if (jstat0 != null) {
                    workerPeak = jstat0;
                }
                WorkerMessage done = worker.submitTask(task).get(18, TimeUnit.MINUTES);
                assertEquals(WorkerProtocol.COMPLETED, done.type(), done.message());
                long sampled = workerSampler.peakUsedOnly();
                if (sampled > 0L) {
                    workerPeak = Math.max(workerPeak, sampled);
                }
            }
            WorkerHealthSnapshot after = worker.health();
            workerPost = after.heapUsedBytes();
            if (workerPeak < 0L) {
                workerPeak = Math.max(workerBaseline, workerPost);
            }
            desktopPostOp = ReleaseNftSupport.heapUsed();
            desktopPeak = Math.max(desktopPeak, desktopPostOp);
        }
        AnalysisStorageRoots.clear();
        long desktopPostClose = ReleaseNftSupport.heapUsed();
        System.out.println("PHASE9C_MEM_ISO dataset=GENERATED_VALID_MF4 inputBytes=" + Files.size(mf4)
                + " records=" + records
                + " desktopPid=" + ProcessHandle.current().pid()
                + " workerPid=" + workerPid
                + " desktopBaseline=" + desktopBaseline
                + " desktopPeak=" + desktopPeak
                + " desktopPostOp=" + desktopPostOp
                + " desktopPostClose=" + desktopPostClose
                + " workerBaseline=" + workerBaseline
                + " workerPeak=" + workerPeak
                + " workerPost=" + workerPost
                + " workerHeapMax=" + workerMax
                + " isolation=PROCESS_ISOLATED"
                + " workerPeakSource=jstat-gc-or-health");
        assertTrue(desktopPeak < 512L * 1024 * 1024,
                "desktop must not accumulate the MF4 payload; peak=" + desktopPeak);
        assertTrue(workerPeak < 768L * 1024 * 1024,
                "worker peak heap must stay bounded vs 50 MB input; peak=" + workerPeak);
        assertTrue(workerPost < 768L * 1024 * 1024,
                "worker post-process heap must stay bounded vs 50 MB input; post=" + workerPost);
    }
}
