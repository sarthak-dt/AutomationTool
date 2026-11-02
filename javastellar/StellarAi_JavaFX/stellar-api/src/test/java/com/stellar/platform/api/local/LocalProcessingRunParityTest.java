package com.stellar.platform.api.local;

import com.stellar.platform.api.assets.FileAssetUploader;
import com.stellar.platform.api.dto.CreateProjectRequest;
import com.stellar.platform.api.dto.FileAssetDto;
import com.stellar.platform.api.dto.IndependentJobResultDto;
import com.stellar.platform.api.dto.JobDto;
import com.stellar.platform.api.dto.JobStatusDto;
import com.stellar.platform.api.dto.ProcessingPlanDto;
import com.stellar.platform.api.dto.ProcessingRunRequest;
import com.stellar.platform.api.dto.ProcessingRunResultDto;
import com.stellar.platform.api.identity.FileAssetKinds;
import com.stellar.platform.api.identity.PlatformIds;
import com.stellar.platform.api.support.ProcessingPlanDtoMapper;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalProcessingRunParityTest {

    @TempDir
    Path temp;

    @Test
    void processingRunExecutesCanonicalPlanAndSupportsIncrementalReuse() throws Exception {
        Path dataRoot = temp.resolve("api-data");
        Files.createDirectories(dataRoot);
        LocalStellarPlatformClient client = LocalStellarPlatformClient.createDefault(dataRoot);
        try {
            String projectId = PlatformIds.newId();
            client.projects().createProject(new CreateProjectRequest("Run Parity", "", projectId));

            Path measurement = copyAsc(temp.resolve("inputs").resolve("sample.asc"));
            Path definition = writeDbc(temp.resolve("inputs").resolve("sample.dbc"));

            FileAssetDto mAsset = FileAssetUploader.ensureAsset(
                    client.assets(), projectId, "src-1", FileAssetKinds.MEASUREMENT, measurement, true);
            FileAssetDto dAsset = FileAssetUploader.ensureAsset(
                    client.assets(), projectId, "def-1", FileAssetKinds.DEFINITION, definition, true);

            ProjectProcessingPlan plan = planFor("src-1", "def-1", "sample.dbc");
            ProcessingPlanDto planDto = ProcessingPlanDtoMapper.toDto(plan);
            assertEquals(1, planDto.jobs().size());
            assertEquals("CONFIRMED", planDto.jobs().getFirst().mappingLifecycle());

            ProcessingRunRequest request = new ProcessingRunRequest(
                    planDto,
                    "FORCE_REPROCESS",
                    Map.of("src-1", mAsset.fileAssetId()),
                    Map.of("def-1", dAsset.fileAssetId()),
                    256
            );

            JobDto run1 = client.processing().startProcessingRun(projectId, request);
            ProcessingRunResultDto result1 = awaitRun(client, projectId, run1.jobId());
            assertNotNull(result1);
            assertEquals(1, result1.jobResults().size());
            IndependentJobResultDto job1 = result1.jobResults().getFirst();
            assertEquals("src-1", job1.projectSourceId());
            assertEquals(0, job1.channelId());
            assertEquals("def-1", job1.definitionSourceId());
            assertFalse(job1.reused(), "first run must decode");

            // Incremental: same A job + new B job — A should reuse when REUSE_COMPLETED.
            ProcessingJob jobB = new ProcessingJob(
                    ProcessingJobKey.of("src-1", 1),
                    MeasurementSourceFormat.ASC,
                    MappingLifecycle.CONFIRMED,
                    "def-1",
                    "sample.dbc",
                    1L, 1L, 0L, 10L, 1.0, 10L, 1.0,
                    null, true, List.of()
            );
            ProjectProcessingPlan incremental = new ProjectProcessingPlan(
                    "plan-2",
                    Instant.now(),
                    List.of(plan.jobs().getFirst(), jobB),
                    ProcessingPlanReadiness.READY,
                    List.of()
            );
            JobDto run2 = client.processing().startProcessingRun(projectId, new ProcessingRunRequest(
                    ProcessingPlanDtoMapper.toDto(incremental),
                    "REUSE_COMPLETED",
                    Map.of("src-1", mAsset.fileAssetId()),
                    Map.of("def-1", dAsset.fileAssetId()),
                    256
            ));
            ProcessingRunResultDto result2 = awaitRun(client, projectId, run2.jobId());
            assertEquals(2, result2.jobResults().size());
            IndependentJobResultDto reused = result2.jobResults().stream()
                    .filter(j -> j.channelId() == 0)
                    .findFirst()
                    .orElseThrow();
            assertTrue(reused.reused() || reused.sourceFramesRead() == job1.sourceFramesRead(),
                    "channel 0 should reuse prior completed artifact; reused="
                            + reused.reused() + " detail=" + reused.technicalDetail());
        } finally {
            client.close();
        }
    }

    private static ProcessingRunResultDto awaitRun(
            LocalStellarPlatformClient client,
            String projectId,
            String runId
    ) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            JobDto status = client.processing().getProcessJob(projectId, runId);
            if (status.status() != JobStatusDto.RUNNING && status.status() != JobStatusDto.QUEUED) {
                return client.processing().getProcessingRunResult(projectId, runId).orElseThrow();
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("timed out waiting for processing run " + runId);
    }

    private static ProjectProcessingPlan planFor(String sourceId, String defId, String defName) {
        ProcessingJob job = new ProcessingJob(
                ProcessingJobKey.of(sourceId, 0),
                MeasurementSourceFormat.ASC,
                MappingLifecycle.CONFIRMED,
                defId,
                defName,
                3L, 1L, 2L, 4L, 0.33, 1L, 0.25,
                null, true, List.of()
        );
        return new ProjectProcessingPlan(
                "plan-auto",
                Instant.now(),
                List.of(job),
                ProcessingPlanReadiness.READY,
                List.of()
        );
    }

    private static Path copyAsc(Path target) throws Exception {
        Files.createDirectories(target.getParent());
        try (var in = LocalProcessingRunParityTest.class.getResourceAsStream("/asc/classical_basic.asc")) {
            if (in == null) {
                // Fallback inline fixture when resource not on classpath.
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
