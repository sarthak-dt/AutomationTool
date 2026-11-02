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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tiny generator smoke — runs in ordinary verify. Not a GB NFT. */
class GeneratedValidMf4WriterSmokeTest {

    @TempDir
    Path temp;

    @AfterEach
    void clear() {
        AnalysisStorageRoots.clear();
    }

    @Test
    void writesValidClassicCanMf4AndDecodes() throws Exception {
        Path analysis = temp.resolve("analysis");
        Files.createDirectories(analysis);
        AnalysisStorageRoots.setUserSelectedRoot(analysis);
        Path db = temp.resolve("p/.stellar/stellar.db");
        Files.createDirectories(db.getParent());
        String projectId = UUID.randomUUID().toString();
        Path mf4 = GeneratedValidMf4Writer.write(temp.resolve("tiny.mf4"), 64);
        Path dbc = GeneratedValidMf4Writer.writeDbc(temp.resolve("tiny.dbc"));
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata(projectId, "tiny", Instant.now(), Instant.now(), "DECODE", null));
        }
        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "tiny", Instant.now(),
                List.of(new ProcessingJob(
                        ProcessingJobKey.of("src-1", 0),
                        MeasurementSourceFormat.MF4,
                        MappingLifecycle.CONFIRMED,
                        "def-1", "tiny.dbc",
                        1L, 1L, 0L, 0L, 1.0, 0L, 1.0,
                        null, true, List.of())),
                ProcessingPlanReadiness.READY, List.of());
        ProcessingTaskDescriptor task = ProcessingTaskDescriptor.plan(
                "t", projectId, "r",
                db.toAbsolutePath().toString(),
                analysis.toAbsolutePath().toString(),
                ProcessingPlanDtoMapper.toJson(plan),
                Map.of("src-1", mf4.toAbsolutePath().toString()),
                Map.of("def-1", dbc.toAbsolutePath().toString()),
                true);
        LocalPlanWorkerEngine.Outcome out = new LocalPlanWorkerEngine().execute(task, null, null);
        assertEquals("SUCCESS", out.status(), out.toJson());
        assertTrue(Files.size(mf4) > 64L * GeneratedValidMf4Writer.RECORD_BYTES);
    }
}
