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
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.CanFrameConsumer;
import com.stellar.platform.parser.spi.NativeSignalCapableReader;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.SignalBatchConsumer;
import com.stellar.platform.parser.spi.WarningConsumer;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.catalog.CompositeSignalCatalogStore;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native GPS channels belong to the measurement, not to a CAN channel. They must reach the
 * catalog regardless of which CAN channels a job decodes, and exactly one job per source may
 * own them so the combined project view never shows the same series twice.
 */
class NativeMeasurementOwnershipTest {

    private static final Path SOURCE = Path.of("drive.mf4");
    private static final int GPS_RECORD_ID = 9;

    @Test
    void gpsChannelsReachTheCatalogWhenOneChannelIsDecoded() {
        MultiSourceProcessingResult batch = execute(new AtomicInteger(), job("A", 1, "def"));

        assertEquals(List.of("LATITUDE", "LONGITUDE"), gpsNames(batch, "A", 1));
    }

    @Test
    void onlyTheOwningChannelStoresGpsWhenOneSourceHasSeveralChannels() {
        MultiSourceProcessingResult batch =
                execute(new AtomicInteger(), job("A", 1, "def"), job("A", 3, "def"));

        assertEquals(List.of("LATITUDE", "LONGITUDE"), gpsNames(batch, "A", 1),
                "lowest executable channel owns the source's native channels");
        assertEquals(List.of(), gpsNames(batch, "A", 3), "a non-owning channel must not copy them");
    }

    @Test
    void ownershipHoldsWhenChannelsUseDifferentDatabasesAndRunAsSeparatePasses() {
        MultiSourceProcessingResult batch =
                execute(new AtomicInteger(), job("A", 4, "def"), job("A", 2, "other"));

        assertEquals(List.of("LATITUDE", "LONGITUDE"), gpsNames(batch, "A", 2));
        assertEquals(List.of(), gpsNames(batch, "A", 4));
    }

    @Test
    void combinedProjectViewExposesEachGpsSignalExactlyOnce() {
        MultiSourceProcessingResult batch =
                execute(new AtomicInteger(), job("A", 1, "def"), job("A", 2, "def"));

        List<CompositeSignalCatalogStore.Member> members = new ArrayList<>();
        for (IndependentJobResult result : batch.jobResults()) {
            members.add(new CompositeSignalCatalogStore.Member(
                    (SignalSeriesStore) result.catalogHandle().orElseThrow(),
                    "A", result.definitionSourceId().orElseThrow(), "drive.mf4", "test.dbc"));
        }

        CompositeSignalCatalogStore composite = assertDoesNotThrow(
                () -> CompositeSignalCatalogStore.fromMembers(members, "drive.mf4", "test.dbc"),
                "duplicated native series would collide in the combined view");
        assertEquals(1L, composite.snapshot().entries().stream()
                .filter(e -> e.signalName().equals("LATITUDE")).count());
        assertEquals(1L, composite.snapshot().entries().stream()
                .filter(e -> e.signalName().equals("LONGITUDE")).count());
    }

    @Test
    void gpsChannelsAreReadInTheSameSinglePassAsCanDecoding() {
        AtomicInteger reads = new AtomicInteger();
        MultiSourceProcessingResult batch =
                execute(reads, job("A", 1, "def"), job("A", 2, "def"));

        assertEquals(1, reads.get(), "publishing native channels must not re-open the measurement");
        assertTrue(batch.successfulJobs() >= 1);
    }

    @Test
    void nativeOnlyNoDbcJobPublishesGpsWithoutADefinition() {
        MultiSourceProcessingResult batch = execute(new AtomicInteger(), nativeOnlyJob("A", 1));

        assertEquals(List.of("LATITUDE", "LONGITUDE"), gpsNames(batch, "A", 1));
        IndependentJobResult result = batch.jobResults().getFirst();
        assertTrue(result.successful());
        assertTrue(result.definitionSourceId().isEmpty());
        assertEquals(MappingLifecycle.NO_MATCH, result.mappingLifecycle());
    }

    private static List<String> gpsNames(MultiSourceProcessingResult batch, String sourceId, int channel) {
        IndependentJobResult result = batch.resultFor(SourceChannelRef.of(sourceId, channel)).orElseThrow();
        SignalSeriesStore store = (SignalSeriesStore) result.catalogHandle().orElseThrow();
        return store.snapshot().entries().stream()
                .filter(e -> e.sourceType() == SignalSourceType.MF4_NATIVE)
                .map(SignalCatalogEntry::signalName)
                .sorted()
                .toList();
    }

    private static MultiSourceProcessingResult execute(AtomicInteger reads, ProcessingJob... jobs) {
        DbcDatabase dbc = database();
        PlanExecutionContext ctx = PlanExecutionContext.builder(
                        new MeasurementDecodePipeline(new ParserRegistry(List.of()), new DefaultDbcDecoder()))
                .measurementPathResolver(id -> SOURCE)
                .definitionDatabaseResolver(id -> Optional.of(dbc))
                .readerResolver(path -> Optional.of(new GpsAndCanReader(reads)))
                .build();
        return new ProjectPlanExecutor(ctx).execute(new ProjectProcessingPlan(
                "plan", Instant.now(), List.of(jobs), ProcessingPlanReadiness.READY, List.of()));
    }

    private static ProcessingJob job(String sourceId, int channel, String definitionId) {
        return new ProcessingJob(
                ProcessingJobKey.of(sourceId, channel),
                MeasurementSourceFormat.MF4,
                MappingLifecycle.CONFIRMED,
                definitionId,
                "test.dbc",
                1, 1, 0,
                1L,
                1.0,
                null,
                true,
                List.of());
    }

    private static ProcessingJob nativeOnlyJob(String sourceId, int channel) {
        return new ProcessingJob(
                ProcessingJobKey.of(sourceId, channel),
                MeasurementSourceFormat.MF4,
                MappingLifecycle.NO_MATCH,
                null,
                "",
                1, 0, 1,
                1L,
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
                        1, 0, 0, 255, "rpm", List.of(), DbcMultiplexRole.NONE, null, "",
                        DbcValueTable.empty())),
                "")), List.of());
    }

    /** One CAN frame on each of buses 1..4 plus a GPS group whose record id matches no bus. */
    private record GpsAndCanReader(AtomicInteger reads) implements NativeSignalCapableReader {

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
            try {
                List<CanonicalCanFrame> frames = new ArrayList<>();
                for (int bus = 1; bus <= 4; bus++) {
                    frames.add(new CanonicalCanFrame(
                            bus, 1_000_000_000L, bus, 0x100L, false, FrameDirection.RX, 8,
                            new byte[] {(byte) bus, 0, 0, 0, 0, 0, 0, 0},
                            FrameQuality.OK, SourceReference.ofLine(bus)));
                }
                frameConsumer.onBatch(frames);
                nativeSignalConsumer.onBatch(List.of(
                        gps("LATITUDE", 18.556502),
                        gps("LONGITUDE", 73.715589)));
            } catch (ParserException e) {
                throw e;
            } catch (Exception e) {
                throw new ParserException("reader failed", e);
            }
        }

        private static CanonicalMeasurementSample gps(String name, double value) {
            return new CanonicalMeasurementSample(
                    name, 1_000_000_000L, GPS_RECORD_ID, value, null, "deg", null,
                    new SourceReference(1, 0));
        }
    }
}
