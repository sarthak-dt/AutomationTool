package com.stellar.platform.api.nft;

import com.stellar.platform.api.support.ProcessingPlanDtoMapper;
import com.stellar.platform.api.worker.LocalPlanWorkerEngine;
import com.stellar.platform.api.worker.ProcessingTaskDescriptor;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("release-nft")
class ReleaseNftManyFileTest {

    @TempDir
    Path temp;

    @AfterEach
    void clear() {
        AnalysisStorageRoots.clear();
    }

    @Test
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    void manyTinyGeneratedMf4Sources() throws Exception {
        int[] counts = {1, 5, 20, 100, 500};
        Path dbc = GeneratedValidMf4Writer.writeDbc(temp.resolve("many.dbc"));
        for (int n : counts) {
            long t0 = System.nanoTime();
            ManyRow row = runProject(n, dbc);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            System.out.println("PHASE9C_MANY n=" + n + " processMs=" + row.processMs
                    + " reopenMs=" + row.reopenMs + " incrementalMs=" + row.incrementalMs
                    + " files=" + n + " eachRecords=8 totalBytes=" + row.totalBytes
                    + " outcome=" + row.outcome + " wallMs=" + ms
                    + " dataset=GENERATED_VALID_MF4_TINY");
            assertEquals("SUCCESS", row.outcome);
        }
    }

    private ManyRow runProject(int n, Path dbc) throws Exception {
        Path root = temp.resolve("n" + n);
        Path analysis = root.resolve("analysis");
        Files.createDirectories(analysis);
        AnalysisStorageRoots.setUserSelectedRoot(analysis);
        Path db = root.resolve("project/.stellar/stellar.db");
        Files.createDirectories(db.getParent());
        String projectId = UUID.randomUUID().toString();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata(projectId, "many-" + n, Instant.now(), Instant.now(), "DECODE", null));
        }
        Map<String, String> sources = new LinkedHashMap<>();
        List<ProcessingJob> jobs = new ArrayList<>();
        long totalBytes = 0L;
        for (int i = 0; i < n; i++) {
            String sid = "src-" + i;
            Path mf4 = GeneratedValidMf4Writer.write(root.resolve(sid + ".mf4"), 8);
            totalBytes += Files.size(mf4);
            sources.put(sid, mf4.toAbsolutePath().toString());
            jobs.add(job(sid));
        }
        ProcessingTaskDescriptor first = planTask(projectId, db, analysis, jobs, sources, dbc, true);
        long p0 = System.nanoTime();
        LocalPlanWorkerEngine.Outcome out = new LocalPlanWorkerEngine().execute(first, null, null);
        long processMs = (System.nanoTime() - p0) / 1_000_000L;
        long r0 = System.nanoTime();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertTrue(store.loadProject(projectId).isPresent());
        }
        long reopenMs = (System.nanoTime() - r0) / 1_000_000L;

        String extraId = "src-extra";
        Path extra = GeneratedValidMf4Writer.write(root.resolve(extraId + ".mf4"), 8);
        sources.put(extraId, extra.toAbsolutePath().toString());
        jobs.add(job(extraId));
        long i0 = System.nanoTime();
        LocalPlanWorkerEngine.Outcome incr = new LocalPlanWorkerEngine().execute(
                planTask(projectId, db, analysis, jobs, sources, dbc, false), null, null);
        long incrementalMs = (System.nanoTime() - i0) / 1_000_000L;
        assertEquals("SUCCESS", incr.status(), incr.toJson());
        return new ManyRow(out.status(), processMs, reopenMs, incrementalMs, totalBytes);
    }

    private static ProcessingJob job(String sourceId) {
        return new ProcessingJob(
                ProcessingJobKey.of(sourceId, 0),
                MeasurementSourceFormat.MF4,
                MappingLifecycle.CONFIRMED,
                "def-1", "many.dbc",
                1L, 1L, 0L, 0L, 1.0, 0L, 1.0,
                null, true, List.of());
    }

    private static ProcessingTaskDescriptor planTask(
            String projectId, Path db, Path analysis,
            List<ProcessingJob> jobs, Map<String, String> sources, Path dbc, boolean force
    ) {
        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "many", Instant.now(), jobs, ProcessingPlanReadiness.READY, List.of());
        return ProcessingTaskDescriptor.plan(
                "many-" + jobs.size(),
                projectId,
                "run-" + jobs.size(),
                db.toAbsolutePath().toString(),
                analysis.toAbsolutePath().toString(),
                ProcessingPlanDtoMapper.toJson(plan),
                sources,
                Map.of("def-1", dbc.toAbsolutePath().toString()),
                force);
    }

    private record ManyRow(String outcome, long processMs, long reopenMs, long incrementalMs, long totalBytes) {
    }
}
