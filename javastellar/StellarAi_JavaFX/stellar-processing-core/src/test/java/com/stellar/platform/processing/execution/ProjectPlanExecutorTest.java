package com.stellar.platform.processing.execution;

import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcByteOrder;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.model.DbcValueTable;
import com.stellar.platform.dbc.model.DbcValueType;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.FileMetadata;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.processing.ProcessingSelection;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnosticCode;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectExecutionStatus;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.CanFrameConsumer;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.WarningConsumer;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.ProcessingResult;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectPlanExecutorTest {

    @Test
    void nativeOnlyJobCompletesWithoutADefinitionId() {
        Path path = Path.of("src-native.asc");
        List<CanonicalCanFrame> frames = List.of(frame(1, 1, 0x100, 10L, (byte) 1));
        ProcessingJob job = new ProcessingJob(
                ProcessingJobKey.of("A", 1),
                MeasurementSourceFormat.ASC,
                MappingLifecycle.NO_MATCH,
                null,
                "",
                1, 0, 1,
                1,
                0.0,
                null,
                true,
                List.of());
        MultiSourceProcessingResult batch = executor(
                Map.of("A", path),
                Map.of(),
                Map.of(path, reader(frames))
        ).execute(readyPlan(job));

        IndependentJobResult result = batch.jobResults().getFirst();
        assertTrue(result.successful());
        assertTrue(result.definitionSourceId().isEmpty());
        assertEquals(MappingLifecycle.NO_MATCH, result.mappingLifecycle());
        assertEquals(JobExecutionStatus.SUCCESS_WITH_WARNINGS, result.status());
    }

    @Test
    void nonReadyPlanDoesNotStartJobs() {
        ProcessingJob recommended = job("A", 1, "def", MappingLifecycle.RECOMMENDED, 10, false);
        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "p1", Instant.now(), List.of(recommended),
                ProcessingPlanReadiness.REQUIRES_CONFIRMATION, List.of());
        MultiSourceProcessingResult result = executor(Map.of(), Map.of()).execute(plan);
        assertEquals(ProjectExecutionStatus.BLOCKED, result.status());
        assertTrue(result.jobResults().isEmpty());
    }

    @Test
    void recommendedIsNeverAutoConfirmedDuringExecution() {
        // Even if somehow marked executable incorrectly, lifecycle stays RECOMMENDED when blocked path used.
        ProcessingJob recommended = job("A", 1, "def", MappingLifecycle.RECOMMENDED, 2, false);
        ProjectProcessingPlan plan = readyPlan(recommended);
        // Force READY wrapper would violate policy; readiness gate blocks.
        ProjectProcessingPlan blocked = new ProjectProcessingPlan(
                plan.planId(), Instant.now(), List.of(recommended),
                ProcessingPlanReadiness.REQUIRES_CONFIRMATION, List.of());
        MultiSourceProcessingResult result = executor(Map.of(), Map.of()).execute(blocked);
        assertEquals(ProjectExecutionStatus.BLOCKED, result.status());
    }

    @Test
    void channelFilterOnlyDecodesSelectedChannel() {
        Path path = Path.of("src-a.asc");
        DbcDatabase dbc = database(0x100, "Msg", "Sig");
        List<CanonicalCanFrame> frames = List.of(
                frame(1, 1, 0x100, 10L, (byte) 1),
                frame(2, 2, 0x100, 20L, (byte) 2),
                frame(3, 3, 0x100, 30L, (byte) 3)
        );
        ProcessingJob job = job("A", 2, "def", MappingLifecycle.CONFIRMED, 1, true);
        MultiSourceProcessingResult batch = executor(
                Map.of("A", path),
                Map.of("def", dbc),
                Map.of(path, reader(frames))
        ).execute(readyPlan(job));

        IndependentJobResult result = batch.jobResults().getFirst();
        assertTrue(result.successful());
        assertEquals(1, result.selectedChannelFrames());
        assertEquals(1, result.signalsProduced());
        SignalSeriesStore store = (SignalSeriesStore) result.catalogHandle().orElseThrow();
        assertTrue(store.snapshot().entries().stream().allMatch(e -> e.channel() == 2));
        assertEquals(20L, result.firstSampleTimestampNanos().orElseThrow());
        assertEquals(20L, result.lastSampleTimestampNanos().orElseThrow());
        assertTrue(store.snapshot().entries().stream().noneMatch(e -> e.channel() == 1 || e.channel() == 3));
    }

    @Test
    void multiSourceIsolationNoCrossContamination() {
        Path pathA = Path.of("source-a.asc");
        Path pathB = Path.of("source-b.asc");
        DbcDatabase dbc = database(0x100, "Msg", "Speed");
        List<CanonicalCanFrame> framesA = List.of(frame(1, 1, 0x100, 100L, (byte) 10));
        List<CanonicalCanFrame> framesB = List.of(frame(1, 1, 0x100, 1L, (byte) 20));

        ProcessingJob jobA = job("A", 1, "def", MappingLifecycle.CONFIRMED, 1, true);
        ProcessingJob jobB = job("B", 1, "def", MappingLifecycle.CONFIRMED, 1, true);
        MultiSourceProcessingResult batch = executor(
                Map.of("A", pathA, "B", pathB),
                Map.of("def", dbc),
                Map.of(pathA, reader(framesA), pathB, reader(framesB))
        ).execute(readyPlan(jobA, jobB));

        assertEquals(ProjectExecutionStatus.SUCCESS, batch.status());
        assertEquals(2, batch.successfulJobs());
        IndependentJobResult ra = batch.resultFor(SourceChannelRef.of("A", 1)).orElseThrow();
        IndependentJobResult rb = batch.resultFor(SourceChannelRef.of("B", 1)).orElseThrow();
        assertEquals("A", ra.projectSourceId());
        assertEquals("B", rb.projectSourceId());
        assertEquals(List.of(100L), timestamps(ra));
        assertEquals(List.of(1L), timestamps(rb));
        // No merged timeline 100,101,... or offset B after A
        assertNotEquals(timestamps(ra), timestamps(rb));
    }

    @Test
    void multiChannelSameSourceProducesIndependentResults() {
        Path path = Path.of("multi-ch.asc");
        DbcDatabase dbc = database(0x100, "Msg", "Sig");
        List<CanonicalCanFrame> frames = List.of(
                frame(1, 1, 0x100, 11L, (byte) 1),
                frame(2, 3, 0x100, 33L, (byte) 3)
        );
        ProcessingJob j1 = job("X", 1, "def", MappingLifecycle.CONFIRMED, 1, true);
        ProcessingJob j3 = job("X", 3, "def", MappingLifecycle.CONFIRMED, 1, true);
        MultiSourceProcessingResult batch = executor(
                Map.of("X", path),
                Map.of("def", dbc),
                Map.of(path, reader(frames))
        ).execute(readyPlan(j1, j3));

        assertEquals(2, batch.jobResults().size());
        assertEquals(List.of(11L), timestamps(batch.resultFor(SourceChannelRef.of("X", 1)).orElseThrow()));
        assertEquals(List.of(33L), timestamps(batch.resultFor(SourceChannelRef.of("X", 3)).orElseThrow()));
    }

    @Test
    void overlappingTimestampsArePreservedIndependently() {
        Path pathA = Path.of("oa.asc");
        Path pathB = Path.of("ob.asc");
        DbcDatabase dbc = database(0x10, "M", "S");
        MultiSourceProcessingResult batch = executor(
                Map.of("A", pathA, "B", pathB),
                Map.of("def", dbc),
                Map.of(
                        pathA, reader(List.of(frame(1, 1, 0x10, 10_000_000_000L, (byte) 1),
                                frame(2, 1, 0x10, 11_000_000_000L, (byte) 2))),
                        pathB, reader(List.of(frame(1, 1, 0x10, 10_000_000_000L, (byte) 3),
                                frame(2, 1, 0x10, 11_000_000_000L, (byte) 4)))
                )
        ).execute(readyPlan(
                job("A", 1, "def", MappingLifecycle.CONFIRMED, 2, true),
                job("B", 1, "def", MappingLifecycle.CONFIRMED, 2, true)
        ));
        assertEquals(List.of(10_000_000_000L, 11_000_000_000L), timestamps(batch.resultFor(SourceChannelRef.of("A", 1)).orElseThrow()));
        assertEquals(List.of(10_000_000_000L, 11_000_000_000L), timestamps(batch.resultFor(SourceChannelRef.of("B", 1)).orElseThrow()));
    }

    @Test
    void overlappingLiteralTimestampsTenAndElevenAreNotDedupedOrOffset() {
        Path pathA = Path.of("oa10.asc");
        Path pathB = Path.of("ob10.asc");
        DbcDatabase dbc = database(0x10, "M", "S");
        MultiSourceProcessingResult batch = executor(
                Map.of("A", pathA, "B", pathB),
                Map.of("def", dbc),
                Map.of(
                        pathA, reader(List.of(frame(1, 1, 0x10, 10L, (byte) 1), frame(2, 1, 0x10, 11L, (byte) 2))),
                        pathB, reader(List.of(frame(1, 1, 0x10, 10L, (byte) 3), frame(2, 1, 0x10, 11L, (byte) 4)))
                )
        ).execute(readyPlan(
                job("A", 1, "def", MappingLifecycle.CONFIRMED, 2, true),
                job("B", 1, "def", MappingLifecycle.CONFIRMED, 2, true)
        ));
        assertEquals(List.of(10L, 11L), timestamps(batch.resultFor(SourceChannelRef.of("A", 1)).orElseThrow()));
        assertEquals(List.of(10L, 11L), timestamps(batch.resultFor(SourceChannelRef.of("B", 1)).orElseThrow()));
    }

    @Test
    void unmatchedCanIdsYieldSuccessWithWarningsAndMatchAccounting() {
        Path path = Path.of("partial-match.asc");
        DbcDatabase dbc = database(0x100, "Msg", "Sig");
        List<CanonicalCanFrame> frames = List.of(
                frame(1, 1, 0x100, 10L, (byte) 1),
                frame(2, 1, 0x200, 20L, (byte) 2)
        );
        IndependentJobResult result = executor(
                Map.of("A", path), Map.of("def", dbc), Map.of(path, reader(frames))
        ).execute(readyPlan(job("A", 1, "def", MappingLifecycle.CONFIRMED, 2, true)))
                .jobResults().getFirst();

        assertEquals(JobExecutionStatus.SUCCESS_WITH_WARNINGS, result.status());
        assertTrue(result.successful());
        assertEquals(2, result.selectedChannelFrames());
        assertEquals(1, result.dbcMatchedFrames());
        assertEquals(1, result.dbcUnmatchedFrames());
        assertEquals(1, result.signalsProduced());
        assertTrue(result.dbcMatchedFrames() <= result.selectedChannelFrames());
        // Pipeline UNMATCHED_CAN_ID diagnostics are not copied onto IndependentJobResult today.
        assertTrue(result.diagnostics().stream()
                .noneMatch(d -> d.code() == ProcessingPlanDiagnosticCode.FRAME_COUNT_MISMATCH));
    }

    @Test
    void fullDbcMatchIsSuccessWithoutWarnings() {
        Path path = Path.of("full-match.asc");
        DbcDatabase dbc = database(0x100, "Msg", "Sig");
        IndependentJobResult result = executor(
                Map.of("A", path), Map.of("def", dbc),
                Map.of(path, reader(List.of(frame(1, 1, 0x100, 10L, (byte) 1))))
        ).execute(readyPlan(job("A", 1, "def", MappingLifecycle.CONFIRMED, 1, true)))
                .jobResults().getFirst();
        assertEquals(JobExecutionStatus.SUCCESS, result.status());
        assertEquals(1, result.selectedChannelFrames());
        assertEquals(1, result.dbcMatchedFrames());
        assertEquals(0, result.dbcUnmatchedFrames());
    }

    @Test
    void parserFailureIsFailedNotSuccessWithWarnings() {
        Path path = Path.of("bad.asc");
        DbcDatabase dbc = database(0x10, "M", "S");
        AutomotiveFileReader failing = new AutomotiveFileReader() {
            @Override public String formatKey() { return "ASC"; }
            @Override public boolean supports(Path p) { return true; }
            @Override public FileMetadata readMetadata(Path p) {
                return new FileMetadata("ASC", "bad", "bad");
            }
            @Override public void readFrames(Path p, ReaderContext context, CanFrameConsumer frameConsumer,
                                             WarningConsumer warningConsumer) throws ParserException {
                throw new ParserException("unreadable payload");
            }
        };
        IndependentJobResult result = executor(
                Map.of("A", path), Map.of("def", dbc), Map.of(path, failing)
        ).execute(readyPlan(job("A", 1, "def", MappingLifecycle.CONFIRMED, 1, true)))
                .jobResults().getFirst();
        assertEquals(JobExecutionStatus.FAILED, result.status());
        assertFalse(result.successful());
        assertNotEquals(JobExecutionStatus.SUCCESS_WITH_WARNINGS, result.status());
    }

    @Test
    void missingDefinitionIsFailedNotSuccessWithWarnings() {
        Path path = Path.of("nodef.asc");
        IndependentJobResult result = executor(
                Map.of("A", path), Map.of(), Map.of(path, reader(List.of(frame(1, 1, 0x10, 1L, (byte) 1))))
        ).execute(readyPlan(job("A", 1, "missing-def", MappingLifecycle.CONFIRMED, 1, true)))
                .jobResults().getFirst();
        assertEquals(JobExecutionStatus.FAILED, result.status());
        assertNotEquals(JobExecutionStatus.SUCCESS_WITH_WARNINGS, result.status());
    }

    @Test
    void frameCountMismatchIsNonFatalWarningNotSilentSuccess() {
        Path path = Path.of("mismatch.asc");
        DbcDatabase dbc = database(0x10, "M", "S");
        IndependentJobResult result = executor(
                Map.of("A", path), Map.of("def", dbc),
                Map.of(path, reader(List.of(frame(1, 1, 0x10, 1L, (byte) 1))))
        ).execute(readyPlan(job("A", 1, "def", MappingLifecycle.CONFIRMED, 99, true)))
                .jobResults().getFirst();
        assertEquals(1, result.selectedChannelFrames());
        assertEquals(99, result.expectedSelectedChannelFrames());
        assertEquals(JobExecutionStatus.SUCCESS_WITH_WARNINGS, result.status());
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.code() == ProcessingPlanDiagnosticCode.FRAME_COUNT_MISMATCH));
    }

    @Test
    void allSuccessWithWarningsProjectStatusIsSuccessAndCountedAsSuccessful() {
        Path path = Path.of("warn.asc");
        DbcDatabase dbc = database(0x100, "Msg", "Sig");
        List<CanonicalCanFrame> frames = List.of(
                frame(1, 1, 0x100, 10L, (byte) 1),
                frame(2, 1, 0x200, 20L, (byte) 2)
        );
        MultiSourceProcessingResult batch = executor(
                Map.of("A", path), Map.of("def", dbc), Map.of(path, reader(frames))
        ).execute(readyPlan(job("A", 1, "def", MappingLifecycle.CONFIRMED, 2, true)));
        assertEquals(ProjectExecutionStatus.SUCCESS, batch.status());
        assertEquals(1, batch.successfulJobs());
        assertEquals(0, batch.failedJobs());
        assertEquals(JobExecutionStatus.SUCCESS_WITH_WARNINGS, batch.jobResults().getFirst().status());
    }

    @Test
    void registryStoresIndependentEntriesPerJobKey() {
        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        IndependentJobResult a = jobResult("A", 1, JobExecutionStatus.SUCCESS_WITH_WARNINGS);
        IndependentJobResult b = jobResult("B", 1, JobExecutionStatus.SUCCESS_WITH_WARNINGS);
        registry.publishJob(a);
        registry.publishJob(b);
        assertEquals(2, registry.all().size());
        assertEquals("A", registry.get(SourceChannelRef.of("A", 1)).orElseThrow().projectSourceId());
        assertEquals("B", registry.get(SourceChannelRef.of("B", 1)).orElseThrow().projectSourceId());
        assertNotEquals(
                registry.get(ProcessingJobKey.of("A", 1)).orElseThrow().jobKey(),
                registry.get(ProcessingJobKey.of("B", 1)).orElseThrow().jobKey());
    }

    @Test
    void planJobOrderDoesNotAlterSampleTimestamps() {
        Path pathA = Path.of("order-a.asc");
        Path pathB = Path.of("order-b.asc");
        DbcDatabase dbc = database(0x10, "M", "S");
        // Execute B before A in plan order; timestamps must remain source-native.
        MultiSourceProcessingResult batch = executor(
                Map.of("A", pathA, "B", pathB),
                Map.of("def", dbc),
                Map.of(
                        pathA, reader(List.of(frame(1, 1, 0x10, 1000L, (byte) 1))),
                        pathB, reader(List.of(frame(1, 1, 0x10, 1L, (byte) 2)))
                )
        ).execute(readyPlan(
                job("B", 1, "def", MappingLifecycle.CONFIRMED, 1, true),
                job("A", 1, "def", MappingLifecycle.CONFIRMED, 1, true)
        ));
        assertEquals("B", batch.jobResults().get(0).projectSourceId());
        assertEquals("A", batch.jobResults().get(1).projectSourceId());
        assertEquals(List.of(1L), timestamps(batch.resultFor(SourceChannelRef.of("B", 1)).orElseThrow()));
        assertEquals(List.of(1000L), timestamps(batch.resultFor(SourceChannelRef.of("A", 1)).orElseThrow()));
    }

    @Test
    void noTimelineConcatenationAcrossSources() {
        Path pathA = Path.of("ta.asc");
        Path pathB = Path.of("tb.asc");
        DbcDatabase dbc = database(0x10, "M", "S");
        MultiSourceProcessingResult batch = executor(
                Map.of("A", pathA, "B", pathB),
                Map.of("def", dbc),
                Map.of(
                        pathA, reader(List.of(
                                frame(1, 1, 0x10, 100L, (byte) 1),
                                frame(2, 1, 0x10, 101L, (byte) 1),
                                frame(3, 1, 0x10, 102L, (byte) 1))),
                        pathB, reader(List.of(
                                frame(1, 1, 0x10, 1L, (byte) 2),
                                frame(2, 1, 0x10, 2L, (byte) 2),
                                frame(3, 1, 0x10, 3L, (byte) 2)))
                )
        ).execute(readyPlan(
                job("A", 1, "def", MappingLifecycle.CONFIRMED, 3, true),
                job("B", 1, "def", MappingLifecycle.CONFIRMED, 3, true)
        ));
        assertEquals(List.of(100L, 101L, 102L), timestamps(batch.resultFor(SourceChannelRef.of("A", 1)).orElseThrow()));
        assertEquals(List.of(1L, 2L, 3L), timestamps(batch.resultFor(SourceChannelRef.of("B", 1)).orElseThrow()));
        // Explicitly forbid synthetic continuous merge
        List<Long> mergedWrong = List.of(100L, 101L, 102L, 103L, 104L, 105L);
        assertFalse(timestamps(batch.resultFor(SourceChannelRef.of("A", 1)).orElseThrow()).equals(mergedWrong));
    }

    @Test
    void partialFailureRetainsSuccessfulResults() {
        Path pathOk = Path.of("ok.asc");
        Path pathFail = Path.of("fail.asc");
        DbcDatabase dbc = database(0x10, "M", "S");
        AutomotiveFileReader failing = new AutomotiveFileReader() {
            @Override public String formatKey() { return "ASC"; }
            @Override public boolean supports(Path path) { return true; }
            @Override public FileMetadata readMetadata(Path path) {
                return new FileMetadata("ASC", "fail", "fail");
            }
            @Override public void readFrames(Path path, ReaderContext context, CanFrameConsumer frameConsumer,
                                             WarningConsumer warningConsumer) throws ParserException {
                throw new ParserException("forced controlled failure");
            }
        };
        MultiSourceProcessingResult batch = executor(
                Map.of("OK1", pathOk, "BAD", pathFail, "OK2", pathOk),
                Map.of("def", dbc),
                Map.of(pathOk, reader(List.of(frame(1, 1, 0x10, 5L, (byte) 1))), pathFail, failing)
        ).execute(readyPlan(
                job("OK1", 1, "def", MappingLifecycle.CONFIRMED, 1, true),
                job("BAD", 1, "def", MappingLifecycle.CONFIRMED, 1, true),
                job("OK2", 1, "def", MappingLifecycle.CONFIRMED, 1, true)
        ));
        assertEquals(ProjectExecutionStatus.PARTIAL_FAILURE, batch.status());
        assertEquals(2, batch.successfulJobs());
        assertEquals(1, batch.failedJobs());
        assertTrue(batch.resultFor(SourceChannelRef.of("OK1", 1)).orElseThrow().successful());
        assertTrue(batch.resultFor(SourceChannelRef.of("OK2", 1)).orElseThrow().successful());
        assertEquals(JobExecutionStatus.FAILED, batch.resultFor(SourceChannelRef.of("BAD", 1)).orElseThrow().status());
    }

    @Test
    void exactSelectedFrameCountMatchesInventoryExpectation() {
        Path path = Path.of("frames.asc");
        DbcDatabase dbc = database(0x10, "M", "S");
        List<CanonicalCanFrame> frames = List.of(
                frame(1, 1, 0x10, 1L, (byte) 1),
                frame(2, 1, 0x10, 2L, (byte) 1),
                frame(3, 1, 0x10, 3L, (byte) 1)
        );
        ProcessingJob job = job("A", 1, "def", MappingLifecycle.CONFIRMED, 3, true);
        IndependentJobResult result = executor(
                Map.of("A", path), Map.of("def", dbc), Map.of(path, reader(frames))
        ).execute(readyPlan(job)).jobResults().getFirst();
        assertEquals(3, result.expectedSelectedChannelFrames());
        assertEquals(3, result.selectedChannelFrames());
        assertTrue(result.diagnostics().stream()
                .noneMatch(d -> d.code() == ProcessingPlanDiagnosticCode.FRAME_COUNT_MISMATCH));
    }

    @Test
    void singleSourceParityWithDirectPipelineTimestampAndValueHash() throws Exception {
        Path path = Path.of("parity.asc");
        String identity = ProcessingSelection.sourceIdentity(path);
        ProcessingSelection selection = ProcessingSelection.forSource(identity, List.of(1));
        DbcDatabase dbc = database(0x55, "Msg", "S");
        List<CanonicalCanFrame> frames = List.of(
                frame(1, 1, 0x55, 100L, (byte) 7),
                frame(2, 9, 0x55, 101L, (byte) 8),
                frame(3, 1, 0x55, 200L, (byte) 9)
        );
        AutomotiveFileReader reader = reader(frames);
        MeasurementDecodePipeline pipeline = new MeasurementDecodePipeline(
                new ParserRegistry(List.of()), new DefaultDbcDecoder());

        List<CanonicalSignal> directSignals = new ArrayList<>();
        ProcessingResult direct = pipeline.process(
                reader, path, dbc, ReaderContext.ofBatchSize(32),
                directSignals::addAll, ProcessingProgressListener.NOOP, selection);

        IndependentJobResult viaPlan = executor(
                Map.of("A", path), Map.of("def", dbc), Map.of(path, reader)
        ).execute(readyPlan(job("A", 1, "def", MappingLifecycle.CONFIRMED, 2, true)))
                .jobResults().getFirst();

        assertEquals(direct.statistics().framesSelectedForDecode(), viaPlan.selectedChannelFrames());
        assertEquals(direct.statistics().signalsEmitted(), viaPlan.signalsProduced());
        assertEquals(hashSignals(directSignals), hashCatalog(viaPlan));
        assertEquals(hashTimestampList(timestampsFromSignals(directSignals)), hashTimestampList(timestamps(viaPlan)));
    }

    @Test
    void quickDecodeSameSourceAndDbcReadsMeasurementOnlyOnceForMultipleChannels() {
        Path path = Path.of("shared-pass.asc");
        DbcDatabase dbc = database(0x100, "Msg", "Sig");
        List<CanonicalCanFrame> frames = List.of(
                frame(1, 1, 0x100, 10L, (byte) 1),
                frame(2, 2, 0x100, 20L, (byte) 2),
                frame(3, 3, 0x100, 30L, (byte) 3)
        );
        AtomicInteger reads = new AtomicInteger();
        AutomotiveFileReader reader = countingReader(frames, reads);

        MultiSourceProcessingResult batch = executor(
                Map.of("A", path), Map.of("def", dbc), Map.of(path, reader)
        ).execute(readyPlan(
                quickJob("A", 1, "def", 1, 1),
                quickJob("A", 2, "def", 1, 1),
                quickJob("A", 3, "def", 1, 1)
        ));

        assertEquals(1, reads.get(), "same source + same DBC must use one streaming pass");
        assertEquals(3, batch.successfulJobs());
        assertEquals(List.of(10L), timestamps(batch.resultFor(SourceChannelRef.of("A", 1)).orElseThrow()));
        assertEquals(List.of(20L), timestamps(batch.resultFor(SourceChannelRef.of("A", 2)).orElseThrow()));
        assertEquals(List.of(30L), timestamps(batch.resultFor(SourceChannelRef.of("A", 3)).orElseThrow()));
    }

    @Test
    void differentDbcsShareOneSourcePassWhileEachResultKeepsChannelIsolation() {
        Path path = Path.of("two-definitions.asc");
        DbcDatabase dbcA = database(0x100, "MsgA", "SigA");
        DbcDatabase dbcB = database(0x100, "MsgB", "SigB");
        List<CanonicalCanFrame> frames = List.of(
                frame(1, 1, 0x100, 10L, (byte) 1),
                frame(2, 2, 0x100, 20L, (byte) 2)
        );
        AtomicInteger reads = new AtomicInteger();
        AutomotiveFileReader reader = countingReader(frames, reads);

        MultiSourceProcessingResult batch = executor(
                Map.of("A", path), Map.of("def-A", dbcA, "def-B", dbcB), Map.of(path, reader)
        ).execute(readyPlan(
                quickJob("A", 1, "def-A", 1, 1),
                quickJob("A", 2, "def-B", 1, 1)
        ));

        assertEquals(1, reads.get(), "different DBC mappings on one source must share one routed source pass");
        assertEquals(List.of(10L), timestamps(batch.resultFor(SourceChannelRef.of("A", 1)).orElseThrow()));
        assertEquals(List.of(20L), timestamps(batch.resultFor(SourceChannelRef.of("A", 2)).orElseThrow()));
    }

    @Test
    void incrementalExecutionSkipsCompletedOldSourceAndReadsOnlyNewSource() {
        Path oldPath = Path.of("old-completed.asc");
        Path newPath = Path.of("newly-added.asc");
        DbcDatabase dbc = database(0x100, "Msg", "Sig");
        AtomicInteger oldReads = new AtomicInteger();
        AtomicInteger newReads = new AtomicInteger();
        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        registry.publishJob(jobResult("OLD", 1, JobExecutionStatus.SUCCESS));

        PlanExecutionContext ctx = PlanExecutionContext.builder(
                        new MeasurementDecodePipeline(new ParserRegistry(List.of()), new DefaultDbcDecoder()))
                .measurementPathResolver(id -> "OLD".equals(id) ? oldPath : newPath)
                .definitionDatabaseResolver(id -> Optional.of(dbc))
                .readerResolver(path -> Optional.of(path.equals(oldPath)
                        ? countingReader(List.of(frame(1, 1, 0x100, 10L, (byte) 1)), oldReads)
                        : countingReader(List.of(frame(1, 1, 0x100, 20L, (byte) 2)), newReads)))
                .build();

        MultiSourceProcessingResult batch = new ProjectPlanExecutor(ctx, registry).execute(readyPlan(
                job("OLD", 1, "def", MappingLifecycle.CONFIRMED, 1, true),
                job("NEW", 1, "def", MappingLifecycle.CONFIRMED, 1, true)));

        assertEquals(0, oldReads.get(), "completed unchanged source must not be opened again");
        assertEquals(1, newReads.get(), "new source must be read exactly once");
        assertEquals(2, batch.successfulJobs());
    }

    @Test
    void reuseCompletedNotifiesListenerAndDoesNotRewriteArtifact() {
        Path path = Path.of("reuse-proof.asc");
        DbcDatabase dbc = database(0x100, "Msg", "Sig");
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger reused = new AtomicInteger();
        AtomicInteger started = new AtomicInteger();
        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        IndependentJobResult first = jobResult("A", 1, JobExecutionStatus.SUCCESS);
        registry.publishJob(first);

        PlanExecutionContext ctx = PlanExecutionContext.builder(
                        new MeasurementDecodePipeline(new ParserRegistry(List.of()), new DefaultDbcDecoder()))
                .measurementPathResolver(id -> path)
                .definitionDatabaseResolver(id -> Optional.of(dbc))
                .readerResolver(p -> Optional.of(countingReader(
                        List.of(frame(1, 1, 0x100, 10L, (byte) 1)), reads)))
                .build();

        PlanJobLifecycleListener spy = new PlanJobLifecycleListener() {
            @Override
            public void onJobReused(ProcessingJob job, IndependentJobResult result, int index, int total) {
                reused.incrementAndGet();
            }

            @Override
            public void onJobStarting(ProcessingJob job, int index, int total) {
                started.incrementAndGet();
            }

            @Override
            public void onJobFinished(IndependentJobResult result, int index, int total) {
            }
        };

        MultiSourceProcessingResult batch = new ProjectPlanExecutor(ctx, registry).execute(
                readyPlan(job("A", 1, "def", MappingLifecycle.CONFIRMED, 1, true)),
                spy,
                PlanExecutionPolicy.REUSE_COMPLETED);

        assertEquals(0, reads.get(), "REUSE_COMPLETED must not reparse the source");
        assertEquals(0, started.get(), "reused jobs must not start a decode");
        assertEquals(1, reused.get());
        assertSame(first, registry.get(SourceChannelRef.of("A", 1)).orElseThrow());
        assertEquals(1, batch.successfulJobs());
    }

    @Test
    void fiveIncrementalCyclesDoNotRereadCompletedWork() {
        Path path = Path.of("cycle.asc");
        DbcDatabase dbc = database(0x100, "Msg", "Sig");
        AtomicInteger reads = new AtomicInteger();
        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        PlanExecutionContext ctx = PlanExecutionContext.builder(
                        new MeasurementDecodePipeline(new ParserRegistry(List.of()), new DefaultDbcDecoder()))
                .measurementPathResolver(id -> path)
                .definitionDatabaseResolver(id -> Optional.of(dbc))
                .readerResolver(p -> Optional.of(countingReader(
                        List.of(frame(1, 1, 0x100, 10L, (byte) 1)), reads)))
                .build();
        ProjectPlanExecutor executor = new ProjectPlanExecutor(ctx, registry);
        ProcessingJob job = job("A", 1, "def", MappingLifecycle.CONFIRMED, 1, true);
        executor.execute(readyPlan(job));
        assertEquals(1, reads.get());
        for (int i = 0; i < 5; i++) {
            executor.execute(readyPlan(job));
        }
        assertEquals(1, reads.get(), "later cycles must reuse the completed artifact");
        assertEquals(1, registry.all().size());
    }

    @Test
    void incrementalExecutionRetriesPriorFailureInsteadOfCachingIt() {
        Path path = Path.of("retry.asc");
        DbcDatabase dbc = database(0x100, "Msg", "Sig");
        AtomicInteger reads = new AtomicInteger();
        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        registry.publishJob(jobResult("A", 1, JobExecutionStatus.FAILED));

        PlanExecutionContext ctx = PlanExecutionContext.builder(
                        new MeasurementDecodePipeline(new ParserRegistry(List.of()), new DefaultDbcDecoder()))
                .measurementPathResolver(id -> path)
                .definitionDatabaseResolver(id -> Optional.of(dbc))
                .readerResolver(p -> Optional.of(countingReader(
                        List.of(frame(1, 1, 0x100, 10L, (byte) 1)), reads)))
                .build();

        new ProjectPlanExecutor(ctx, registry).execute(readyPlan(
                job("A", 1, "def", MappingLifecycle.CONFIRMED, 1, true)));

        assertEquals(1, reads.get(), "failed result must be retried, never treated as reusable completion");
    }

    @Test
    void changedDbcInvalidatesCompletedJobAndReprocessesSource() {
        Path path = Path.of("changed-dbc.asc");
        DbcDatabase dbc = database(0x100, "Msg", "Sig");
        AtomicInteger reads = new AtomicInteger();
        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        registry.publishJob(jobResult("A", 1, JobExecutionStatus.SUCCESS)); // definition id = def

        PlanExecutionContext ctx = PlanExecutionContext.builder(
                        new MeasurementDecodePipeline(new ParserRegistry(List.of()), new DefaultDbcDecoder()))
                .measurementPathResolver(id -> path)
                .definitionDatabaseResolver(id -> Optional.of(dbc))
                .readerResolver(p -> Optional.of(countingReader(
                        List.of(frame(1, 1, 0x100, 10L, (byte) 1)), reads)))
                .build();

        new ProjectPlanExecutor(ctx, registry).execute(readyPlan(
                job("A", 1, "different-def", MappingLifecycle.CONFIRMED, 1, true)));

        assertEquals(1, reads.get(), "changed effective DBC is a cache miss and must be processed");
    }

    @Test
    void fourSourceChangeOnlyBReprocessesBAndReusesAcd() {
        Path pathA = Path.of("A.asc");
        Path pathB = Path.of("B.asc");
        Path pathC = Path.of("C.asc");
        Path pathD = Path.of("D.asc");
        DbcDatabase dbcA = database(0x100, "MsgA", "SigA");
        DbcDatabase dbcB = database(0x200, "MsgB", "SigB");
        DbcDatabase dbcC = database(0x300, "MsgC", "SigC");
        DbcDatabase dbcD = database(0x400, "MsgD", "SigD");
        AtomicInteger readsA = new AtomicInteger();
        AtomicInteger readsB = new AtomicInteger();
        AtomicInteger readsC = new AtomicInteger();
        AtomicInteger readsD = new AtomicInteger();
        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        PlanExecutionContext ctx = PlanExecutionContext.builder(
                        new MeasurementDecodePipeline(new ParserRegistry(List.of()), new DefaultDbcDecoder()))
                .measurementPathResolver(id -> switch (id) {
                    case "A" -> pathA;
                    case "B" -> pathB;
                    case "C" -> pathC;
                    default -> pathD;
                })
                .definitionDatabaseResolver(id -> Optional.ofNullable(switch (id) {
                    case "def-A" -> dbcA;
                    case "def-B" -> dbcB;
                    case "def-C" -> dbcC;
                    case "def-D" -> dbcD;
                    default -> null;
                }))
                .readerResolver(path -> {
                    if (path.equals(pathA)) {
                        return Optional.of(countingReader(List.of(frame(1, 1, 0x100, 10L, (byte) 1)), readsA));
                    }
                    if (path.equals(pathB)) {
                        return Optional.of(countingReader(List.of(frame(1, 1, 0x200, 20L, (byte) 2)), readsB));
                    }
                    if (path.equals(pathC)) {
                        return Optional.of(countingReader(List.of(frame(1, 1, 0x300, 30L, (byte) 3)), readsC));
                    }
                    return Optional.of(countingReader(List.of(frame(1, 1, 0x400, 40L, (byte) 4)), readsD));
                })
                .build();
        ProjectPlanExecutor executor = new ProjectPlanExecutor(ctx, registry);
        ProjectProcessingPlan plan = readyPlan(
                job("A", 1, "def-A", MappingLifecycle.CONFIRMED, 1, true),
                job("B", 1, "def-B", MappingLifecycle.CONFIRMED, 1, true),
                job("C", 1, "def-C", MappingLifecycle.CONFIRMED, 1, true),
                job("D", 1, "def-D", MappingLifecycle.CONFIRMED, 1, true));

        executor.execute(plan);
        assertEquals(1, readsA.get());
        assertEquals(1, readsB.get());
        assertEquals(1, readsC.get());
        assertEquals(1, readsD.get());

        executor.execute(plan);
        assertEquals(1, readsA.get(), "A must be reused");
        assertEquals(1, readsB.get(), "unchanged B must be reused");
        assertEquals(1, readsC.get(), "C must be reused");
        assertEquals(1, readsD.get(), "D must be reused");

        ProcessingResultRegistry withoutB = new ProcessingResultRegistry();
        withoutB.publishJob(registry.get(ProcessingJobKey.of("A", 1)).orElseThrow());
        withoutB.publishJob(registry.get(ProcessingJobKey.of("C", 1)).orElseThrow());
        withoutB.publishJob(registry.get(ProcessingJobKey.of("D", 1)).orElseThrow());
        new ProjectPlanExecutor(ctx, withoutB).execute(plan);
        assertEquals(1, readsA.get(), "A parser invocations after B invalidation");
        assertEquals(2, readsB.get(), "B must be reparsed after its result is no longer reusable");
        assertEquals(1, readsC.get(), "C parser invocations after B invalidation");
        assertEquals(1, readsD.get(), "D parser invocations after B invalidation");
    }

    @Test
    void fourSourceChangeOnlyBDefinitionReprocessesB() {
        Path pathA = Path.of("A-def.asc");
        Path pathB = Path.of("B-def.asc");
        Path pathC = Path.of("C-def.asc");
        Path pathD = Path.of("D-def.asc");
        DbcDatabase shared = database(0x100, "Msg", "Sig");
        DbcDatabase dbcB2 = database(0x200, "MsgB2", "SigB2");
        AtomicInteger readsA = new AtomicInteger();
        AtomicInteger readsB = new AtomicInteger();
        AtomicInteger readsC = new AtomicInteger();
        AtomicInteger readsD = new AtomicInteger();
        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        PlanExecutionContext ctx = PlanExecutionContext.builder(
                        new MeasurementDecodePipeline(new ParserRegistry(List.of()), new DefaultDbcDecoder()))
                .measurementPathResolver(id -> switch (id) {
                    case "A" -> pathA;
                    case "B" -> pathB;
                    case "C" -> pathC;
                    default -> pathD;
                })
                .definitionDatabaseResolver(id -> Optional.of("def-B2".equals(id) ? dbcB2 : shared))
                .readerResolver(path -> {
                    if (path.equals(pathA)) {
                        return Optional.of(countingReader(List.of(frame(1, 1, 0x100, 10L, (byte) 1)), readsA));
                    }
                    if (path.equals(pathB)) {
                        return Optional.of(countingReader(List.of(frame(1, 1, 0x100, 20L, (byte) 2)), readsB));
                    }
                    if (path.equals(pathC)) {
                        return Optional.of(countingReader(List.of(frame(1, 1, 0x100, 30L, (byte) 3)), readsC));
                    }
                    return Optional.of(countingReader(List.of(frame(1, 1, 0x100, 40L, (byte) 4)), readsD));
                })
                .build();
        ProjectPlanExecutor executor = new ProjectPlanExecutor(ctx, registry);
        executor.execute(readyPlan(
                job("A", 1, "def-A", MappingLifecycle.CONFIRMED, 1, true),
                job("B", 1, "def-B", MappingLifecycle.CONFIRMED, 1, true),
                job("C", 1, "def-C", MappingLifecycle.CONFIRMED, 1, true),
                job("D", 1, "def-D", MappingLifecycle.CONFIRMED, 1, true)));
        assertEquals(1, readsA.get());
        assertEquals(1, readsB.get());
        assertEquals(1, readsC.get());
        assertEquals(1, readsD.get());

        executor.execute(readyPlan(
                job("A", 1, "def-A", MappingLifecycle.CONFIRMED, 1, true),
                job("B", 1, "def-B2", MappingLifecycle.CONFIRMED, 1, true),
                job("C", 1, "def-C", MappingLifecycle.CONFIRMED, 1, true),
                job("D", 1, "def-D", MappingLifecycle.CONFIRMED, 1, true)));
        assertEquals(1, readsA.get(), "A must not reparse when only B's definition changes");
        assertEquals(2, readsB.get(), "B must reparse after definition change");
        assertEquals(1, readsC.get(), "C must not reparse when only B's definition changes");
        assertEquals(1, readsD.get(), "D must not reparse when only B's definition changes");
    }

    @Test
    void forceReprocessExplicitlyReadsCompletedSourceAgain() {
        Path path = Path.of("force.asc");
        DbcDatabase dbc = database(0x100, "Msg", "Sig");
        AtomicInteger reads = new AtomicInteger();
        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        registry.publishJob(jobResult("A", 1, JobExecutionStatus.SUCCESS));

        PlanExecutionContext ctx = PlanExecutionContext.builder(
                        new MeasurementDecodePipeline(new ParserRegistry(List.of()), new DefaultDbcDecoder()))
                .measurementPathResolver(id -> path)
                .definitionDatabaseResolver(id -> Optional.of(dbc))
                .readerResolver(p -> Optional.of(countingReader(
                        List.of(frame(1, 1, 0x100, 10L, (byte) 1)), reads)))
                .build();

        new ProjectPlanExecutor(ctx, registry).execute(
                readyPlan(job("A", 1, "def", MappingLifecycle.CONFIRMED, 1, true)),
                PlanJobLifecycleListener.NOOP,
                PlanExecutionPolicy.FORCE_REPROCESS);

        assertEquals(1, reads.get(), "explicit re-run must bypass incremental cache");
    }

    @Test
    void registryDoesNotReplaceSuccessWithFailure() {
        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        IndependentJobResult success = jobResult("A", 1, JobExecutionStatus.SUCCESS);
        IndependentJobResult failure = jobResult("A", 1, JobExecutionStatus.FAILED);
        registry.publishJob(success);
        registry.publishJob(failure);
        assertEquals(JobExecutionStatus.SUCCESS, registry.get(SourceChannelRef.of("A", 1)).orElseThrow().status());
    }

    @Test
    void executorDoesNotInvokeInventoryScanHooks() {
        AtomicInteger inventoryScans = new AtomicInteger();
        Path path = Path.of("noscan.asc");
        DbcDatabase dbc = database(0x10, "M", "S");
        PlanExecutionContext ctx = PlanExecutionContext.builder(
                        new MeasurementDecodePipeline(new ParserRegistry(List.of()), new DefaultDbcDecoder()))
                .measurementPathResolver(id -> path)
                .definitionDatabaseResolver(id -> Optional.of(dbc))
                .readerResolver(p -> Optional.of(reader(List.of(frame(1, 1, 0x10, 1L, (byte) 1)))))
                .build();
        // inventoryScans never incremented by executor
        new ProjectPlanExecutor(ctx).execute(readyPlan(job("A", 1, "def", MappingLifecycle.CONFIRMED, 1, true)));
        assertEquals(0, inventoryScans.get());
    }

    private static IndependentJobResult jobResult(String source, int channel, JobExecutionStatus status) {
        return new IndependentJobResult(
                ProcessingJobKey.of(source, channel),
                "def", "d.dbc", MappingLifecycle.CONFIRMED, status,
                1, 1, 1, 1, 0, 1, 1L, 1L, null, java.time.Duration.ZERO, List.of(), "", null);
    }

    private static ProjectPlanExecutor executor(
            Map<String, Path> sources,
            Map<String, DbcDatabase> defs
    ) {
        return executor(sources, defs, Map.of());
    }

    private static ProjectPlanExecutor executor(
            Map<String, Path> sources,
            Map<String, DbcDatabase> defs,
            Map<Path, AutomotiveFileReader> readers
    ) {
        PlanExecutionContext ctx = PlanExecutionContext.builder(
                        new MeasurementDecodePipeline(new ParserRegistry(List.of()), new DefaultDbcDecoder()))
                .measurementPathResolver(sources::get)
                .definitionDatabaseResolver(id -> Optional.ofNullable(defs.get(id)))
                .readerResolver(path -> Optional.ofNullable(readers.get(path)))
                .build();
        return new ProjectPlanExecutor(ctx);
    }

    private static ProjectProcessingPlan readyPlan(ProcessingJob... jobs) {
        return new ProjectProcessingPlan(
                "plan-" + jobs.length, Instant.now(), List.of(jobs),
                ProcessingPlanReadiness.READY, List.of());
    }

    private static ProcessingJob job(
            String sourceId, int channel, String defId, MappingLifecycle life, long frames, boolean executable
    ) {
        return new ProcessingJob(
                ProcessingJobKey.of(sourceId, channel),
                MeasurementSourceFormat.ASC,
                life,
                defId,
                "test.dbc",
                1, 1, 0,
                frames,
                1.0,
                null,
                executable,
                List.of()
        );
    }

    private static ProcessingJob quickJob(
            String sourceId, int channel, String defId, long frames, long matchedFrames
    ) {
        return new ProcessingJob(
                ProcessingJobKey.of(sourceId, channel),
                MeasurementSourceFormat.ASC,
                MappingLifecycle.RECOMMENDED_FALLBACK,
                defId,
                defId + ".dbc",
                1, 1, 0,
                frames,
                1.0,
                matchedFrames,
                frames == 0 ? 0.0 : matchedFrames / (double) frames,
                null,
                true,
                List.of()
        );
    }

    private static AutomotiveFileReader countingReader(List<CanonicalCanFrame> frames, AtomicInteger reads) {
        AutomotiveFileReader delegate = reader(frames);
        return new AutomotiveFileReader() {
            @Override public String formatKey() { return delegate.formatKey(); }
            @Override public boolean supports(Path path) { return delegate.supports(path); }
            @Override public FileMetadata readMetadata(Path path) throws ParserException {
                return delegate.readMetadata(path);
            }
            @Override
            public void readFrames(Path path, ReaderContext context, CanFrameConsumer frameConsumer,
                                   WarningConsumer warningConsumer) throws ParserException {
                reads.incrementAndGet();
                delegate.readFrames(path, context, frameConsumer, warningConsumer);
            }
        };
    }

    private static AutomotiveFileReader reader(List<CanonicalCanFrame> frames) {
        return new AutomotiveFileReader() {
            @Override public String formatKey() { return "ASC"; }
            @Override public boolean supports(Path path) { return true; }
            @Override public FileMetadata readMetadata(Path path) {
                return new FileMetadata("ASC", path.getFileName().toString(), path.toString());
            }
            @Override
            public void readFrames(Path path, ReaderContext context, CanFrameConsumer frameConsumer,
                                   WarningConsumer warningConsumer) throws ParserException {
                try {
                    frameConsumer.onBatch(frames);
                } catch (ParserException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ParserException("reader failed", e);
                }
            }
        };
    }

    private static DbcDatabase database(long canId, String message, String signal) {
        return new DbcDatabase("", List.of(), List.of(
                new DbcMessage(
                        CanIdKey.fromFrame(canId, false),
                        message,
                        8,
                        "",
                        List.of(new DbcSignal(
                                signal, 0, 8, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,
                                1, 0, 0, 255, "u", List.of(), DbcMultiplexRole.NONE, null, "", DbcValueTable.empty()
                        )),
                        ""
                )
        ), List.of());
    }

    private static CanonicalCanFrame frame(long seq, int channel, long canId, long ts, byte payload) {
        // 8-byte payload matches DbcMessage DLC in fixtures (avoids INSUFFICIENT_PAYLOAD warnings).
        return new CanonicalCanFrame(
                seq, ts, channel, canId, false, FrameDirection.RX, 8,
                new byte[]{payload, 0, 0, 0, 0, 0, 0, 0}, FrameQuality.OK, SourceReference.ofLine(seq)
        );
    }

    private static List<Long> timestamps(IndependentJobResult result) {
        SignalSeriesStore store = (SignalSeriesStore) result.catalogHandle().orElseThrow();
        List<Long> out = new ArrayList<>();
        for (var entry : store.snapshot().entries()) {
            SignalCatalogKey key = entry.key();
            int n = store.retainedSampleCount(key);
            for (SignalSamplePoint p : store.querySamples(key, 0, n)) {
                out.add(p.timestampNanos());
            }
        }
        out.sort(Long::compareTo);
        return out;
    }

    private static String hashSignals(List<CanonicalSignal> signals) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (CanonicalSignal s : signals) {
            md.update(Long.toString(s.timestampNanos()).getBytes(StandardCharsets.UTF_8));
            md.update((byte) '|');
            md.update(Double.toString(s.physicalValue()).getBytes(StandardCharsets.UTF_8));
            md.update((byte) '|');
            md.update(Integer.toString(s.channel()).getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static String hashCatalog(IndependentJobResult result) throws Exception {
        SignalSeriesStore store = (SignalSeriesStore) result.catalogHandle().orElseThrow();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        List<CanonicalSignal> rebuilt = new ArrayList<>();
        // Order by timestamp then channel for stable comparison with direct emission order
        for (var entry : store.snapshot().sortedByName().entries()) {
            SignalCatalogKey key = entry.key();
            for (SignalSamplePoint p : store.querySamples(key, 0, store.retainedSampleCount(key))) {
                md.update(Long.toString(p.timestampNanos()).getBytes(StandardCharsets.UTF_8));
                md.update((byte) '|');
                md.update(Double.toString(p.physicalValue()).getBytes(StandardCharsets.UTF_8));
                md.update((byte) '|');
                md.update(Integer.toString(entry.channel()).getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
        }
        // Prefer same ordering as directSignals: timestamp order
        List<long[]> rows = new ArrayList<>();
        for (var entry : store.snapshot().entries()) {
            for (SignalSamplePoint p : store.querySamples(entry.key(), 0, store.retainedSampleCount(entry.key()))) {
                rows.add(new long[]{p.timestampNanos(), Double.doubleToLongBits(p.physicalValue()), entry.channel()});
            }
        }
        rows.sort((a, b) -> Long.compare(a[0], b[0]));
        MessageDigest md2 = MessageDigest.getInstance("SHA-256");
        for (long[] r : rows) {
            md2.update(Long.toString(r[0]).getBytes(StandardCharsets.UTF_8));
            md2.update((byte) '|');
            md2.update(Double.toString(Double.longBitsToDouble(r[1])).getBytes(StandardCharsets.UTF_8));
            md2.update((byte) '|');
            md2.update(Long.toString(r[2]).getBytes(StandardCharsets.UTF_8));
            md2.update((byte) '\n');
        }
        return HexFormat.of().formatHex(md2.digest());
    }

    private static String hashTimestampList(List<Long> timestamps) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (Long ts : timestamps) {
            md.update(Long.toString(ts).getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static List<Long> timestampsFromSignals(List<CanonicalSignal> signals) {
        List<Long> ts = new ArrayList<>();
        for (CanonicalSignal s : signals) {
            ts.add(s.timestampNanos());
        }
        return ts;
    }
}
