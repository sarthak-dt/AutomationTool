package com.stellar.platform.dbc.decode;

import com.stellar.platform.dbc.model.DbcDatabase;
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
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbcDecoderGoldenTest {

    private static DbcDatabase database;
    private static final DefaultDbcDecoder DECODER = new DefaultDbcDecoder();

    @BeforeAll
    static void loadDbc() throws Exception {
        database = new DbcParser().parse(resource("dbc/common_decoder.dbc"));
    }

    @Test
    void decodesStandardIntelUnsignedWithFactor() {
        // EngineSpeed little-endian 0x2710 = 10000 raw -> 2500.0 rpm
        byte[] data = new byte[]{0x10, 0x27, 0, 0, 0, 0, 0, 0};
        CanonicalCanFrame frame = frame(0x100, false, data);
        DecodingResult result = DECODER.decode(frame, database);
        assertEquals(DecodeStatus.DECODED, result.status());
        assertEquals("EngineData", result.matchedMessage().name());
        CanonicalSignal speed = byName(result).get("EngineSpeed");
        assertEquals(10000L, speed.rawValue());
        assertEquals(2500.0, speed.physicalValue(), 1e-9);
        assertEquals("rpm", speed.unit());
    }

    @Test
    void decodesExtendedIdentifierMessage() {
        byte[] data = new byte[]{0x10, 0x27, 0x00, 0x00, 0, 0, 0, 0};
        CanonicalCanFrame frame = frame(0x100, true, data);
        DecodingResult result = DECODER.decode(frame, database);
        assertEquals("ChassisStatus", result.matchedMessage().name());
        assertTrue(result.matchedMessage().extended());
        assertEquals(10000L, byName(result).get("WheelSpeedFL").rawValue());
        assertEquals(100.0, byName(result).get("WheelSpeedFL").physicalValue(), 1e-9);
    }

    @Test
    void decodesIntelSignedTemperature() {
        // EngineTemp signed 8-bit at bit 16: raw 90 -> physical 50
        byte[] data = new byte[]{0, 0, 90, 0, 0, 0, 0, 0};
        DecodingResult result = DECODER.decode(frame(0x100, false, data), database);
        CanonicalSignal temp = byName(result).get("EngineTemp");
        assertEquals(90L, temp.rawValue());
        assertEquals(50.0, temp.physicalValue(), 1e-9);
        assertEquals("degC", temp.unit());
    }

    @Test
    void decodesIntelU12CrossingBytes() {
        byte[] data = new byte[8];
        SignalBitExtractorTest.writeIntel(data, 3, 12, 2748);
        DecodingResult result = DECODER.decode(frame(0x200, false, data), database);
        assertEquals(2748L, byName(result).get("IntelU12").rawValue());
        assertEquals(2748.0, byName(result).get("IntelU12").physicalValue(), 1e-9);
    }

    @Test
    void decodesIntelS12() {
        byte[] data = new byte[8];
        SignalBitExtractorTest.writeIntel(data, 20, 12, 0x800); // -2048
        DecodingResult result = DECODER.decode(frame(0x200, false, data), database);
        assertEquals(-2048L, byName(result).get("IntelS12").rawValue());
    }

    @Test
    void decodesMotorolaUnsignedAndSigned() {
        byte[] data = new byte[8];
        SignalBitExtractorTest.writeMotorola(data, 7, 16, 4660);
        SignalBitExtractorTest.writeMotorola(data, 23, 8, 0x80); // -128
        DecodingResult result = DECODER.decode(frame(0x300, false, data), database);
        assertEquals(4660L, byName(result).get("MotoU16").rawValue());
        assertEquals(-128L, byName(result).get("MotoS8").rawValue());
    }

    @Test
    void appliesFactorAndOffset() {
        // raw 600 -> 0.1*600 - 50 = 10
        byte[] data = new byte[]{(byte) 0x58, 0x02, 0, 0};
        DecodingResult result = DECODER.decode(frame(0x400, false, data), database);
        CanonicalSignal scaled = byName(result).get("Scaled");
        assertEquals(600L, scaled.rawValue());
        assertEquals(10.0, scaled.physicalValue(), 1e-9);
        assertEquals("unit", scaled.unit());
    }

    @Test
    void mapsValueTableText() {
        DecodingResult on = DECODER.decode(frame(0x500, false, new byte[]{1}), database);
        assertEquals("ON", byName(on).get("Switch").textualValue());
        DecodingResult unknown = DECODER.decode(frame(0x500, false, new byte[]{9}), database);
        assertEquals(9L, byName(unknown).get("Switch").rawValue());
        assertEquals(null, byName(unknown).get("Switch").textualValue());
    }

    @Test
    void unmatchedCanIdDoesNotFailHard() {
        DecodingResult result = DECODER.decode(frame(0x123, false, new byte[]{1, 2}), database);
        assertEquals(DecodeStatus.UNMATCHED, result.status());
        assertTrue(result.signals().isEmpty());
        assertFalse(result.diagnostics().isEmpty());
    }

    @Test
    void canFdPayloadBeyondEightBytes() {
        byte[] data = new byte[16];
        data[0] = 0x34;
        data[1] = 0x12; // SensorA = 0x1234
        data[8] = 0x14;
        data[9] = 0x00; // SensorB raw 20 -> 10.0 V
        CanonicalCanFrame frame = new CanonicalCanFrame(
                0, 1_000L, 1, 0x700, false, FrameDirection.RX, 16, data,
                FrameQuality.OK, SourceReference.ofLine(1)
        );
        DecodingResult result = DECODER.decode(frame, database);
        assertEquals(DecodeStatus.DECODED, result.status());
        assertEquals(0x1234L, byName(result).get("SensorA").rawValue());
        assertEquals(10.0, byName(result).get("SensorB").physicalValue(), 1e-9);
        assertEquals("V", byName(result).get("SensorB").unit());
    }

    private static CanonicalCanFrame frame(long id, boolean ext, byte[] data) {
        return new CanonicalCanFrame(
                0, 100_000_000L, 1, id, ext, FrameDirection.RX, data.length, data,
                FrameQuality.OK, SourceReference.ofLine(1)
        );
    }

    private static Map<String, CanonicalSignal> byName(DecodingResult result) {
        Map<String, CanonicalSignal> map = new HashMap<>();
        for (CanonicalSignal s : result.signals()) {
            map.put(s.signalName(), s);
        }
        return map;
    }

    private static Path resource(String name) throws URISyntaxException {
        var url = DbcDecoderGoldenTest.class.getClassLoader().getResource(name);
        return Path.of(url.toURI());
    }
}
