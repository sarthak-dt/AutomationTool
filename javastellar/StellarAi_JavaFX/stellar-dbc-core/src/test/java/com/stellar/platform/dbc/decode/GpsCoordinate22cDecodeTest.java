package com.stellar.platform.dbc.decode;

import com.stellar.platform.dbc.model.DbcByteOrder;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.model.DbcValueType;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.SourceReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Byte-level decode of the Bengaluru TBOX GPS_COORDINATE_22C layout (CAN 0x22C / 556).
 */
class GpsCoordinate22cDecodeTest {

    private static DbcDatabase database;
    private static final DefaultDbcDecoder DECODER = new DefaultDbcDecoder();

    @BeforeAll
    static void load() throws Exception {
        database = new DbcParser().parse(resource("dbc/gps_coordinate_22c.dbc"));
    }

    @Test
    void definitionsMatchTheProductDbc() {
        DbcSignal lat = signal("GPS_LAT_INFO");
        assertEquals(7, lat.startBit());
        assertEquals(32, lat.bitLength());
        assertEquals(DbcByteOrder.MOTOROLA, lat.byteOrder());
        assertEquals(DbcValueType.SIGNED, lat.valueType());
        assertEquals(1e-7, lat.factor(), 1e-15);
        assertEquals(0.0, lat.offset(), 0.0);
        assertEquals("Decimal Degrees", lat.unit());
        assertEquals(DbcMultiplexRole.NONE, lat.multiplexRole());

        DbcSignal lon = signal("GPS_LONG_INFO");
        assertEquals(39, lon.startBit());
        assertEquals(32, lon.bitLength());
        assertEquals(DbcByteOrder.MOTOROLA, lon.byteOrder());
        assertEquals(DbcValueType.SIGNED, lon.valueType());
        assertEquals(1e-7, lon.factor(), 1e-15);
        assertEquals(0.0, lon.offset(), 0.0);
        assertEquals("", lon.unit());
        assertEquals(DbcMultiplexRole.NONE, lon.multiplexRole());
    }

    @Test
    void allZeroPayloadDecodesToZeroDegrees() {
        byte[] payload = new byte[8];
        DecodingResult result = DECODER.decode(frame(556, payload), database);
        Map<String, CanonicalSignal> byName = result.signals().stream()
                .collect(Collectors.toMap(CanonicalSignal::signalName, s -> s));
        CanonicalSignal lat = byName.get("GPS_LAT_INFO");
        CanonicalSignal lon = byName.get("GPS_LONG_INFO");
        assertEquals(0L, lat.rawValue());
        assertEquals(0L, lon.rawValue());
        assertEquals(0.0, lat.physicalValue(), 0.0);
        assertEquals(0.0, lon.physicalValue(), 0.0);
        assertEquals("Decimal Degrees", lat.unit());
        assertEquals("", lon.unit());
    }

    @Test
    void knownBengaluruRawDecodesToDecimalDegrees() {
        byte[] payload = new byte[8];
        SignalBitExtractorTest.writeMotorola(payload, 7, 32, 132089455);
        SignalBitExtractorTest.writeMotorola(payload, 39, 32, 776038656);
        DecodingResult result = DECODER.decode(frame(556, payload), database);
        Map<String, CanonicalSignal> byName = result.signals().stream()
                .collect(Collectors.toMap(CanonicalSignal::signalName, s -> s));
        assertEquals(13.2089455, byName.get("GPS_LAT_INFO").physicalValue(), 1e-7);
        assertEquals(77.6038656, byName.get("GPS_LONG_INFO").physicalValue(), 1e-7);
    }

    private static DbcSignal signal(String name) {
        return database.messages().stream()
                .flatMap(m -> m.signals().stream())
                .filter(s -> name.equals(s.name()))
                .findFirst()
                .orElseThrow();
    }

    private static CanonicalCanFrame frame(long id, byte[] data) {
        return new CanonicalCanFrame(
                0, 39_910_000L, 3, id, false, FrameDirection.RX, data.length, data,
                FrameQuality.OK, SourceReference.ofLine(1)
        );
    }

    private static Path resource(String name) throws URISyntaxException {
        var url = GpsCoordinate22cDecodeTest.class.getClassLoader().getResource(name);
        return Path.of(url.toURI());
    }
}
