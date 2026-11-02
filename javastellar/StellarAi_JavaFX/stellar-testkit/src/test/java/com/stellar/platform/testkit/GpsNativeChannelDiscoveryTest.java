package com.stellar.platform.testkit;

import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.domain.processing.ProcessingSelection;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.NativeMeasurementPolicy;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.ProcessingResult;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.map.GpsLocationSignalIndex;
import com.stellar.platform.processing.map.GpsSignalRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real MF4 blocks through the real parser: a GPS channel group carrying the channels of the
 * verified drive recording, in a channel group whose record id is deliberately unrelated to any
 * CAN bus number. Nothing here declares a CAN id, a DBC message or a channel mapping.
 */
class GpsNativeChannelDiscoveryTest {

    /** Channel-group record id — matches no CAN bus number in the selection below. */
    private static final long GPS_RECORD_ID = 9L;
    private static final int SELECTED_CAN_CHANNEL = 0;

    /** name → unit, in record order after the master time channel. */
    private static final Map<String, String> GPS_CHANNELS = new LinkedHashMap<>();

    static {
        GPS_CHANNELS.put("LATITUDE", "deg");
        GPS_CHANNELS.put("LONGITUDE", "deg");
        GPS_CHANNELS.put("ALTITUDE", "m");
        GPS_CHANNELS.put("SPEED_OVER_GROUND", "km/h");
        GPS_CHANNELS.put("COURSE_OVER_GROUND", "deg");
        GPS_CHANNELS.put("GROUND_DISTANCE", "m");
        GPS_CHANNELS.put("NUMBER_SATELLITES", "");
        GPS_CHANNELS.put("QUALITY", "");
        GPS_CHANNELS.put("HORIZONTAL_ACCURACY", "m");
        GPS_CHANNELS.put("VERTICAL_ACCURACY", "m");
    }

    private static final double[][] SAMPLES = {
            //  lat        lon        alt    sog   cog    dist  sats qual hacc vacc
            {18.556502, 73.715589, 563.80, 0.00, 12.5, 0.00, 11, 1, 1.4, 2.1},
            {18.556612, 73.715701, 563.95, 18.40, 41.2, 16.30, 11, 1, 1.3, 2.0},
            {18.556730, 73.715820, 564.10, 27.10, 43.8, 33.10, 12, 1, 1.2, 1.9},
            {18.556861, 73.715944, 564.22, 31.60, 44.1, 51.40, 12, 1, 1.2, 1.9},
    };

    @TempDir
    Path temp;

    private Path measurement;
    private Path dbc;

    @BeforeEach
    void createMeasurement() throws IOException {
        measurement = temp.resolve("drive_gps.mf4");
        writeGpsMeasurement(measurement);
        dbc = temp.resolve("veh.dbc");
        Files.writeString(dbc, """
                VERSION ""
                NS_ :
                BS_:
                BU_: ECU
                BO_ 256 EngineData: 8 ECU
                 SG_ EngineRPM : 0|16@1+ (0.25,0) [0|8000] "rpm" ECU
                """);
    }

    @Test
    void latitudeAndLongitudeAreDiscoveredWithoutAnyDbcOrCanMapping() throws Exception {
        SignalCatalog catalog = decode();

        SignalCatalogEntry latitude = entry(catalog, "LATITUDE");
        SignalCatalogEntry longitude = entry(catalog, "LONGITUDE");

        assertEquals(SignalSourceType.MF4_NATIVE, latitude.sourceType());
        assertEquals(SignalSourceType.MF4_NATIVE, longitude.sourceType());
        assertEquals(0L, latitude.canId(), "a native channel carries no CAN id");
        assertEquals((int) GPS_RECORD_ID, latitude.channel(), "channel is the MF4 channel-group record id");
    }

    @Test
    void everyGpsChannelOfTheRecordingReachesTheCanonicalCatalogExactlyOnce() throws Exception {
        SignalCatalog catalog = decode();

        for (String name : GPS_CHANNELS.keySet()) {
            assertEquals(1L, catalog.entries().stream().filter(e -> e.signalName().equals(name)).count(),
                    name + " must appear exactly once");
        }
        assertEquals(GPS_CHANNELS.size(), catalog.nativeMf4SignalCount());
    }

    @Test
    void reportedMetadataMatchesTheRecording() throws Exception {
        SignalCatalog catalog = decode();

        SignalCatalogEntry latitude = entry(catalog, "LATITUDE");
        assertEquals("deg", latitude.unit());
        assertEquals(SAMPLES.length, latitude.sampleCount());
        assertEquals(1_000_000_000L, latitude.firstTimestampNanos());
        assertEquals(4_000_000_000L, latitude.lastTimestampNanos());
        assertEquals(18.556502, latitude.minPhysical(), 1e-9);
        assertEquals(18.556861, latitude.maxPhysical(), 1e-9);

        SignalCatalogEntry longitude = entry(catalog, "LONGITUDE");
        assertEquals("deg", longitude.unit());
        assertEquals(SAMPLES.length, longitude.sampleCount());
        assertEquals(73.715589, longitude.minPhysical(), 1e-9);
        assertEquals(73.715944, longitude.maxPhysical(), 1e-9);
    }

    @Test
    void coordinatesKeepTheirOwnTimeSeriesRatherThanBeingMerged() throws Exception {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("drive_gps.mf4", "veh.dbc");
        decodeInto(store);
        SignalCatalog catalog = store.snapshot();

        var latitudeSamples = store.querySamples(entry(catalog, "LATITUDE").key(), 0, 100);
        var longitudeSamples = store.querySamples(entry(catalog, "LONGITUDE").key(), 0, 100);

        assertEquals(SAMPLES.length, latitudeSamples.size());
        assertEquals(SAMPLES.length, longitudeSamples.size());
        for (int i = 0; i < SAMPLES.length; i++) {
            long expected = (i + 1) * 1_000_000_000L;
            assertEquals(expected, latitudeSamples.get(i).timestampNanos());
            assertEquals(expected, longitudeSamples.get(i).timestampNanos());
            assertEquals(SAMPLES[i][0], latitudeSamples.get(i).physicalValue(), 1e-9);
            assertEquals(SAMPLES[i][1], longitudeSamples.get(i).physicalValue(), 1e-9);
        }
    }

    @Test
    void theCatalogClassifiesTheRecordingAsAGpsLocationGroup() throws Exception {
        GpsLocationSignalIndex index = GpsLocationSignalIndex.build(decode().entries());

        assertTrue(index.hasRoute());
        assertEquals(GPS_CHANNELS.size(), index.size());
        assertEquals(Optional.of(GpsSignalRole.LATITUDE), index.roleOf(entry(decode(), "LATITUDE")));
        assertEquals(Optional.of(GpsSignalRole.HORIZONTAL_ACCURACY),
                index.roleOf(entry(decode(), "HORIZONTAL_ACCURACY")));
    }

    @Test
    void switchingToAMeasurementWithoutGpsClearsTheLocationGroup() throws Exception {
        assertTrue(GpsLocationSignalIndex.build(decode().entries()).hasRoute());

        assertTrue(GpsLocationSignalIndex.build(List.of()).isEmpty(),
                "a cleared catalog must not retain the previous file's GPS grouping");
    }

    @Test
    void decodingHappensInASinglePassOverTheMeasurement() throws Exception {
        long before = System.nanoTime();
        ProcessingResult result = decodeInto(new InMemorySignalCatalogStore("drive_gps.mf4", "veh.dbc"));
        long elapsedMillis = (System.nanoTime() - before) / 1_000_000L;

        assertTrue(result.completedSuccessfully(), String.valueOf(result.outcome()));
        assertEquals(SAMPLES.length * (long) GPS_CHANNELS.size(),
                result.statistics().nativeSignalsReceived(),
                "every channel sample is published once, with no second copy");
        assertTrue(elapsedMillis < 5_000L, "decode took " + elapsedMillis + " ms");
    }

    private SignalCatalog decode() throws Exception {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("drive_gps.mf4", "veh.dbc");
        decodeInto(store);
        return store.snapshot();
    }

    private ProcessingResult decodeInto(InMemorySignalCatalogStore store) throws Exception {
        return MeasurementDecodePipeline.createDefault().process(
                measurement,
                new DbcParser().parse(dbc),
                ReaderContext.ofBatchSize(4),
                store::accept,
                ProcessingProgressListener.NOOP,
                ProcessingSelection.forSource(
                        ProcessingSelection.sourceIdentity(measurement), Set.of(SELECTED_CAN_CHANNEL)),
                NativeMeasurementPolicy.INCLUDE);
    }

    private static SignalCatalogEntry entry(SignalCatalog catalog, String signalName) {
        SignalCatalogEntry found = catalog.entries().stream()
                .filter(e -> e.signalName().equals(signalName))
                .findFirst()
                .orElse(null);
        assertNotNull(found, signalName + " missing from the canonical signal catalog");
        return found;
    }

    /** One data group holding a single GPS channel group: master time plus ten float64 channels. */
    private static void writeGpsMeasurement(Path path) throws IOException {
        int channelCount = GPS_CHANNELS.size();
        int recordBytes = 8 + channelCount * 8;

        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(path)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long secondsUnit = b.writeTx("s");
            long timeConversion = b.writeCcLinear(0.0, 1.0, secondsUnit);

            // The CN chain is singly linked, so channels are written back to front.
            long nextCn = 0L;
            List<String> names = List.copyOf(GPS_CHANNELS.keySet());
            for (int i = channelCount - 1; i >= 0; i--) {
                String name = names.get(i);
                long unitTx = GPS_CHANNELS.get(name).isEmpty() ? 0L : b.writeTx(GPS_CHANNELS.get(name));
                nextCn = b.writeCn(
                        nextCn, 0L, b.writeTx(name), unitTx, 0L, 0L,
                        0, 0, 4, 8 + i * 8, 0, 64, 0, 0);
            }
            long firstCn = b.writeCn(
                    nextCn, 0L, b.writeTx("time"), secondsUnit, 0L, timeConversion,
                    2, 1, 4, 0, 0, 64, 0, 0);

            long cg = b.writeCg(0, firstCn, 0L, 0L, GPS_RECORD_ID, SAMPLES.length, 0, recordBytes, 0);

            ByteBuffer records = ByteBuffer.allocate(SAMPLES.length * recordBytes)
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < SAMPLES.length; i++) {
                records.putDouble(i + 1.0);
                for (double value : SAMPLES[i]) {
                    records.putDouble(value);
                }
            }

            long dt = b.writeDt(records.array());
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
    }
}
