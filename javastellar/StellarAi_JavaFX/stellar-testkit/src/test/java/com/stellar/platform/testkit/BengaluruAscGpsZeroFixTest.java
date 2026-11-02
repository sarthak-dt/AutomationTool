package com.stellar.platform.testkit;

import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.parser.asc.AscFileReader;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.map.GpsSignalRole;
import com.stellar.platform.processing.map.GpsSignalValidator;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.ValidatedRoute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Bengaluru ASC 0x22C frames are all-zero. Decode must stay 0.0 / Decimal Degrees;
 * ValidatedRoute must stay empty; the pair must not be classified as radians.
 */
class BengaluruAscGpsZeroFixTest {

    private static final Path ASC = Path.of(
            "C:\\Users\\ADMIN\\Desktop\\DriveTech\\KALPESH\\blf\\ASC\\"
                    + "ASCII_eCC21_KA04NC7760_Bengaluru_09_08_2023_0002360_20230814_134817.asc");
    private static final Path DBC = Path.of(
            "C:\\Users\\ADMIN\\Desktop\\DriveTech\\Kalpesh2.0\\Files_Prasad_Sangpal\\Generic_DBC\\3.dbc");

    static boolean filesPresent() {
        return Files.isRegularFile(ASC) && Files.isRegularFile(DBC);
    }

    @Test
    @EnabledIf("filesPresent")
    void thirteenZeroFixesAreDegreesNotRadiansAndDoNotBuildARoute() throws Exception {
        DbcDatabase database = new DbcParser().parse(DBC);
        DefaultDbcDecoder decoder = new DefaultDbcDecoder();
        List<CanonicalCanFrame> gps = new ArrayList<>();
        new AscFileReader().readFrames(ASC, ReaderContext.ofBatchSize(256), batch -> {
            for (CanonicalCanFrame frame : batch) {
                if (frame.canId() == 0x22CL) {
                    gps.add(frame);
                }
            }
        }, w -> { });

        assertEquals(13, gps.size());
        List<SignalSamplePoint> lat = new ArrayList<>();
        List<SignalSamplePoint> lon = new ArrayList<>();
        for (CanonicalCanFrame frame : gps) {
            boolean nonzero = false;
            for (int i = 0; i < frame.payloadLength(); i++) {
                nonzero |= frame.payloadByteAt(i) != 0;
            }
            assertFalse(nonzero, "0x22C payload must be all zeros at t=" + frame.timestampNanos());
            var decoded = decoder.decode(frame, database);
            CanonicalSignal latSig = named(decoded.signals(), "GPS_LAT_INFO");
            CanonicalSignal lonSig = named(decoded.signals(), "GPS_LONG_INFO");
            assertEquals(0L, latSig.rawValue());
            assertEquals(0L, lonSig.rawValue());
            assertEquals(0.0, latSig.physicalValue(), 0.0);
            assertEquals(0.0, lonSig.physicalValue(), 0.0);
            assertEquals("Decimal Degrees", latSig.unit());
            lat.add(new SignalSamplePoint(frame.timestampNanos(), latSig.physicalValue(), null, null));
            lon.add(new SignalSamplePoint(frame.timestampNanos(), lonSig.physicalValue(), null, null));
        }

        ValidatedRoute route = MapRouteBuilder.validateAndBuild(lat, lon, null);
        assertTrue(route.isEmpty(), "all-(0,0) must not become a Leaflet route");

        GpsSignalValidator.Result pair = GpsSignalValidator.coordinatePairAdvisory(
                catalog("GPS_LAT_INFO", "Decimal Degrees", 0.0, 0.0, lat.size()),
                catalog("GPS_LONG_INFO", "", 0.0, 0.0, lon.size()));
        assertFalse(pair.message().contains(GpsSignalValidator.RADIAN_ADVISORY), pair.message());
        assertEquals(GpsSignalValidator.ZERO_ZERO_ADVISORY, pair.message());
        assertTrue(GpsSignalValidator.validSample(GpsSignalRole.LATITUDE, 12.97));
        assertTrue(GpsSignalValidator.validSample(GpsSignalRole.LONGITUDE, 77.60));
    }

    private static CanonicalSignal named(List<CanonicalSignal> signals, String name) {
        return signals.stream().filter(s -> name.equals(s.signalName())).findFirst().orElseThrow();
    }

    private static SignalCatalogEntry catalog(String name, String unit, double min, double max, int samples) {
        SignalCatalogKey key = new SignalCatalogKey(name, SignalSourceType.DBC_CAN, "GPS_COORDINATE_22C", 556);
        return new SignalCatalogEntry(key, unit, samples, 0L, 1L, min, max, ASC.getFileName().toString(), "3.dbc");
    }
}
