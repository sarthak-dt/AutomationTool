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
import com.stellar.platform.domain.CanonicalMeasurementSample;
import com.stellar.platform.domain.FileMetadata;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.ProcessingWarning;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnosticCode;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.parser.spi.CanFrameConsumer;
import com.stellar.platform.parser.spi.NativeSignalCapableReader;
import com.stellar.platform.parser.spi.ParseCheckpointListener;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ParserResumeCursor;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.SignalBatchConsumer;
import com.stellar.platform.parser.spi.SimpleCancellationToken;
import com.stellar.platform.parser.spi.WarningConsumer;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A DBC channel and an Auto Decode (no-DBC) channel of the same source share one physical scan
 * while every job result, diagnostic and stored sample stays identical to separate per-job passes.
 */
class SharedSourceScanTest {

    private static final Path SOURCE = Path.of("drive.mf4");
    private static final int GPS_RECORD_ID = 9;

    @Test
    void dbcChannelAndNoDbcChannelAreScannedOnce() {
        AtomicInteger reads = new AtomicInteger();
        MultiSourceProcessingResult batch = execute(reads, false, JobResumeSupport.NONE,
                dbcJob(1, 4), nativeOnlyJob(2, 4));

        assertEquals(1, reads.get(), "same source must be scanned once");
        assertEquals(2, batch.jobResults().size());
        assertTrue(batch.jobResults().stream().allMatch(IndependentJobResult::successful));
    }

    @Test
    void sharedScanResultsMatchSeparatePerJobPassesExactly() throws Exception {
        AtomicInteger separateReads = new AtomicInteger();
        IndependentJobResult separateCh1 = execute(separateReads, false, JobResumeSupport.NONE, dbcJob(1, 4))
                .jobResults().getFirst();
        IndependentJobResult separateCh2 = execute(separateReads, false, JobResumeSupport.NONE, nativeOnlyJob(2, 4))
                .jobResults().getFirst();
        assertEquals(2, separateReads.get());

        AtomicInteger sharedReads = new AtomicInteger();
        MultiSourceProcessingResult shared = execute(sharedReads, false, JobResumeSupport.NONE,
                dbcJob(1, 4), nativeOnlyJob(2, 4));
        assertEquals(1, sharedReads.get());

        assertSameResult(separateCh1, shared.resultFor(SourceChannelRef.of("A", 1)).orElseThrow());
        assertSameResult(separateCh2, shared.resultFor(SourceChannelRef.of("A", 2)).orElseThrow());
    }

    @Test
    void frameCountMismatchWarningIsPreservedPerJob() {
        MultiSourceProcessingResult batch = execute(new AtomicInteger(), false, JobResumeSupport.NONE,
                dbcJob(1, 99), nativeOnlyJob(2, 4));

        IndependentJobResult ch1 = batch.resultFor(SourceChannelRef.of("A", 1)).orElseThrow();
        IndependentJobResult ch2 = batch.resultFor(SourceChannelRef.of("A", 2)).orElseThrow();
        assertTrue(ch1.diagnostics().stream()
                .anyMatch(d -> d.code() == ProcessingPlanDiagnosticCode.FRAME_COUNT_MISMATCH));
        assertTrue(ch2.diagnostics().stream()
                .noneMatch(d -> d.code() == ProcessingPlanDiagnosticCode.FRAME_COUNT_MISMATCH));
        assertEquals(4L, ch1.selectedChannelFrames());
        assertEquals(4L, ch2.selectedChannelFrames());
    }

    @Test
    void nativeChannelsStayOwnedByLowestExecutableChannel() {
        AtomicInteger reads = new AtomicInteger();
        MultiSourceProcessingResult batch = execute(reads, true, JobResumeSupport.NONE,
                dbcJob(1, 4), nativeOnlyJob(2, 4));

        assertEquals(1, reads.get());
        assertEquals(List.of("LATITUDE", "LONGITUDE"), nativeNames(batch, 1));
        assertEquals(List.of(), nativeNames(batch, 2));
    }

    @Test
    void everyJobReceivesItsOwnSafeCheckpoint() {
        List<String> checkpoints = new CopyOnWriteArrayList<>();
        JobResumeSupport resume = new JobResumeSupport() {
            @Override
            public ParseCheckpointListener listenerFor(ProcessingJob job) {
                return (cursor, records, ts) -> checkpoints.add(job.key() + "@" + records);
            }
        };
        AtomicInteger reads = new AtomicInteger();
        execute(reads, false, resume, dbcJob(1, 4), nativeOnlyJob(2, 4));

        assertEquals(1, reads.get());
        assertEquals(2, checkpoints.size());
        assertTrue(checkpoints.contains(ProcessingJobKey.of("A", 1) + "@8"));
        assertTrue(checkpoints.contains(ProcessingJobKey.of("A", 2) + "@8"));
    }

    @Test
    void pendingResumeCursorKeepsPerJobResume() {
        JobResumeSupport resume = new JobResumeSupport() {
            @Override
            public Optional<ParserResumeCursor> cursorFor(ProcessingJob job) {
                return job.channelId() == 1 ? Optional.of(ParserResumeCursor.mf4("x")) : Optional.empty();
            }
        };
        AtomicInteger reads = new AtomicInteger();
        execute(reads, false, resume, dbcJob(1, 4), nativeOnlyJob(2, 4));

        assertEquals(2, reads.get(), "a job resuming mid-file must keep its own cursor");
    }

    @Test
    void cancellationDuringSharedScanCancelsEveryJobLikeSeparatePasses() {
        SimpleCancellationToken sharedToken = new SimpleCancellationToken();
        AtomicInteger sharedReads = new AtomicInteger();
        MultiSourceProcessingResult shared = execute(sharedReads, false, JobResumeSupport.NONE, sharedToken,
                dbcJob(1, 4), nativeOnlyJob(2, 4));
        assertEquals(1, sharedReads.get());

        IndependentJobResult separateCh1 = execute(new AtomicInteger(), false, JobResumeSupport.NONE,
                new SimpleCancellationToken(), dbcJob(1, 4)).jobResults().getFirst();
        IndependentJobResult separateCh2 = execute(new AtomicInteger(), false, JobResumeSupport.NONE,
                new SimpleCancellationToken(), nativeOnlyJob(2, 4)).jobResults().getFirst();

        IndependentJobResult sharedCh1 = shared.resultFor(SourceChannelRef.of("A", 1)).orElseThrow();
        IndependentJobResult sharedCh2 = shared.resultFor(SourceChannelRef.of("A", 2)).orElseThrow();
        assertFalse(sharedCh1.successful());
        assertFalse(sharedCh2.successful());
        assertEquals(separateCh1.status(), sharedCh1.status());
        assertEquals(separateCh2.status(), sharedCh2.status());
        assertEquals(0L, sharedCh1.signalsProduced());
        assertEquals(0L, sharedCh2.signalsProduced());
    }

    private static void assertSameResult(IndependentJobResult expected, IndependentJobResult actual) throws Exception {
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.definitionSourceId(), actual.definitionSourceId());
        assertEquals(expected.definitionDisplayName(), actual.definitionDisplayName());
        assertEquals(expected.mappingLifecycle(), actual.mappingLifecycle());
        assertEquals(expected.sourceFramesRead(), actual.sourceFramesRead());
        assertEquals(expected.selectedChannelFrames(), actual.selectedChannelFrames());
        assertEquals(expected.dbcMatchedFrames(), actual.dbcMatchedFrames());
        assertEquals(expected.dbcUnmatchedFrames(), actual.dbcUnmatchedFrames());
        assertEquals(expected.signalsProduced(), actual.signalsProduced());
        assertEquals(expected.firstSampleTimestampNanos(), actual.firstSampleTimestampNanos());
        assertEquals(expected.lastSampleTimestampNanos(), actual.lastSampleTimestampNanos());
        assertEquals(diagnosticDump(expected), diagnosticDump(actual));
        assertEquals(expected.technicalDetail(), actual.technicalDetail());
        assertEquals(catalogDump(expected), catalogDump(actual));
    }

    private static List<String> diagnosticDump(IndependentJobResult result) {
        return result.diagnostics().stream()
                .map(d -> d.code() + "|" + d.channel().map(Object::toString).orElse("") + "|" + d.detail())
                .toList();
    }

    private static List<String> catalogDump(IndependentJobResult result) {
        SignalSeriesStore store = (SignalSeriesStore) result.catalogHandle().orElseThrow();
        List<String> rows = new ArrayList<>();
        for (SignalCatalogEntry entry : store.snapshot().sortedByName().entries()) {
            rows.add(entry.signalName() + "|" + entry.unit() + "|" + entry.sampleCount() + "|"
                    + entry.minPhysical() + "|" + entry.maxPhysical());
            for (SignalSamplePoint p : store.querySamples(entry.key(), 0, store.retainedSampleCount(entry.key()))) {
                rows.add("  " + p.timestampNanos() + "=" + Double.doubleToLongBits(p.physicalValue()));
            }
        }
        return rows;
    }

    private static List<String> nativeNames(MultiSourceProcessingResult batch, int channel) {
        IndependentJobResult result = batch.resultFor(SourceChannelRef.of("A", channel)).orElseThrow();
        SignalSeriesStore store = (SignalSeriesStore) result.catalogHandle().orElseThrow();
        return store.snapshot().entries().stream()
                .filter(e -> e.sourceType() == SignalSourceType.MF4_NATIVE)
                .map(SignalCatalogEntry::signalName)
                .sorted()
                .toList();
    }

    private static MultiSourceProcessingResult execute(
            AtomicInteger reads, boolean withNative, JobResumeSupport resume, ProcessingJob... jobs) {
        return execute(reads, withNative, resume, null, jobs);
    }

    /** A non-null {@code cancelOnRead} token is cancelled as the reader starts, before any frame. */
    private static MultiSourceProcessingResult execute(
            AtomicInteger reads,
            boolean withNative,
            JobResumeSupport resume,
            SimpleCancellationToken cancelOnRead,
            ProcessingJob... jobs) {
        DbcDatabase dbc = database();
        PlanExecutionContext.Builder builder = PlanExecutionContext.builder(
                        new MeasurementDecodePipeline(new ParserRegistry(List.of()), new DefaultDbcDecoder()))
                .measurementPathResolver(id -> SOURCE)
                .definitionDatabaseResolver(id -> Optional.of(dbc))
                .readerResolver(path -> Optional.of(new TwoBusReader(reads, withNative, cancelOnRead)))
                .resumeSupport(resume);
        if (cancelOnRead != null) {
            builder.cancellationToken(cancelOnRead);
        }
        PlanExecutionContext ctx = builder.build();
        return new ProjectPlanExecutor(ctx).execute(new ProjectProcessingPlan(
                "plan", Instant.now(), List.of(jobs), ProcessingPlanReadiness.READY, List.of()));
    }

    private static ProcessingJob dbcJob(int channel, long frames) {
        return new ProcessingJob(
                ProcessingJobKey.of("A", channel),
                MeasurementSourceFormat.MF4,
                MappingLifecycle.RECOMMENDED_FALLBACK,
                "def",
                "test.dbc",
                2, 1, 1,
                frames,
                0.5,
                null,
                true,
                List.of());
    }

    private static ProcessingJob nativeOnlyJob(int channel, long frames) {
        return new ProcessingJob(
                ProcessingJobKey.of("A", channel),
                MeasurementSourceFormat.MF4,
                MappingLifecycle.NO_MATCH,
                null,
                "",
                1, 0, 1,
                frames,
                0.0,
                null,
                true,
                List.of());
    }

    private static DbcDatabase database() {
        return new DbcDatabase("", List.of(), List.of(new DbcMessage(
                CanIdKey.fromFrame(0x100L, false), "EngineData", 8, "",
                List.of(new DbcSignal(
                        "EngineRPM", 0, 8, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,
                        2, 10, 0, 1000, "rpm", List.of(), DbcMultiplexRole.NONE, null, "",
                        DbcValueTable.empty())),
                "")), List.of());
    }

    /**
     * Bus 1 carries a DBC-known and an unknown id; bus 2 carries ids no DBC covers. Emits a
     * recovery warning and one safe checkpoint, like the MF4 recovery reader.
     */
    private record TwoBusReader(AtomicInteger reads, boolean withNative, SimpleCancellationToken cancelOnRead)
            implements NativeSignalCapableReader {

        @Override public String formatKey() {
            return "mf4";
        }

        @Override public boolean supports(Path path) {
            return true;
        }

        @Override public FileMetadata readMetadata(Path path) {
            return new FileMetadata("mf4", "drive.mf4", path.toString());
        }

        @Override public void readFrames(
                Path path, ReaderContext context, CanFrameConsumer frames, WarningConsumer warnings
        ) throws ParserException {
            readFramesAndSignals(path, context, frames, SignalBatchConsumer.NOOP, warnings);
        }

        @Override public void readFramesAndSignals(
                Path path,
                ReaderContext context,
                CanFrameConsumer frameConsumer,
                SignalBatchConsumer nativeSignalConsumer,
                WarningConsumer warningConsumer
        ) throws ParserException {
            reads.incrementAndGet();
            if (cancelOnRead != null) {
                cancelOnRead.cancel();
            }
            try {
                warningConsumer.onWarning(new ProcessingWarning(
                        "RECOVERY_REQUIRED", "Recovery required for DG (not silent zero)",
                        new SourceReference(-1L, -1L)));
                List<CanonicalCanFrame> frames = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    long ts = 1_000_000_000L + i * 10_000_000L;
                    frames.add(frame(i * 2L, ts, 1, i % 2 == 0 ? 0x100L : 0x200L, (byte) (i * 7)));
                    frames.add(frame(i * 2L + 1, ts + 1, 2, i % 2 == 0 ? 0x300L : 0x301L, (byte) i));
                }
                frameConsumer.onBatch(frames);
                if (withNative) {
                    nativeSignalConsumer.onBatch(List.of(
                            gps("LATITUDE", 18.556502),
                            gps("LONGITUDE", 73.715589)));
                }
                context.checkpointListener().onSafeCheckpoint(ParserResumeCursor.mf4("end"), 8L, 1_030_000_001L);
            } catch (ParserException e) {
                throw e;
            } catch (Exception e) {
                throw new ParserException("reader failed", e);
            }
        }

        private static CanonicalCanFrame frame(long seq, long ts, int bus, long canId, byte b0) {
            return new CanonicalCanFrame(
                    seq, ts, bus, canId, false, FrameDirection.RX, 8,
                    new byte[] {b0, 0, 0, 0, 0, 0, 0, 0},
                    FrameQuality.RECOVERED, SourceReference.ofLine(seq));
        }

        private static CanonicalMeasurementSample gps(String name, double value) {
            return new CanonicalMeasurementSample(
                    name, 1_000_000_000L, GPS_RECORD_ID, value, null, "deg", null,
                    new SourceReference(1, 0));
        }
    }
}
