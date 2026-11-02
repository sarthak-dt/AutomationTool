package com.stellar.platform.server;

import com.stellar.platform.api.StellarPlatformClient;
import com.stellar.platform.api.assets.FileAssetUploader;
import com.stellar.platform.api.dto.CreateProjectRequest;
import com.stellar.platform.api.dto.FileAssetDto;
import com.stellar.platform.api.dto.IndependentJobResultDto;
import com.stellar.platform.api.dto.JobDto;
import com.stellar.platform.api.dto.JobStatusDto;
import com.stellar.platform.api.dto.ProcessingPlanDiagnosticDto;
import com.stellar.platform.api.dto.ProcessingPlanDto;
import com.stellar.platform.api.dto.ProcessingRunRequest;
import com.stellar.platform.api.dto.ProcessingRunResultDto;
import com.stellar.platform.api.dto.SignalCatalogEntryDto;
import com.stellar.platform.api.dto.SignalCatalogPageDto;
import com.stellar.platform.api.dto.SignalCatalogQuery;
import com.stellar.platform.api.dto.SignalSampleDto;
import com.stellar.platform.api.dto.SignalSamplePageDto;
import com.stellar.platform.api.http.HttpStellarPlatformClient;
import com.stellar.platform.api.identity.FileAssetKinds;
import com.stellar.platform.api.identity.PlatformIds;
import com.stellar.platform.api.local.LocalStellarPlatformClient;
import com.stellar.platform.api.support.ProcessingPlanDtoMapper;
import com.stellar.platform.api.support.SignalKeyCodec;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.server.parity.LocalHttpParityReport;
import com.stellar.platform.server.parity.LocalHttpParityReport.Category;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STEP 6 quality gate: same project id, assets, DBC, and {@link ProjectProcessingPlan}
 * via {@link LocalStellarPlatformClient} and {@link HttpStellarPlatformClient} must yield
 * equivalent engineering results.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalHttpProcessingParityCertificationTest {

    /**
     * Physical values from current DBC decode are exact binary64 for these fixtures
     * (integer counts × powers-of-two scales). Use exact equality; do not widen to hide defects.
     */
    private static final double PHYSICAL_EPS = 0.0;

    private static final Path SERVER_DATA_ROOT;

    static {
        try {
            SERVER_DATA_ROOT = Files.createTempDirectory("stellar-parity-cert-" + UUID.randomUUID());
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
    void certifyLocalEqualsHttpProcessingSemantics() throws Exception {
        assertTrue(parserRegistry.providers().size() >= 1);

        LocalHttpParityReport report = new LocalHttpParityReport();
        Path localRoot = temp.resolve("local-data");
        Files.createDirectories(localRoot);

        LocalStellarPlatformClient local = LocalStellarPlatformClient.createDefault(localRoot);
        StellarPlatformClient http = new HttpStellarPlatformClient("http://localhost:" + port);
        try {
            certifySingleSourceCustom(report, local, http);
            certifyMultiSource(report, local, http);
            certifyMultiChannel(report, local, http);
            certifyAutoDecode(report, local, http);
            certifyCustomManual(report, local, http);
            certifyIncremental(report, local, http);
            certifyWarningCase(report, local, http);
        } finally {
            local.close();
        }

        String text = report.format();
        System.out.println(text);
        assertTrue(report.allPassed(), "LOCAL == HTTP certification failed:\n" + text);
    }

    private void certifySingleSourceCustom(
            LocalHttpParityReport report, StellarPlatformClient local, StellarPlatformClient http
    ) throws Exception {
        report.scenario("single-source-custom");
        Path measurement = writeSingleAsc(temp.resolve("single.asc"));
        Path definition = writeSimpleDbc(temp.resolve("single.dbc"));
        String projectId = PlatformIds.newId();
        ProjectProcessingPlan plan = plan(
                "plan-single",
                List.of(job("src-1", 0, MappingLifecycle.CONFIRMED, "def-1", "single.dbc"))
        );
        Side localSide = runForce(local, projectId, plan,
                Map.of("src-1", measurement), Map.of("def-1", definition));
        Side httpSide = runForce(http, projectId, plan,
                Map.of("src-1", measurement), Map.of("def-1", definition));
        compareAll(report, plan, localSide, httpSide);
    }

    private void certifyMultiSource(
            LocalHttpParityReport report, StellarPlatformClient local, StellarPlatformClient http
    ) throws Exception {
        report.scenario("multi-source");
        Path m1 = writeSingleAsc(temp.resolve("ms1.asc"));
        Path m2 = writeSingleAsc(temp.resolve("ms2.asc"));
        Path definition = writeSimpleDbc(temp.resolve("ms.dbc"));
        String projectId = PlatformIds.newId();
        ProjectProcessingPlan plan = plan(
                "plan-multi-src",
                List.of(
                        job("src-a", 0, MappingLifecycle.CONFIRMED, "def-1", "ms.dbc"),
                        job("src-b", 0, MappingLifecycle.CONFIRMED, "def-1", "ms.dbc")
                )
        );
        Side localSide = runForce(local, projectId, plan,
                Map.of("src-a", m1, "src-b", m2), Map.of("def-1", definition));
        Side httpSide = runForce(http, projectId, plan,
                Map.of("src-a", m1, "src-b", m2), Map.of("def-1", definition));
        compareAll(report, plan, localSide, httpSide);
    }

    private void certifyMultiChannel(
            LocalHttpParityReport report, StellarPlatformClient local, StellarPlatformClient http
    ) throws Exception {
        report.scenario("multi-channel");
        Path measurement = writeDualChannelAsc(temp.resolve("mch.asc"));
        Path definition = writeSimpleDbc(temp.resolve("mch.dbc"));
        String projectId = PlatformIds.newId();
        ProjectProcessingPlan plan = plan(
                "plan-multi-ch",
                List.of(
                        job("src-1", 1, MappingLifecycle.CONFIRMED, "def-1", "mch.dbc"),
                        job("src-1", 2, MappingLifecycle.CONFIRMED, "def-1", "mch.dbc")
                )
        );
        Side localSide = runForce(local, projectId, plan,
                Map.of("src-1", measurement), Map.of("def-1", definition));
        Side httpSide = runForce(http, projectId, plan,
                Map.of("src-1", measurement), Map.of("def-1", definition));
        compareAll(report, plan, localSide, httpSide);
    }

    private void certifyAutoDecode(
            LocalHttpParityReport report, StellarPlatformClient local, StellarPlatformClient http
    ) throws Exception {
        report.scenario("auto-decode");
        Path measurement = writeSingleAsc(temp.resolve("auto.asc"));
        Path definition = writeSimpleDbc(temp.resolve("auto.dbc"));
        String projectId = PlatformIds.newId();
        ProjectProcessingPlan plan = plan(
                "plan-auto",
                List.of(job("src-1", 0, MappingLifecycle.RECOMMENDED_FALLBACK, "def-1", "auto.dbc"))
        );
        Side localSide = runForce(local, projectId, plan,
                Map.of("src-1", measurement), Map.of("def-1", definition));
        Side httpSide = runForce(http, projectId, plan,
                Map.of("src-1", measurement), Map.of("def-1", definition));
        compareAll(report, plan, localSide, httpSide);
    }

    private void certifyCustomManual(
            LocalHttpParityReport report, StellarPlatformClient local, StellarPlatformClient http
    ) throws Exception {
        report.scenario("custom-manual");
        Path measurement = writeSingleAsc(temp.resolve("manual.asc"));
        Path definition = writeSimpleDbc(temp.resolve("manual.dbc"));
        String projectId = PlatformIds.newId();
        ProjectProcessingPlan plan = plan(
                "plan-manual",
                List.of(job("src-1", 0, MappingLifecycle.MANUAL, "def-1", "manual.dbc"))
        );
        Side localSide = runForce(local, projectId, plan,
                Map.of("src-1", measurement), Map.of("def-1", definition));
        Side httpSide = runForce(http, projectId, plan,
                Map.of("src-1", measurement), Map.of("def-1", definition));
        compareAll(report, plan, localSide, httpSide);
    }

    private void certifyIncremental(
            LocalHttpParityReport report, StellarPlatformClient local, StellarPlatformClient http
    ) throws Exception {
        report.scenario("incremental");
        Path measurement = writeDualChannelAsc(temp.resolve("inc.asc"));
        Path definition = writeSimpleDbc(temp.resolve("inc.dbc"));
        String projectId = PlatformIds.newId();
        ProjectProcessingPlan first = plan(
                "plan-inc-1",
                List.of(job("src-1", 1, MappingLifecycle.CONFIRMED, "def-1", "inc.dbc"))
        );
        ProjectProcessingPlan second = plan(
                "plan-inc-2",
                List.of(
                        job("src-1", 1, MappingLifecycle.CONFIRMED, "def-1", "inc.dbc"),
                        job("src-1", 2, MappingLifecycle.CONFIRMED, "def-1", "inc.dbc")
                )
        );

        Side localFirst = runForce(local, projectId + "-L", first,
                Map.of("src-1", measurement), Map.of("def-1", definition));
        Side httpFirst = runForce(http, projectId + "-H", first,
                Map.of("src-1", measurement), Map.of("def-1", definition));
        compareJobsAndDiagnostics(report, first, localFirst, httpFirst);

        Side localSecond = runReuse(local, localFirst.projectId, second,
                localFirst.measurementAssets, localFirst.definitionAssets);
        Side httpSecond = runReuse(http, httpFirst.projectId, second,
                httpFirst.measurementAssets, httpFirst.definitionAssets);
        compareAll(report, second, localSecond, httpSecond);
        report.check(Category.JOBS,
                localSecond.result.jobResults().size() == 2
                        && httpSecond.result.jobResults().size() == 2,
                "incremental job count local=" + localSecond.result.jobResults().size()
                        + " http=" + httpSecond.result.jobResults().size());
    }

    private void certifyWarningCase(
            LocalHttpParityReport report, StellarPlatformClient local, StellarPlatformClient http
    ) throws Exception {
        report.scenario("warning-case");
        Path measurement = writeWarningsAsc(temp.resolve("warn.asc"));
        Path definition = writeSimpleDbc(temp.resolve("warn.dbc"));
        String projectId = PlatformIds.newId();
        ProjectProcessingPlan plan = plan(
                "plan-warn",
                List.of(job("src-1", 1, MappingLifecycle.CONFIRMED, "def-1", "warn.dbc"))
        );
        Side localSide = runForce(local, projectId, plan,
                Map.of("src-1", measurement), Map.of("def-1", definition));
        Side httpSide = runForce(http, projectId, plan,
                Map.of("src-1", measurement), Map.of("def-1", definition));
        compareAll(report, plan, localSide, httpSide);
    }

    private void compareAll(
            LocalHttpParityReport report,
            ProjectProcessingPlan plan,
            Side local,
            Side http
    ) {
        comparePlan(report, plan, local, http);
        compareJobsAndDiagnostics(report, plan, local, http);
        compareCatalogMetadataTimestampsValues(report, local, http);
        compareRangeQueries(report, local, http);
    }

    private void comparePlan(
            LocalHttpParityReport report,
            ProjectProcessingPlan plan,
            Side local,
            Side http
    ) {
        ProcessingPlanDto submitted = ProcessingPlanDtoMapper.toDto(plan);
        report.check(Category.PLAN,
                submitted.jobs().size() == local.result.jobResults().size()
                        && submitted.jobs().size() == http.result.jobResults().size(),
                "plan job count vs results submitted=" + submitted.jobs().size()
                        + " local=" + local.result.jobResults().size()
                        + " http=" + http.result.jobResults().size());
        report.check(Category.PLAN,
                local.result.planId().equals(http.result.planId())
                        || Objects.equals(submitted.planId(), local.result.planId()),
                "planId local=" + local.result.planId() + " http=" + http.result.planId());
        report.check(Category.PLAN,
                local.result.status().equals(http.result.status()),
                "run status local=" + local.result.status() + " http=" + http.result.status());
    }

    private void compareJobsAndDiagnostics(
            LocalHttpParityReport report,
            ProjectProcessingPlan plan,
            Side local,
            Side http
    ) {
        List<IndependentJobResultDto> a = sortedJobs(local.result.jobResults());
        List<IndependentJobResultDto> b = sortedJobs(http.result.jobResults());
        report.check(Category.JOBS, a.size() == b.size(),
                "jobCount local=" + a.size() + " http=" + b.size());
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            IndependentJobResultDto la = a.get(i);
            IndependentJobResultDto hb = b.get(i);
            report.check(Category.JOBS,
                    la.projectSourceId().equals(hb.projectSourceId())
                            && la.channelId() == hb.channelId()
                            && la.definitionSourceId().equals(hb.definitionSourceId())
                            && la.mappingLifecycle().equals(hb.mappingLifecycle())
                            && la.status().equals(hb.status()),
                    "job structure local=" + summarize(la) + " http=" + summarize(hb));
            report.check(Category.JOBS,
                    la.signalsProduced() == hb.signalsProduced()
                            && la.sourceFramesRead() == hb.sourceFramesRead()
                            && la.selectedChannelFrames() == hb.selectedChannelFrames()
                            && la.dbcMatchedFrames() == hb.dbcMatchedFrames()
                            && la.dbcUnmatchedFrames() == hb.dbcUnmatchedFrames(),
                    "job counts local signals=" + la.signalsProduced()
                            + " frames=" + la.sourceFramesRead()
                            + " http signals=" + hb.signalsProduced()
                            + " frames=" + hb.sourceFramesRead());
            report.check(Category.DIAGNOSTICS,
                    diagnosticsEquivalent(la.diagnostics(), hb.diagnostics()),
                    "diagnostics local=" + normalizeDiags(la.diagnostics())
                            + " http=" + normalizeDiags(hb.diagnostics()));
            report.check(Category.TIMESTAMPS,
                    Objects.equals(la.firstSampleTimestampNanos(), hb.firstSampleTimestampNanos())
                            && Objects.equals(la.lastSampleTimestampNanos(), hb.lastSampleTimestampNanos()),
                    "job timestamps local=[" + la.firstSampleTimestampNanos()
                            + "," + la.lastSampleTimestampNanos()
                            + "] http=[" + hb.firstSampleTimestampNanos()
                            + "," + hb.lastSampleTimestampNanos() + "]");
        }
        report.check(Category.JOBS, plan.jobs().size() == a.size() || a.size() == b.size(),
                "requested plan jobs=" + plan.jobs().size());
    }

    private void compareCatalogMetadataTimestampsValues(
            LocalHttpParityReport report, Side local, Side http
    ) {
        SignalCatalogPageDto localCat = local.client.signals().catalog(
                local.projectId, new SignalCatalogQuery("", 0, 2000));
        SignalCatalogPageDto httpCat = http.client.signals().catalog(
                http.projectId, new SignalCatalogQuery("", 0, 2000));
        report.check(Category.CATALOG, localCat.total() == httpCat.total(),
                "catalog total local=" + localCat.total() + " http=" + httpCat.total());

        Map<String, SignalCatalogEntryDto> localById = indexCatalog(localCat.entries());
        Map<String, SignalCatalogEntryDto> httpById = indexCatalog(httpCat.entries());
        report.check(Category.CATALOG, localById.keySet().equals(httpById.keySet()),
                "catalog logical ids local=" + localById.keySet() + " http=" + httpById.keySet());

        for (String id : localById.keySet()) {
            SignalCatalogEntryDto le = localById.get(id);
            SignalCatalogEntryDto he = httpById.get(id);
            if (he == null) {
                report.fail(Category.CATALOG, "missing http catalog entry " + id);
                continue;
            }
            report.check(Category.METADATA,
                    le.name().equals(he.name())
                            && le.unit().equals(he.unit())
                            && le.messageName().equals(he.messageName())
                            && le.sourceType().equals(he.sourceType())
                            && le.canId() == he.canId()
                            && le.channel() == he.channel()
                            && le.sampleCount() == he.sampleCount(),
                    "metadata " + id + " local=" + meta(le) + " http=" + meta(he));
            report.check(Category.TIMESTAMPS,
                    Objects.equals(le.firstTimestampNanos(), he.firstTimestampNanos())
                            && Objects.equals(le.lastTimestampNanos(), he.lastTimestampNanos()),
                    "catalog timestamps " + id
                            + " local=[" + le.firstTimestampNanos() + "," + le.lastTimestampNanos() + "]"
                            + " http=[" + he.firstTimestampNanos() + "," + he.lastTimestampNanos() + "]");
            report.check(Category.VALUES,
                    doublesEqual(le.minPhysical(), he.minPhysical())
                            && doublesEqual(le.maxPhysical(), he.maxPhysical()),
                    "catalog min/max " + id
                            + " local=[" + le.minPhysical() + "," + le.maxPhysical() + "]"
                            + " http=[" + he.minPhysical() + "," + he.maxPhysical() + "]");

            compareSamplePages(report, local, http, le.key(), he.key());
        }
    }

    private void compareSamplePages(
            LocalHttpParityReport report,
            Side local,
            Side http,
            String localKey,
            String httpKey
    ) {
        SignalSamplePageDto lp = local.client.signals().samples(local.projectId, localKey, 0, 5000);
        SignalSamplePageDto hp = http.client.signals().samples(http.projectId, httpKey, 0, 5000);
        report.check(Category.VALUES, lp.total() == hp.total() && lp.samples().size() == hp.samples().size(),
                "sample count key=" + logicalId(localKey)
                        + " localTotal=" + lp.total() + " httpTotal=" + hp.total());
        int n = Math.min(lp.samples().size(), hp.samples().size());
        for (int i = 0; i < n; i++) {
            SignalSampleDto a = lp.samples().get(i);
            SignalSampleDto b = hp.samples().get(i);
            report.check(Category.TIMESTAMPS,
                    a.timestampNanos() == b.timestampNanos(),
                    "sample[" + i + "] ts local=" + a.timestampNanos() + " http=" + b.timestampNanos());
            report.check(Category.VALUES,
                    doublesEqual(a.physicalValue(), b.physicalValue())
                            && Objects.equals(a.rawValue(), b.rawValue())
                            && Objects.equals(a.textualValue(), b.textualValue()),
                    "sample[" + i + "] value local=" + a.physicalValue() + " http=" + b.physicalValue());
        }
    }

    private void compareRangeQueries(LocalHttpParityReport report, Side local, Side http) {
        SignalCatalogPageDto localCat = local.client.signals().catalog(
                local.projectId, new SignalCatalogQuery("", 0, 50));
        if (localCat.entries().isEmpty()) {
            report.check(Category.RANGE_QUERIES, true, "no signals — range N/A");
            return;
        }
        SignalCatalogEntryDto le = localCat.entries().getFirst();
        SignalCatalogEntryDto he = indexCatalog(
                http.client.signals().catalog(http.projectId, new SignalCatalogQuery("", 0, 50)).entries()
        ).get(logicalId(le.key()));
        if (he == null) {
            report.fail(Category.RANGE_QUERIES, "http missing signal for range " + logicalId(le.key()));
            return;
        }
        long from = le.firstTimestampNanos() == null ? 0L : le.firstTimestampNanos();
        long to = le.lastTimestampNanos() == null ? from : le.lastTimestampNanos();
        SignalSamplePageDto lr = local.client.signals().range(local.projectId, le.key(), from, to, 100);
        SignalSamplePageDto hr = http.client.signals().range(http.projectId, he.key(), from, to, 100);
        report.check(Category.RANGE_QUERIES, lr.samples().size() == hr.samples().size(),
                "range size local=" + lr.samples().size() + " http=" + hr.samples().size());
        int n = Math.min(lr.samples().size(), hr.samples().size());
        for (int i = 0; i < n; i++) {
            report.check(Category.RANGE_QUERIES,
                    lr.samples().get(i).timestampNanos() == hr.samples().get(i).timestampNanos()
                            && doublesEqual(lr.samples().get(i).physicalValue(),
                            hr.samples().get(i).physicalValue()),
                    "range[" + i + "] mismatch");
        }
        SignalSamplePageDto emptyL = local.client.signals().range(
                local.projectId, le.key(), to + 1_000_000_000L, to + 2_000_000_000L, 10);
        SignalSamplePageDto emptyH = http.client.signals().range(
                http.projectId, he.key(), to + 1_000_000_000L, to + 2_000_000_000L, 10);
        report.check(Category.RANGE_QUERIES,
                emptyL.samples().isEmpty() && emptyH.samples().isEmpty(),
                "empty range local=" + emptyL.samples().size() + " http=" + emptyH.samples().size());
    }

    private Side runForce(
            StellarPlatformClient client,
            String projectId,
            ProjectProcessingPlan plan,
            Map<String, Path> measurements,
            Map<String, Path> definitions
    ) throws Exception {
        return run(client, projectId, plan, "FORCE_REPROCESS", measurements, definitions, Map.of(), Map.of());
    }

    private Side runReuse(
            StellarPlatformClient client,
            String projectId,
            ProjectProcessingPlan plan,
            Map<String, String> measurementAssets,
            Map<String, String> definitionAssets
    ) throws Exception {
        return run(client, projectId, plan, "REUSE_COMPLETED", Map.of(), Map.of(),
                measurementAssets, definitionAssets);
    }

    private Side run(
            StellarPlatformClient client,
            String projectId,
            ProjectProcessingPlan plan,
            String policy,
            Map<String, Path> measurements,
            Map<String, Path> definitions,
            Map<String, String> existingMeasurementAssets,
            Map<String, String> existingDefinitionAssets
    ) throws Exception {
        if (existingMeasurementAssets.isEmpty() && existingDefinitionAssets.isEmpty()) {
            client.projects().createProject(new CreateProjectRequest(
                    "ParityCert-" + projectId, "", projectId));
        }
        Map<String, String> measurementAssets = new LinkedHashMap<>(existingMeasurementAssets);
        Map<String, String> definitionAssets = new LinkedHashMap<>(existingDefinitionAssets);
        for (Map.Entry<String, Path> e : measurements.entrySet()) {
            FileAssetDto asset = FileAssetUploader.ensureAsset(
                    client.assets(), projectId, e.getKey(), FileAssetKinds.MEASUREMENT, e.getValue(), false);
            measurementAssets.put(e.getKey(), asset.fileAssetId());
        }
        for (Map.Entry<String, Path> e : definitions.entrySet()) {
            FileAssetDto asset = FileAssetUploader.ensureAsset(
                    client.assets(), projectId, e.getKey(), FileAssetKinds.DEFINITION, e.getValue(), false);
            definitionAssets.put(e.getKey(), asset.fileAssetId());
        }
        JobDto run = client.processing().startProcessingRun(projectId, new ProcessingRunRequest(
                ProcessingPlanDtoMapper.toDto(plan),
                policy,
                measurementAssets,
                definitionAssets,
                256
        ));
        ProcessingRunResultDto result = await(client, projectId, run.jobId());
        return new Side(client, projectId, result, measurementAssets, definitionAssets);
    }

    private static ProcessingRunResultDto await(
            StellarPlatformClient client, String projectId, String runId
    ) throws Exception {
        for (int i = 0; i < 600; i++) {
            JobDto status = client.processing().getProcessJob(projectId, runId);
            if (status.status() != JobStatusDto.RUNNING && status.status() != JobStatusDto.QUEUED) {
                return client.processing().getProcessingRunResult(projectId, runId).orElseThrow();
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("timed out for run " + runId);
    }

    private static ProjectProcessingPlan plan(String planId, List<ProcessingJob> jobs) {
        return new ProjectProcessingPlan(
                planId,
                Instant.parse("2026-01-02T00:00:00Z"),
                jobs,
                ProcessingPlanReadiness.READY,
                List.of()
        );
    }

    private static ProcessingJob job(
            String sourceId,
            int channelId,
            MappingLifecycle lifecycle,
            String defId,
            String defName
    ) {
        return new ProcessingJob(
                ProcessingJobKey.of(sourceId, channelId),
                MeasurementSourceFormat.ASC,
                lifecycle,
                defId,
                defName,
                1L, 1L, 0L, 2L, 1.0, 2L, 1.0,
                null, true, List.of()
        );
    }

    private static Path writeSingleAsc(Path target) throws Exception {
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

    private static Path writeDualChannelAsc(Path target) throws Exception {
        Files.writeString(target, """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                Begin Triggerblock Thu Jan 01 12:00:00.000 am 2026
                  0.100000 1 123 Rx d 8 01 02 03 04 05 06 07 08
                  0.150000 2 123 Rx d 8 0A 0B 0C 0D 0E 0F 10 11
                  0.200000 1 123 Rx d 8 11 22 33 44 55 66 77 88
                  0.250000 2 123 Rx d 8 21 22 23 24 25 26 27 28
                End TriggerBlock
                """);
        return target;
    }

    private static Path writeWarningsAsc(Path target) throws Exception {
        Files.writeString(target, """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                Begin Triggerblock Thu Jan 01 12:00:00.000 am 2026
                  0.000000 1 123 Rx d 8 01 02 03 04 05 06 07 08
                  this is not a valid frame line
                  0.040000 1 123 Rx d 8 FF EE DD CC BB AA 99 88
                End TriggerBlock
                """);
        return target;
    }

    private static Path writeSimpleDbc(Path target) throws Exception {
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

    private static List<IndependentJobResultDto> sortedJobs(List<IndependentJobResultDto> jobs) {
        return jobs.stream()
                .sorted(Comparator
                        .comparing(IndependentJobResultDto::projectSourceId)
                        .thenComparingInt(IndependentJobResultDto::channelId)
                        .thenComparing(IndependentJobResultDto::definitionSourceId))
                .toList();
    }

    private static Map<String, SignalCatalogEntryDto> indexCatalog(List<SignalCatalogEntryDto> entries) {
        Map<String, SignalCatalogEntryDto> map = new LinkedHashMap<>();
        for (SignalCatalogEntryDto e : entries) {
            map.put(logicalId(e.key()), e);
        }
        return map;
    }

    /** Engineering identity without filesystem basename (Local/HTTP asset names may differ). */
    private static String logicalId(String encodedKey) {
        SignalCatalogKey key = SignalKeyCodec.decode(encodedKey);
        return key.signalName() + "|" + key.sourceType() + "|" + key.messageName()
                + "|" + key.canId() + "|" + key.channel();
    }

    private static boolean diagnosticsEquivalent(
            List<ProcessingPlanDiagnosticDto> a,
            List<ProcessingPlanDiagnosticDto> b
    ) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            ProcessingPlanDiagnosticDto da = a.get(i);
            ProcessingPlanDiagnosticDto db = b.get(i);
            if (!da.code().equals(db.code())
                    || !Objects.equals(da.channelId(), db.channelId())
                    || !Objects.equals(da.projectSourceId(), db.projectSourceId())) {
                return false;
            }
            if (!normalizeDiag(da.detail()).equals(normalizeDiag(db.detail()))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> normalizeDiags(List<ProcessingPlanDiagnosticDto> diags) {
        List<String> out = new ArrayList<>(diags.size());
        for (ProcessingPlanDiagnosticDto d : diags) {
            out.add(d.code() + "|" + d.projectSourceId() + "|" + d.channelId()
                    + "|" + normalizeDiag(d.detail()));
        }
        return out;
    }

    private static String normalizeDiag(String detail) {
        if (detail == null) {
            return "";
        }
        return detail.replaceAll("[A-Za-z]:\\\\[^\\s\\]]+", "<path>")
                .replaceAll("/[^\\s\\]]+/assets/[^\\s\\]]+", "<path>");
    }

    private static boolean doublesEqual(Double a, Double b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (Double.isNaN(a) && Double.isNaN(b)) {
            return true;
        }
        return Math.abs(a - b) <= PHYSICAL_EPS;
    }

    private static boolean doublesEqual(double a, double b) {
        if (Double.isNaN(a) && Double.isNaN(b)) {
            return true;
        }
        return Math.abs(a - b) <= PHYSICAL_EPS;
    }

    private static String summarize(IndependentJobResultDto j) {
        return j.projectSourceId() + "#" + j.channelId() + "/" + j.definitionSourceId()
                + "/" + j.mappingLifecycle() + "/" + j.status();
    }

    private static String meta(SignalCatalogEntryDto e) {
        return e.name() + "/" + e.unit() + "/" + e.messageName() + "/" + e.canId()
                + "/ch" + e.channel() + "/n=" + e.sampleCount();
    }

    private record Side(
            StellarPlatformClient client,
            String projectId,
            ProcessingRunResultDto result,
            Map<String, String> measurementAssets,
            Map<String, String> definitionAssets
    ) {
    }
}
