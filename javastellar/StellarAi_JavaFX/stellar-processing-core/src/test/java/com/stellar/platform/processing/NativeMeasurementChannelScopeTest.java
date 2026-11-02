package com.stellar.platform.processing;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalMeasurementSample;
import com.stellar.platform.domain.FileMetadata;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.domain.processing.ProcessingSelection;
import com.stellar.platform.parser.spi.CanFrameConsumer;
import com.stellar.platform.parser.spi.NativeSignalCapableReader;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.SignalBatchConsumer;
import com.stellar.platform.parser.spi.WarningConsumer;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A native MF4 measurement channel carries the channel-group record id, not a CAN bus number.
 * Scoping it with the CAN {@link ProcessingSelection} dropped GPS channels whenever the two
 * unrelated id spaces happened not to coincide.
 */
class NativeMeasurementChannelScopeTest {

    /** Record id of the GPS channel group — deliberately unrelated to any CAN bus number. */
    private static final int GPS_RECORD_ID = 7;
    private static final int CAN_BUS_CHANNEL = 0;

    @TempDir
    Path temp;

    @Test
    void gpsChannelsSurviveAProcessingSelectionThatNamesADifferentCanChannel() throws Exception {
        Harness harness = new Harness();
        SignalCatalog snapshot = harness.run(ProcessingSelection.forSource(
                ProcessingSelection.sourceIdentity(harness.measurement), Set.of(CAN_BUS_CHANNEL)));

        assertTrue(nameOf(snapshot).contains("LATITUDE"), "LATITUDE must reach the catalog");
        assertTrue(nameOf(snapshot).contains("LONGITUDE"), "LONGITUDE must reach the catalog");
    }

    @Test
    void gpsChannelsDoNotRequireACanChannelMappingAtAll() throws Exception {
        Harness harness = new Harness();
        SignalCatalog snapshot = harness.run(ProcessingSelection.none());

        assertTrue(nameOf(snapshot).contains("LATITUDE"));
        assertTrue(nameOf(snapshot).contains("LONGITUDE"));
        assertEquals(SignalSourceType.MF4_NATIVE, snapshot.entries().stream()
                .filter(e -> e.signalName().equals("LATITUDE"))
                .findFirst().orElseThrow().sourceType());
    }

    @Test
    void latitudeAndLongitudeKeepTheirOwnTimestampsAndSampleCounts() throws Exception {
        Harness harness = new Harness();
        SignalCatalog snapshot = harness.run(ProcessingSelection.forSource(
                ProcessingSelection.sourceIdentity(harness.measurement), Set.of(CAN_BUS_CHANNEL)));

        var latitude = snapshot.entries().stream()
                .filter(e -> e.signalName().equals("LATITUDE")).findFirst().orElseThrow();
        var longitude = snapshot.entries().stream()
                .filter(e -> e.signalName().equals("LONGITUDE")).findFirst().orElseThrow();

        assertEquals(3L, latitude.sampleCount());
        assertEquals(3L, longitude.sampleCount());
        assertEquals(1_000_000_000L, latitude.firstTimestampNanos());
        assertEquals(3_000_000_000L, latitude.lastTimestampNanos());

        // Different rates must not be merged or resampled at this layer.
        assertEquals(2L, snapshot.entries().stream()
                .filter(e -> e.signalName().equals("ALTITUDE")).findFirst().orElseThrow().sampleCount());
    }

    @Test
    void gpsChannelsAreEmittedWhenTheDbcDatabaseHasNoMessages() throws Exception {
        Harness harness = new Harness();
        SignalCatalog snapshot = harness.runEmptyDbc(ProcessingSelection.forSource(
                ProcessingSelection.sourceIdentity(harness.measurement), Set.of(CAN_BUS_CHANNEL)));

        assertTrue(nameOf(snapshot).contains("LATITUDE"));
        assertTrue(nameOf(snapshot).contains("LONGITUDE"));
        assertEquals(0L, snapshot.dbcCanSignalCount());
        assertEquals(SignalSourceType.MF4_NATIVE, snapshot.entries().stream()
                .filter(e -> e.signalName().equals("LATITUDE"))
                .findFirst().orElseThrow().sourceType());
    }

    @Test
    void excludingNativeMeasurementsLeavesCanDecodingUntouched() throws Exception {
        Harness harness = new Harness();
        SignalCatalog snapshot = harness.run(
                ProcessingSelection.forSource(
                        ProcessingSelection.sourceIdentity(harness.measurement), Set.of(CAN_BUS_CHANNEL)),
                NativeMeasurementPolicy.EXCLUDE);

        assertEquals(0L, snapshot.nativeMf4SignalCount(), "owner-scoped pass must emit no native signals");
        assertTrue(snapshot.dbcCanSignalCount() >= 1L, "CAN/DBC decoding must be unaffected");
    }

    private static List<String> nameOf(SignalCatalog catalog) {
        return catalog.entries().stream().map(e -> e.signalName()).toList();
    }

    private final class Harness {
        private final Path measurement;
        private final DbcDatabase database;

        private Harness() throws Exception {
            measurement = temp.resolve("gps.mf4");
            Files.writeString(measurement, "placeholder");
            Path dbcPath = temp.resolve("veh.dbc");
            Files.writeString(dbcPath, """
                    VERSION ""
                    NS_ :
                    BS_:
                    BU_: ECU
                    BO_ 256 EngineData: 8 ECU
                     SG_ EngineRPM : 0|16@1+ (0.25,0) [0|8000] "rpm" ECU
                    """);
            database = new DbcParser().parse(dbcPath);
        }

        private SignalCatalog run(ProcessingSelection selection) throws Exception {
            return run(selection, NativeMeasurementPolicy.INCLUDE);
        }

        private SignalCatalog run(ProcessingSelection selection, NativeMeasurementPolicy policy)
                throws Exception {
            InMemorySignalCatalogStore catalog =
                    new InMemorySignalCatalogStore("gps.mf4", "veh.dbc");
            new MeasurementDecodePipeline(new ParserRegistry(List.of())).process(
                    new GpsMeasurementReader(),
                    measurement,
                    database,
                    ReaderContext.ofBatchSize(8),
                    catalog::accept,
                    ProcessingProgressListener.NOOP,
                    selection,
                    policy
            );
            return catalog.snapshot();
        }

        private SignalCatalog runEmptyDbc(ProcessingSelection selection) throws Exception {
            InMemorySignalCatalogStore catalog =
                    new InMemorySignalCatalogStore("gps.mf4", "");
            new MeasurementDecodePipeline(new ParserRegistry(List.of())).process(
                    new GpsMeasurementReader(),
                    measurement,
                    new DbcDatabase("", List.of(), List.of(), List.of()),
                    ReaderContext.ofBatchSize(8),
                    catalog::accept,
                    ProcessingProgressListener.NOOP,
                    selection,
                    NativeMeasurementPolicy.INCLUDE
            );
            return catalog.snapshot();
        }
    }

    /** Emits one CAN group on bus 0 plus a GPS measurement group with record id 7. */
    private static final class GpsMeasurementReader implements NativeSignalCapableReader {

        @Override public String formatKey() {
            return "mf4";
        }

        @Override public boolean supports(Path path) {
            return true;
        }

        @Override public FileMetadata readMetadata(Path path) {
            return new FileMetadata("mf4", path.getFileName().toString(), path.toString());
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
            try {
                frameConsumer.onBatch(List.of(new CanonicalCanFrame(
                        1L, 1_000_000_000L, CAN_BUS_CHANNEL, 0x100L, false, FrameDirection.RX,
                        8, new byte[] {0x00, 0x10, 0, 0, 0, 0, 0, 0},
                        FrameQuality.VALID, new SourceReference(1, 0))));
                nativeSignalConsumer.onBatch(List.of(
                        gps("LATITUDE", 1_000_000_000L, 18.556502, "deg"),
                        gps("LONGITUDE", 1_000_000_000L, 73.715589, "deg"),
                        gps("ALTITUDE", 1_000_000_000L, 563.89, "m"),
                        gps("SPEED_OVER_GROUND", 1_000_000_000L, 42.3, "km/h"),
                        gps("NUMBER_SATELLITES", 1_000_000_000L, 11, "count"),
                        gps("LATITUDE", 2_000_000_000L, 18.556612, "deg"),
                        gps("LONGITUDE", 2_000_000_000L, 73.715701, "deg"),
                        gps("ALTITUDE", 2_000_000_000L, 564.10, "m"),
                        gps("LATITUDE", 3_000_000_000L, 18.556730, "deg"),
                        gps("LONGITUDE", 3_000_000_000L, 73.715820, "deg")
                ));
            } catch (ParserException e) {
                throw e;
            } catch (Exception e) {
                throw new ParserException("gps reader failed", e);
            }
        }

        private static CanonicalMeasurementSample gps(
                String name, long timestampNanos, double value, String unit) {
            return new CanonicalMeasurementSample(
                    name, timestampNanos, GPS_RECORD_ID, value, null, unit, null,
                    new SourceReference(1, timestampNanos));
        }
    }
}
