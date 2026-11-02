package com.stellar.platform.api.local;

import com.stellar.platform.api.assets.FileAssetStore;
import com.stellar.platform.api.assets.FileAssetUploader;
import com.stellar.platform.api.dto.BeginUploadRequest;
import com.stellar.platform.api.dto.CreateProjectRequest;
import com.stellar.platform.api.dto.FileAssetDto;
import com.stellar.platform.api.dto.IndependentJobResultDto;
import com.stellar.platform.api.dto.JobDto;
import com.stellar.platform.api.dto.JobStatusDto;
import com.stellar.platform.api.dto.ProcessingRunRequest;
import com.stellar.platform.api.dto.ProcessingRunResultDto;
import com.stellar.platform.api.dto.UploadSessionDto;
import com.stellar.platform.api.identity.FileAssetKinds;
import com.stellar.platform.api.identity.FileAssetStatuses;
import com.stellar.platform.api.identity.PlatformIds;
import com.stellar.platform.api.support.DurableJobArtifactService;
import com.stellar.platform.api.support.MetadataStoreFactory;
import com.stellar.platform.api.support.PlatformMetrics;
import com.stellar.platform.api.support.ProcessingLog;
import com.stellar.platform.api.support.ProcessingPlanDtoMapper;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.processing.graph.LosslessGraphSignalStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryObservabilitySecurityTest {

    @TempDir
    Path temp;

    @BeforeEach
    void resetMetrics() {
        PlatformMetrics.get().resetForTests();
    }

    @Test
    void applicationRestartReusesCompletedJobsAndContinuesRemaining() throws Exception {
        Path dataRoot = temp.resolve("data");
        Files.createDirectories(dataRoot);

        String projectId = PlatformIds.newId();
        Path measurement = writeAsc(temp.resolve("in").resolve("sample.asc"));
        Path definition = writeDbc(temp.resolve("in").resolve("sample.dbc"));

        FileAssetDto mAsset;
        FileAssetDto dAsset;
        try (LocalStellarPlatformClient client = open(dataRoot)) {
            client.projects().createProject(new CreateProjectRequest("Recovery", "", projectId));
            mAsset = FileAssetUploader.ensureAsset(
                    client.assets(), projectId, "src-1", FileAssetKinds.MEASUREMENT, measurement, true);
            dAsset = FileAssetUploader.ensureAsset(
                    client.assets(), projectId, "def-1", FileAssetKinds.DEFINITION, definition, true);

            ProjectProcessingPlan planA = planChannels(List.of(0));
            JobDto run1 = client.processing().startProcessingRun(projectId, new ProcessingRunRequest(
                    ProcessingPlanDtoMapper.toDto(planA),
                    "FORCE_REPROCESS",
                    Map.of("src-1", mAsset.fileAssetId()),
                    Map.of("def-1", dAsset.fileAssetId()),
                    256
            ));
            ProcessingRunResultDto result1 = awaitRun(client, projectId, run1.jobId());
            assertEquals(1, result1.jobResults().size());
            assertTrue(result1.jobResults().getFirst().status().startsWith("SUCCESS"));
        }

        // Simulate application restart: new client, empty in-memory registry.
        try (LocalStellarPlatformClient restarted = open(dataRoot)) {
            ProjectProcessingPlan planBoth = planChannels(List.of(0, 1));
            JobDto run2 = restarted.processing().startProcessingRun(projectId, new ProcessingRunRequest(
                    ProcessingPlanDtoMapper.toDto(planBoth),
                    "REUSE_COMPLETED",
                    Map.of("src-1", mAsset.fileAssetId()),
                    Map.of("def-1", dAsset.fileAssetId()),
                    256
            ));
            ProcessingRunResultDto result2 = awaitRun(restarted, projectId, run2.jobId());
            assertEquals(2, result2.jobResults().size());
            IndependentJobResultDto ch0 = result2.jobResults().stream()
                    .filter(j -> j.channelId() == 0).findFirst().orElseThrow();
            IndependentJobResultDto ch1 = result2.jobResults().stream()
                    .filter(j -> j.channelId() == 1).findFirst().orElseThrow();
            assertTrue(ch0.reused() || ch0.technicalDetail().contains("reopened")
                            || ch0.technicalDetail().toLowerCase().contains("reused"),
                    "channel 0 must reuse durable job after restart; detail=" + ch0.technicalDetail());
            assertTrue(ch1.status().startsWith("SUCCESS"));
            assertFalse(ch1.reused(), "channel 1 is new and must execute");
            assertTrue(PlatformMetrics.get().counter("job.outcome.success") > 0
                    || PlatformMetrics.get().counter("processing.duration.count") > 0);
        }
    }

    @Test
    void cancelMarksJobCancelled() throws Exception {
        Path dataRoot = temp.resolve("cancel-data");
        Files.createDirectories(dataRoot);
        try (LocalStellarPlatformClient client = open(dataRoot)) {
            String projectId = PlatformIds.newId();
            client.projects().createProject(new CreateProjectRequest("Cancel", "", projectId));
            Path measurement = writeAsc(temp.resolve("cancel").resolve("sample.asc"));
            Path definition = writeDbc(temp.resolve("cancel").resolve("sample.dbc"));
            FileAssetDto mAsset = FileAssetUploader.ensureAsset(
                    client.assets(), projectId, "src-1", FileAssetKinds.MEASUREMENT, measurement, true);
            FileAssetDto dAsset = FileAssetUploader.ensureAsset(
                    client.assets(), projectId, "def-1", FileAssetKinds.DEFINITION, definition, true);
            JobDto run = client.processing().startProcessingRun(projectId, new ProcessingRunRequest(
                    ProcessingPlanDtoMapper.toDto(planChannels(List.of(0))),
                    "FORCE_REPROCESS",
                    Map.of("src-1", mAsset.fileAssetId()),
                    Map.of("def-1", dAsset.fileAssetId()),
                    256
            ));
            JobDto cancelled = client.processing().cancelProcessJob(projectId, run.jobId());
            assertEquals(JobStatusDto.CANCELLED, cancelled.status());
        }
    }

    @Test
    void partialStagingArtifactIsNotReusable() throws Exception {
        Path dataRoot = temp.resolve("partial-art");
        Files.createDirectories(dataRoot);
        String projectId = PlatformIds.newId();
        DurableJobArtifactService durable = new DurableJobArtifactService(new MetadataStoreFactory(dataRoot));
        Path staging = durable.jobFinalRoot(projectId, "src-1", 0, "def-1")
                .resolveSibling("ch-0.staging");
        // Intentionally incomplete: staging dir without valid index.
        Files.createDirectories(staging);
        assertTrue(durable.isStagingOnly(staging));
        assertFalse(LosslessGraphSignalStore.hasValidIndex(staging));
    }

    @Test
    void partialUploadSurvivesStoreRestart() throws Exception {
        Path dataRoot = temp.resolve("partial-up");
        byte[] payload = "0123456789ABCDEFGHIJ".getBytes(StandardCharsets.UTF_8);
        String sha = sha(payload);
        FileAssetStore first = new FileAssetStore(dataRoot, 8);
        String projectId = PlatformIds.newId();
        // Project gate is on LocalFileAssetApi; store itself resumes from disk.
        UploadSessionDto session = first.beginUpload(projectId, new BeginUploadRequest(
                "src-1", FileAssetKinds.MEASUREMENT, "big.bin", payload.length, sha));
        first.putChunk(projectId, session.uploadSessionId(), 0, slice(payload, 0, session.chunkSizeBytes()));

        FileAssetStore resumed = new FileAssetStore(dataRoot, 8);
        UploadSessionDto status = resumed.status(projectId, session.uploadSessionId());
        assertEquals(FileAssetStatuses.UPLOADING, status.status());
        assertTrue(status.receivedBytes() > 0);
        assertTrue(status.receivedBytes() < payload.length);
        for (int i = 0; i < status.totalChunks(); i++) {
            if (!status.receivedChunkIndexes().contains(i)) {
                int from = i * status.chunkSizeBytes();
                int len = Math.min(status.chunkSizeBytes(), payload.length - from);
                resumed.putChunk(projectId, session.uploadSessionId(), i, slice(payload, from, len));
            }
        }
        assertEquals(FileAssetStatuses.COMPLETE,
                resumed.complete(projectId, session.uploadSessionId()).status());
    }

    @Test
    void pathTraversalAndOversizedUploadRejected() {
        FileAssetStore store = new FileAssetStore(temp.resolve("sec"), 8, 32);
        String projectId = PlatformIds.newId();
        assertThrows(IllegalArgumentException.class,
                () -> store.beginUpload(projectId, new BeginUploadRequest(
                        "../evil", FileAssetKinds.MEASUREMENT, "x.bin", 1, "")));
        assertThrows(IllegalArgumentException.class,
                () -> store.beginUpload("..", new BeginUploadRequest(
                        "src", FileAssetKinds.MEASUREMENT, "x.bin", 1, "")));
        assertThrows(IllegalArgumentException.class,
                () -> store.beginUpload(projectId, new BeginUploadRequest(
                        "src", FileAssetKinds.MEASUREMENT, "huge.bin", 64, "")));
    }

    @Test
    void invalidProjectAssetRelationshipRejected() throws Exception {
        Path dataRoot = temp.resolve("own");
        Files.createDirectories(dataRoot);
        try (LocalStellarPlatformClient client = open(dataRoot)) {
            String projectA = PlatformIds.newId();
            String projectB = PlatformIds.newId();
            client.projects().createProject(new CreateProjectRequest("A", "", projectA));
            client.projects().createProject(new CreateProjectRequest("B", "", projectB));
            Path measurement = writeAsc(temp.resolve("own").resolve("sample.asc"));
            FileAssetDto assetA = FileAssetUploader.ensureAsset(
                    client.assets(), projectA, "src-1", FileAssetKinds.MEASUREMENT, measurement, true);

            assertTrue(client.assets().getAsset(projectB, assetA.fileAssetId()).isEmpty(),
                    "assets are project-scoped");
            assertThrows(IllegalArgumentException.class,
                    () -> client.processing().startProcessingRun(projectB, new ProcessingRunRequest(
                            ProcessingPlanDtoMapper.toDto(planChannels(List.of(0))),
                            "FORCE_REPROCESS",
                            Map.of("src-1", assetA.fileAssetId()),
                            Map.of("def-1", assetA.fileAssetId()),
                            256
                    )));
            assertThrows(IllegalArgumentException.class,
                    () -> client.assets().beginUpload(PlatformIds.newId(), new BeginUploadRequest(
                            "src", FileAssetKinds.MEASUREMENT, "x.bin", 1, "")));
        }
    }

    @Test
    void structuredLogSanitizesSecrets() {
        assertEquals("<redacted>", ProcessingLog.sanitize("password=hunter2"));
        assertEquals("<redacted>", ProcessingLog.sanitize("Authorization: Bearer abc"));
        assertFalse(ProcessingLog.sanitize("projectId=abc").contains("<redacted>"));
    }

    private static LocalStellarPlatformClient open(Path dataRoot) {
        return LocalStellarPlatformClient.createDefault(dataRoot);
    }

    private static ProcessingRunResultDto awaitRun(
            LocalStellarPlatformClient client,
            String projectId,
            String runId
    ) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            JobDto status = client.processing().getProcessJob(projectId, runId);
            if (status.status() != JobStatusDto.RUNNING && status.status() != JobStatusDto.QUEUED) {
                return client.processing().getProcessingRunResult(projectId, runId).orElseThrow();
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("timed out waiting for processing run " + runId);
    }

    private static ProjectProcessingPlan planChannels(List<Integer> channels) {
        List<ProcessingJob> jobs = channels.stream()
                .map(ch -> new ProcessingJob(
                        ProcessingJobKey.of("src-1", ch),
                        MeasurementSourceFormat.ASC,
                        MappingLifecycle.CONFIRMED,
                        "def-1",
                        "sample.dbc",
                        3L, 1L, 2L, 4L, 0.33, 1L, 0.25,
                        null, true, List.of()
                ))
                .toList();
        return new ProjectProcessingPlan(
                "plan-" + channels,
                Instant.now(),
                jobs,
                ProcessingPlanReadiness.READY,
                List.of()
        );
    }

    private static Path writeAsc(Path target) throws Exception {
        Files.createDirectories(target.getParent());
        Files.writeString(target, """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                Begin Triggerblock Thu Jan 01 12:00:00.000 am 2026
                  0.100000 1 123 Rx d 8 01 02 03 04 05 06 07 08
                  0.200000 1 123 Rx d 8 11 12 13 14 15 16 17 18
                End TriggerBlock
                """);
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

    private static byte[] slice(byte[] payload, int from, int len) {
        byte[] out = new byte[len];
        System.arraycopy(payload, from, out, 0, len);
        return out;
    }

    private static String sha(byte[] payload) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    }
}
