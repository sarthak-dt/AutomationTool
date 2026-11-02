package com.stellar.platform.server;

import com.stellar.platform.api.StellarPlatformClient;
import com.stellar.platform.api.assets.FileAssetUploader;
import com.stellar.platform.api.dto.CreateProjectRequest;
import com.stellar.platform.api.dto.FileAssetDto;
import com.stellar.platform.api.dto.IndependentJobResultDto;
import com.stellar.platform.api.dto.JobDto;
import com.stellar.platform.api.dto.JobStatusDto;
import com.stellar.platform.api.dto.ProcessingRunRequest;
import com.stellar.platform.api.dto.ProcessingRunResultDto;
import com.stellar.platform.api.http.HttpStellarPlatformClient;
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
import com.stellar.platform.processing.ParserRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test: same {@link ProjectProcessingPlan} via Local client and HTTP localhost server.
 * Differences are asserted/reported — not hidden.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProcessingRunLocalHttpParityTest {

    private static final Path SERVER_DATA_ROOT;

    static {
        try {
            SERVER_DATA_ROOT = Files.createTempDirectory("stellar-server-parity-" + UUID.randomUUID());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("stellar.server.data-root", () -> SERVER_DATA_ROOT.toAbsolutePath().toString());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ParserRegistry parserRegistry;

    @TempDir
    Path temp;

    @Test
    void localAndHttpProduceComparableJobResults() throws Exception {
        assertTrue(parserRegistry.providers().size() >= 1);

        Path measurement = writeAsc(temp.resolve("m.asc"));
        Path definition = writeDbc(temp.resolve("d.dbc"));
        ProjectProcessingPlan plan = planFor("src-1", "def-1");

        Path localRoot = temp.resolve("local-data");
        Files.createDirectories(localRoot);
        LocalStellarPlatformClient local = LocalStellarPlatformClient.createDefault(localRoot);
        ProcessingRunResultDto localResult;
        try {
            localResult = execute(local, plan, measurement, definition);
        } finally {
            local.close();
        }

        StellarPlatformClient http = new HttpStellarPlatformClient("http://localhost:" + port);
        ProcessingRunResultDto httpResult = execute(http, plan, measurement, definition);

        List<String> differences = new ArrayList<>();
        if (!localResult.status().equals(httpResult.status())) {
            differences.add("status local=" + localResult.status() + " http=" + httpResult.status());
        }
        if (localResult.jobResults().size() != httpResult.jobResults().size()) {
            differences.add("jobCount local=" + localResult.jobResults().size()
                    + " http=" + httpResult.jobResults().size());
        } else {
            for (int i = 0; i < localResult.jobResults().size(); i++) {
                IndependentJobResultDto a = localResult.jobResults().get(i);
                IndependentJobResultDto b = httpResult.jobResults().get(i);
                if (!a.projectSourceId().equals(b.projectSourceId())
                        || a.channelId() != b.channelId()
                        || !a.definitionSourceId().equals(b.definitionSourceId())
                        || !a.mappingLifecycle().equals(b.mappingLifecycle())
                        || !a.status().equals(b.status())) {
                    differences.add("job[" + i + "] structure local=" + summarize(a) + " http=" + summarize(b));
                }
                if (a.signalsProduced() != b.signalsProduced()) {
                    differences.add("job[" + i + "] signalsProduced local="
                            + a.signalsProduced() + " http=" + b.signalsProduced());
                }
                if (a.sourceFramesRead() != b.sourceFramesRead()) {
                    differences.add("job[" + i + "] sourceFramesRead local="
                            + a.sourceFramesRead() + " http=" + b.sourceFramesRead());
                }
                if (!a.diagnostics().equals(b.diagnostics())) {
                    boolean onlyPathNoise = a.diagnostics().size() == b.diagnostics().size();
                    if (onlyPathNoise) {
                        for (int d = 0; d < a.diagnostics().size(); d++) {
                            var da = a.diagnostics().get(d);
                            var db = b.diagnostics().get(d);
                            if (!da.code().equals(db.code())
                                    || !Objects.equals(da.channelId(), db.channelId())
                                    || !Objects.equals(da.projectSourceId(), db.projectSourceId())) {
                                onlyPathNoise = false;
                                break;
                            }
                            String na = normalizeDiag(da.detail());
                            String nb = normalizeDiag(db.detail());
                            if (!na.equals(nb)) {
                                onlyPathNoise = false;
                                break;
                            }
                        }
                    }
                    if (!onlyPathNoise) {
                        differences.add("job[" + i + "] diagnostics differ local="
                                + a.diagnostics() + " http=" + b.diagnostics());
                    }
                }
            }
        }

        assertTrue(differences.isEmpty(),
                "Local/HTTP processing-run differences must be reported:\n" + String.join("\n", differences));
        assertFalse(localResult.jobResults().isEmpty());
        assertEquals(localResult.jobResults().getFirst().projectSourceId(),
                httpResult.jobResults().getFirst().projectSourceId());
    }

    private static String summarize(IndependentJobResultDto j) {
        return j.projectSourceId() + "#" + j.channelId() + "/" + j.definitionSourceId()
                + "/" + j.mappingLifecycle() + "/" + j.status();
    }

    /** Strip absolute sandbox paths so Local/HTTP diag text can be compared semantically. */
    private static String normalizeDiag(String detail) {
        if (detail == null) {
            return "";
        }
        return detail.replaceAll("[A-Za-z]:\\\\[^\\s\\]]+", "<path>")
                .replaceAll("/[^\\s\\]]+/assets/[^\\s\\]]+", "<path>");
    }

    private static ProcessingRunResultDto execute(
            StellarPlatformClient client,
            ProjectProcessingPlan plan,
            Path measurement,
            Path definition
    ) throws Exception {
        String projectId = PlatformIds.newId();
        client.projects().createProject(new CreateProjectRequest("Parity", "", projectId));
        FileAssetDto mAsset = FileAssetUploader.ensureAsset(
                client.assets(), projectId, "src-1", FileAssetKinds.MEASUREMENT, measurement, false);
        FileAssetDto dAsset = FileAssetUploader.ensureAsset(
                client.assets(), projectId, "def-1", FileAssetKinds.DEFINITION, definition, false);
        JobDto run = client.processing().startProcessingRun(projectId, new ProcessingRunRequest(
                ProcessingPlanDtoMapper.toDto(plan),
                "FORCE_REPROCESS",
                Map.of("src-1", mAsset.fileAssetId()),
                Map.of("def-1", dAsset.fileAssetId()),
                256
        ));
        for (int i = 0; i < 400; i++) {
            JobDto status = client.processing().getProcessJob(projectId, run.jobId());
            if (status.status() != JobStatusDto.RUNNING && status.status() != JobStatusDto.QUEUED) {
                return client.processing().getProcessingRunResult(projectId, run.jobId()).orElseThrow();
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("timed out for run " + run.jobId());
    }

    private static ProjectProcessingPlan planFor(String sourceId, String defId) {
        ProcessingJob job = new ProcessingJob(
                ProcessingJobKey.of(sourceId, 0),
                MeasurementSourceFormat.ASC,
                MappingLifecycle.CONFIRMED,
                defId,
                "sample.dbc",
                3L, 1L, 2L, 4L, 0.33, 1L, 0.25,
                null, true, List.of()
        );
        return new ProjectProcessingPlan(
                "plan-parity",
                Instant.parse("2026-01-02T00:00:00Z"),
                List.of(job),
                ProcessingPlanReadiness.READY,
                List.of()
        );
    }

    private static Path writeAsc(Path target) throws Exception {
        Files.writeString(target, """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                Begin Triggerblock Thu Jan 01 12:00:00.000 am 2026
                  0.100000 1 123 Rx d 8 01 02 03 04 05 06 07 08
                  0.200000 1 123 Rx d 8 11 22 33 44 55 66 77 88
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
}
