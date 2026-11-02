package com.stellar.platform.api.nft;

import com.stellar.platform.api.assets.FileAssetUploader;
import com.stellar.platform.api.dto.CreateProjectRequest;
import com.stellar.platform.api.dto.FileAssetDto;
import com.stellar.platform.api.dto.JobDto;
import com.stellar.platform.api.dto.JobStatusDto;
import com.stellar.platform.api.dto.ProcessingRunRequest;
import com.stellar.platform.api.dto.ProcessingRunResultDto;
import com.stellar.platform.api.dto.SignalCatalogPageDto;
import com.stellar.platform.api.dto.SignalCatalogQuery;
import com.stellar.platform.api.dto.SignalSamplePageDto;
import com.stellar.platform.api.dto.SignalViewportRequestDto;
import com.stellar.platform.api.dto.SignalViewportResultDto;
import com.stellar.platform.api.identity.FileAssetKinds;
import com.stellar.platform.api.identity.PlatformIds;
import com.stellar.platform.api.local.LocalStellarPlatformClient;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Repeatable NFT baseline: records durations/sizes for representative ASC+DBC work.
 * Soft regression vs checked-in baseline (5× factor) — no invented absolute targets.
 */
class NftBaselineAndRegressionTest {

    @TempDir
    Path temp;

    @Test
    void measureAndCompareAgainstBaseline() throws Exception {
        Path dataRoot = temp.resolve("nft-data");
        Files.createDirectories(dataRoot);
        NftBaselineReport report = new NftBaselineReport();

        try (LocalStellarPlatformClient client = LocalStellarPlatformClient.createDefault(dataRoot)) {
            String projectId = PlatformIds.newId();
            long reopenStart = System.nanoTime();
            client.projects().createProject(new CreateProjectRequest("NFT Baseline", "", projectId));
            report.put("projectCreateMs", (System.nanoTime() - reopenStart) / 1_000_000L);

            Path measurement = writeAsc(temp.resolve("nft.asc"));
            Path definition = writeDbc(temp.resolve("nft.dbc"));
            FileAssetDto mAsset = FileAssetUploader.ensureAsset(
                    client.assets(), projectId, "src-1", FileAssetKinds.MEASUREMENT, measurement, true);
            FileAssetDto dAsset = FileAssetUploader.ensureAsset(
                    client.assets(), projectId, "def-1", FileAssetKinds.DEFINITION, definition, true);

            ProjectProcessingPlan plan = planChannels(List.of(0));
            Runtime rt = Runtime.getRuntime();
            rt.gc();
            long heapBefore = rt.totalMemory() - rt.freeMemory();

            long processStart = System.nanoTime();
            JobDto run1 = client.processing().startProcessingRun(projectId, new ProcessingRunRequest(
                    ProcessingPlanDtoMapper.toDto(plan),
                    "FORCE_REPROCESS",
                    Map.of("src-1", mAsset.fileAssetId()),
                    Map.of("def-1", dAsset.fileAssetId()),
                    256
            ));
            ProcessingRunResultDto result1 = awaitRun(client, projectId, run1.jobId());
            long processMs = (System.nanoTime() - processStart) / 1_000_000L;
            report.put("processingDurationMs", processMs);
            assertFalse(result1.jobResults().isEmpty());

            long catalogStart = System.nanoTime();
            SignalCatalogPageDto catalog = client.signals().catalog(projectId, new SignalCatalogQuery("", "", "", 0, 100));
            report.put("catalogLoadMs", (System.nanoTime() - catalogStart) / 1_000_000L);
            assertTrue(catalog.total() >= 0);

            if (!catalog.entries().isEmpty()) {
                String key = catalog.entries().getFirst().key();
                long rangeStart = System.nanoTime();
                SignalSamplePageDto range = client.signals().range(projectId, key, 0L, Long.MAX_VALUE, 500);
                report.put("signalRangeQueryMs", (System.nanoTime() - rangeStart) / 1_000_000L);
                report.put("signalRangeSamples", range.samples().size());

                long viewportStart = System.nanoTime();
                SignalViewportResultDto viewport = client.signals().viewport(
                        projectId,
                        new SignalViewportRequestDto(List.of(key), 0L, Long.MAX_VALUE, 800)
                );
                report.put("graphViewportQueryMs", (System.nanoTime() - viewportStart) / 1_000_000L);
                report.put("graphViewportSeries", viewport.series().size());
            }

            // Incremental: channel 0 reuse + channel 1 execute
            long incrStart = System.nanoTime();
            JobDto run2 = client.processing().startProcessingRun(projectId, new ProcessingRunRequest(
                    ProcessingPlanDtoMapper.toDto(planChannels(List.of(0, 1))),
                    "REUSE_COMPLETED",
                    Map.of("src-1", mAsset.fileAssetId()),
                    Map.of("def-1", dAsset.fileAssetId()),
                    256
            ));
            ProcessingRunResultDto result2 = awaitRun(client, projectId, run2.jobId());
            report.put("incrementalProcessingMs", (System.nanoTime() - incrStart) / 1_000_000L);
            report.put("incrementalJobs", result2.jobResults().size());

            long artifactBytes = directorySize(dataRoot.resolve("projects"));
            report.put("artifactBytes", artifactBytes);

            rt.gc();
            long heapAfter = rt.totalMemory() - rt.freeMemory();
            report.put("heapDeltaBytes", Math.max(0L, heapAfter - heapBefore));

            // Repeated reopen simulation: catalog load again (session still warm)
            List<Long> reopenSamples = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                long t0 = System.nanoTime();
                client.signals().catalog(projectId, new SignalCatalogQuery("", "", "", 0, 50));
                reopenSamples.add((System.nanoTime() - t0) / 1_000_000L);
            }
            report.put("repeatedCatalogLoadMsAvg",
                    reopenSamples.stream().mapToLong(Long::longValue).average().orElse(0.0));
            report.put("longSessionCatalogLoads", reopenSamples.size());
        }

        Path outDir = Path.of("target", "nft");
        Path current = outDir.resolve("current.json");
        report.writeJson(current);

        Path baseline = Path.of("src", "test", "resources", "nft", "baseline.json");
        if (!Files.isRegularFile(baseline)) {
            // First commit helper: write a seed baseline under target for inspection.
            report.writeJson(outDir.resolve("baseline-seed.json"));
            return;
        }
        Map<String, Object> baseMetrics = NftBaselineReport.readMetrics(baseline);
        Map<String, Object> curMetrics = report.metrics();
        // Soft gate only on relatively stable duration metrics (5× slack for CI noise).
        for (String metric : List.of("processingDurationMs", "catalogLoadMs", "incrementalProcessingMs")) {
            String msg = NftBaselineReport.compareSoft(baseMetrics, curMetrics, metric, 5.0);
            if (!msg.isBlank()) {
                fail(msg);
            }
        }
    }

    private static ProcessingRunResultDto awaitRun(
            LocalStellarPlatformClient client, String projectId, String runId
    ) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            JobDto status = client.processing().getProcessJob(projectId, runId);
            if (status.status() != JobStatusDto.RUNNING && status.status() != JobStatusDto.QUEUED) {
                return client.processing().getProcessingRunResult(projectId, runId).orElseThrow();
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("timed out waiting for NFT run " + runId);
    }

    private static ProjectProcessingPlan planChannels(List<Integer> channels) {
        List<ProcessingJob> jobs = channels.stream()
                .map(ch -> new ProcessingJob(
                        ProcessingJobKey.of("src-1", ch),
                        MeasurementSourceFormat.ASC,
                        MappingLifecycle.CONFIRMED,
                        "def-1",
                        "nft.dbc",
                        3L, 1L, 2L, 4L, 0.33, 1L, 0.25,
                        null, true, List.of()
                ))
                .toList();
        return new ProjectProcessingPlan(
                "nft-plan", Instant.now(), jobs, ProcessingPlanReadiness.READY, List.of());
    }

    private static Path writeAsc(Path target) throws Exception {
        Files.writeString(target, """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                Begin Triggerblock Thu Jan 01 12:00:00.000 am 2026
                  0.100000 1 123 Rx d 8 01 02 03 04 05 06 07 08
                  0.200000 1 123 Rx d 8 11 12 13 14 15 16 17 18
                  0.300000 1 123 Rx d 8 21 22 23 24 25 26 27 28
                End TriggerBlock
                """);
        return target;
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
}
