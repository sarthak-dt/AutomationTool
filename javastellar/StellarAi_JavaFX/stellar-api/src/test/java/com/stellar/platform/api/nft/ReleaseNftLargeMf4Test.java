package com.stellar.platform.api.nft;

import com.stellar.platform.api.support.ProcessingPlanDtoMapper;
import com.stellar.platform.api.worker.LocalPlanWorkerEngine;
import com.stellar.platform.api.worker.ProcessingTaskDescriptor;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.processing.align.AlignedSignalWorkspaceQuery;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.storage.ArtifactStorageRefs;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GENERATED VALID MF4 large-file matrix. Not executed by default {@code mvn verify}.
 * Run: {@code mvn -pl stellar-api -am test -Prelease-nft}
 */
@Tag("release-nft")
class ReleaseNftLargeMf4Test {

    @TempDir
    Path temp;

    @AfterEach
    void clear() {
        AnalysisStorageRoots.clear();
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.MINUTES)
    void progressiveGeneratedMf4Matrix() throws Exception {
        Path work = temp.resolve("nft");
        Files.createDirectories(work);
        Map<String, Object> host = ReleaseNftSupport.hostBaseline(work);
        System.out.println("PHASE9C_HOST " + host);

        long max = ReleaseNftSupport.maxInputBytes();
        long min = ReleaseNftSupport.minInputBytes();
        long[] targets = {
                50L * 1024 * 1024,
                500L * 1024 * 1024,
                1024L * 1024 * 1024,
                5L * 1024 * 1024 * 1024,
                10L * 1024 * 1024 * 1024
        };
        String[] labels = {"50MB", "500MB", "1GB", "5GB", "10GB"};
        long lastDurationMs = 0L;
        String lastPass = null;
        for (int i = 0; i < targets.length; i++) {
            long target = targets[i];
            String label = labels[i];
            long free = ReleaseNftSupport.usableDisk(work);
            if (target < min) {
                System.out.println("PHASE9C_LARGE " + label + " skipped-below stellar.nft.min.input.mb");
                continue;
            }
            if (target > max) {
                System.out.println("PHASE9C_LARGE " + label + " NOT_TESTED reason=above stellar.nft.max.input.mb");
                continue;
            }
            if (free < target * 4L) {
                System.out.println("PHASE9C_LARGE " + label + " NOT_TESTED reason=insufficient-disk free=" + free);
                continue;
            }
            if (i > 0 && lastDurationMs > 12L * 60_000L) {
                System.out.println("PHASE9C_LARGE " + label + " NOT_TESTED reason=previous-size-too-slow ms=" + lastDurationMs);
                continue;
            }
            MatrixRow row = runSize(work, label, target);
            lastDurationMs = row.durationMs;
            lastPass = label;
            System.out.println("PHASE9C_LARGE " + row);
            assertEquals("SUCCESS", row.outcome, row.notes);
            assertEquals(row.records, row.samples, "decoded samples must match generated records");
            assertTrue(row.peakHeap < 512L * 1024 * 1024,
                    "memory regression: combined peak must not grow with MF4 size; peak="
                            + row.peakHeap + " label=" + label);
        }
        if (lastPass != null) {
            System.out.println("PHASE9C_LARGE largestPass=" + lastPass);
        }
    }

    private MatrixRow runSize(Path work, String label, long targetBytes) throws Exception {
        long records = Math.max(1L, targetBytes / GeneratedValidMf4Writer.RECORD_BYTES);
        Path dir = work.resolve(label);
        Files.createDirectories(dir);
        Path analysis = dir.resolve("analysis");
        Files.createDirectories(analysis);
        AnalysisStorageRoots.setUserSelectedRoot(analysis);
        Path db = dir.resolve("project/.stellar/stellar.db");
        Files.createDirectories(db.getParent());
        String projectId = UUID.randomUUID().toString();
        Path mf4 = dir.resolve("gen.mf4");
        Path dbc = GeneratedValidMf4Writer.writeDbc(dir.resolve("gen.dbc"));
        long genStart = System.nanoTime();
        GeneratedValidMf4Writer.write(mf4, records);
        long genMs = (System.nanoTime() - genStart) / 1_000_000L;
        long inputSize = Files.size(mf4);
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata(projectId, label, Instant.now(), Instant.now(), "DECODE", null));
        }
        ProcessingTaskDescriptor task = task(projectId, db, analysis, mf4, dbc, true);
        long heap0 = ReleaseNftSupport.heapUsed();
        long t0 = System.nanoTime();
        LocalPlanWorkerEngine.Outcome out;
        long peak;
        try (ReleaseNftSupport.HeapSampler sampler = new ReleaseNftSupport.HeapSampler().start(8)) {
            out = new LocalPlanWorkerEngine().execute(task.withProcessingBatchSize(4096), null, null);
            peak = sampler.peakUsed();
        }
        long durationMs = (System.nanoTime() - t0) / 1_000_000L;
        long heap1 = ReleaseNftSupport.heapUsed();
        Path artifact = ArtifactStorageRefs.jobRoot(projectId, "src-1", 0, "def-1");
        long artifactBytes = directorySize(artifact);
        long samples = 0L;
        long firstTs = -1L;
        long lastTs = -1L;
        long pageMs = -1L;
        long seekMs = -1L;
        long reopenMs = -1L;
        int checkpoints = 0;
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            checkpoints = store.listProcessingBatches(ProcessingJobKey.of("src-1", 0).toString()).size();
        }
        if (Files.isDirectory(artifact)) {
            long r0 = System.nanoTime();
            try (GraphSignalSessionStore session = GraphSignalSessionStore.openExisting(artifact)) {
                reopenMs = (System.nanoTime() - r0) / 1_000_000L;
                samples = session.losslessStore().totalSampleCount();
                List<SignalCatalogKey> keys = new ArrayList<>();
                session.snapshot().entries().forEach(e -> keys.add(e.key()));
                if (!keys.isEmpty()) {
                    long p0 = System.nanoTime();
                    AlignedSignalWorkspaceQuery.Result page = AlignedSignalWorkspaceQuery.query(
                            session, AlignedSignalWorkspaceQuery.Request.first(keys, 500));
                    pageMs = (System.nanoTime() - p0) / 1_000_000L;
                    assertTrue(page.samplesRead() <= 500 * Math.max(1, keys.size()) * 2);
                    if (!page.rows().isEmpty()) {
                        firstTs = page.rows().getFirst().timestampNanos();
                    }
                    long s0 = System.nanoTime();
                    AlignedSignalWorkspaceQuery.Result mid = AlignedSignalWorkspaceQuery.query(
                            session, AlignedSignalWorkspaceQuery.Request.atIndex(keys, 500, samples / 2));
                    seekMs = (System.nanoTime() - s0) / 1_000_000L;
                    AlignedSignalWorkspaceQuery.Result tail = AlignedSignalWorkspaceQuery.query(
                            session, AlignedSignalWorkspaceQuery.Request.atIndex(keys, 1, Math.max(0L, samples - 1)));
                    if (!tail.rows().isEmpty()) {
                        lastTs = tail.rows().getLast().timestampNanos();
                    }
                    if (firstTs < 0 && !mid.rows().isEmpty()) {
                        firstTs = page.rows().isEmpty() ? mid.rows().getFirst().timestampNanos() : firstTs;
                    }
                }
            }
        }
        AnalysisStorageRoots.clear();
        long heapPostClose = ReleaseNftSupport.heapUsed();
        double mbps = durationMs <= 0 ? 0 : (inputSize / 1024.0 / 1024.0) / (durationMs / 1000.0);
        String notes = "dataset=GENERATED_VALID_MF4 genMs=" + genMs
                + " firstTs=" + firstTs + " lastTs=" + lastTs
                + " expectedLast=" + ((records - 1) * GeneratedValidMf4Writer.TICK_NANOS)
                + " desktopBaseline=" + heap0
                + " desktopPeak=" + peak
                + " desktopPostOp=" + heap1
                + " desktopPostClose=" + heapPostClose
                + " workerBaseline=COMBINED_JVM_NOT_SEPARATE"
                + " workerPeak=COMBINED_JVM_NOT_SEPARATE"
                + " workerPost=COMBINED_JVM_NOT_SEPARATE"
                + " pageMs=" + pageMs + " seekMs=" + seekMs + " reopenMs=" + reopenMs
                + " checkpoints=" + checkpoints
                + " isolation=IN_PROCESS_SAME_JVM";
        if (records >= 2_000L && "SUCCESS".equals(out.status())) {
            notes += " " + crashResume(dir, records, mf4, dbc);
        }
        return new MatrixRow(label, inputSize, records, durationMs, peak, heap1, artifactBytes,
                mbps, out.status(), notes, samples);
    }

    private String crashResume(Path dir, long records, Path mf4, Path dbc) throws Exception {
        Path analysis = dir.resolve("crash-analysis");
        Files.createDirectories(analysis);
        AnalysisStorageRoots.setUserSelectedRoot(analysis);
        Path db = dir.resolve("crash-project/.stellar/stellar.db");
        Files.createDirectories(db.getParent());
        String projectId = UUID.randomUUID().toString();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata(projectId, "crash", Instant.now(), Instant.now(), "DECODE", null));
        }
        ProcessingTaskDescriptor crash = task(projectId, db, analysis, mf4, dbc, true)
                .withProcessingBatchSize(4096)
                .withInterruptAfterCheckpoints(2);
        new LocalPlanWorkerEngine().execute(crash, null, null);
        long t0 = System.nanoTime();
        LocalPlanWorkerEngine.Outcome resumed = new LocalPlanWorkerEngine().execute(
                task(projectId, db, analysis, mf4, dbc, false).withProcessingBatchSize(4096), null, null);
        long resumeMs = (System.nanoTime() - t0) / 1_000_000L;
        Path artifact = ArtifactStorageRefs.jobRoot(projectId, "src-1", 0, "def-1");
        long samples = 0L;
        if (Files.isDirectory(artifact)) {
            try (GraphSignalSessionStore session = GraphSignalSessionStore.openExisting(artifact)) {
                samples = session.losslessStore().totalSampleCount();
            }
        }
        return "crashResume=" + resumed.status() + " resumeMs=" + resumeMs + " resumeSamples=" + samples
                + " expected=" + records;
    }

    private static ProcessingTaskDescriptor task(
            String projectId, Path db, Path analysis, Path mf4, Path dbc, boolean force
    ) {
        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "nft", Instant.now(),
                List.of(new ProcessingJob(
                        ProcessingJobKey.of("src-1", 0),
                        MeasurementSourceFormat.MF4,
                        MappingLifecycle.CONFIRMED,
                        "def-1", "gen.dbc",
                        1L, 1L, 0L, 0L, 1.0, 0L, 1.0,
                        null, true, List.of())),
                ProcessingPlanReadiness.READY, List.of());
        return ProcessingTaskDescriptor.plan(
                "task-" + projectId.substring(0, 8),
                projectId,
                "run-" + projectId.substring(0, 8),
                db.toAbsolutePath().toString(),
                analysis.toAbsolutePath().toString(),
                ProcessingPlanDtoMapper.toJson(plan),
                Map.of("src-1", mf4.toAbsolutePath().toString()),
                Map.of("def-1", dbc.toAbsolutePath().toString()),
                force);
    }

    private static long directorySize(Path root) throws Exception {
        if (!Files.isDirectory(root)) {
            return 0L;
        }
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (Exception e) {
                    return 0L;
                }
            }).sum();
        }
    }

    private record MatrixRow(
            String label,
            long inputSize,
            long records,
            long durationMs,
            long peakHeap,
            long endHeap,
            long artifactBytes,
            double mbps,
            String outcome,
            String notes,
            long samples
    ) {
        @Override
        public String toString() {
            return label + " input=" + inputSize + " records=" + records + " durationMs=" + durationMs
                    + " mbps=" + String.format(java.util.Locale.ROOT, "%.2f", mbps)
                    + " peakHeap=" + peakHeap + " artifact=" + artifactBytes
                    + " outcome=" + outcome + " " + notes;
        }
    }
}
