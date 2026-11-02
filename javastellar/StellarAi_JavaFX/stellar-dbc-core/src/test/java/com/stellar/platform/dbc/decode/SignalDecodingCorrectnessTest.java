package com.stellar.platform.dbc.decode;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcRawEncoding;
import com.stellar.platform.dbc.parse.DbcDiagnosticCode;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.SourceReference;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Expected physical values are computed from the payload bits and the SG_ definition
 * in this test, not by calling the decoder twice.
 */
class SignalDecodingCorrectnessTest {

    private static final DefaultDbcDecoder DECODER = new DefaultDbcDecoder();

    @Test
    void integerLayoutsUseFactorAndOffsetAndDoNotTreatStartValueAsInvalid() throws Exception {
        DbcDatabase db = parse("""
                VERSION ""
                BU_: ECU
                BO_ 256 Powertrain: 8 ECU
                 SG_ Coolant : 0|8@1- (1,-40) [-40|215] "degC" ECU
                 SG_ VehicleSpeed : 7|16@0+ (0.01,0) [0|655.34] "km/h" ECU
                 SG_ EngineRpm : 16|16@1+ (0.25,0) [0|16383.75] "rpm" ECU
                 SG_ RailPressure : 39|16@0+ (0.1,0) [0|6553.5] "kPa" ECU
                 SG_ BatteryVoltage : 40|8@1+ (0.1,0) [0|25.5] "V" ECU
                 SG_ PackCurrent : 48|16@1- (0.01,0) [-327.68|327.67] "A" ECU
                 SG_ AxleTorque : 56|8@1+ (2,-100) [-100|410] "Nm" ECU
                BA_DEF_ SG_ "GenSigStartValue" FLOAT 0 0;
                BA_DEF_ SG_ "GenSigInvalidValue" HEX 0 0;
                BA_DEF_ SG_ "GenSigInactiveValue" INT 0 0;
                BA_DEF_DEF_ "GenSigInvalidValue" 0;
                BA_ "GenSigStartValue" SG_ 256 Coolant 0;
                BA_ "GenSigInvalidValue" SG_ 256 VehicleSpeed 65535;
                BA_ "GenSigInactiveValue" SG_ 256 VehicleSpeed 65534;
                """);

        assertEquals(0L, signal(db, "Coolant").startRawValue());
        assertNull(signal(db, "Coolant").invalidRawValue());

        byte[] cold = new byte[8];
        cold[0] = 0;
        CanonicalSignal coolantZero = one(decode(db, 256, cold), "Coolant");
        assertEquals(0L, coolantZero.rawValue());
        assertEquals(0.0 * 1.0 + -40.0, coolantZero.physicalValue(), 0.0);
        assertFalse(coolantZero.valueInvalid());
        assertEquals("degC", coolantZero.unit());

        byte[] hot = new byte[8];
        hot[0] = 90;
        CanonicalSignal coolant = one(decode(db, 256, hot), "Coolant");
        assertEquals(90L, coolant.rawValue());
        assertEquals(90.0 - 40.0, coolant.physicalValue(), 0.0);
        assertFalse(coolant.valueInvalid());

        byte[] minSigned = new byte[8];
        minSigned[0] = (byte) 0x80;
        CanonicalSignal coolantMin = one(decode(db, 256, minSigned), "Coolant");
        assertEquals(-128L, coolantMin.rawValue());
        assertEquals(-128.0 - 40.0, coolantMin.physicalValue(), 0.0);

        byte[] speed = new byte[8];
        SignalBitExtractorTest.writeMotorola(speed, 7, 16, 12_000L);
        CanonicalSignal vehicle = one(decode(db, 256, speed), "VehicleSpeed");
        assertEquals(12_000L, vehicle.rawValue());
        assertEquals(12_000.0 * 0.01, vehicle.physicalValue(), 1e-12);
        assertFalse(vehicle.valueInvalid());
        assertEquals(SignalValidity.VALID, vehicle.validity());
        assertEquals("km/h", vehicle.unit());

        byte[] invalidSpeed = new byte[8];
        SignalBitExtractorTest.writeMotorola(invalidSpeed, 7, 16, 65_535L);
        DecodingResult invalid = decode(db, 256, invalidSpeed);
        CanonicalSignal invalidVehicle = one(invalid, "VehicleSpeed");
        assertEquals(65_535L, invalidVehicle.rawValue());
        assertEquals(65_535.0 * 0.01, invalidVehicle.physicalValue(), 1e-12);
        assertNotEquals(0.0, invalidVehicle.physicalValue(), 0.0);
        assertTrue(invalidVehicle.valueInvalid());
        assertEquals(SignalValidity.INVALID_RAW_CODE, invalidVehicle.validity());
        assertEquals("invalid", invalidVehicle.textualValue());
        assertTrue(invalid.diagnostics().stream().anyMatch(d -> d.code() == DbcDiagnosticCode.INVALID_RAW_VALUE));

        byte[] inactiveSpeed = new byte[8];
        SignalBitExtractorTest.writeMotorola(inactiveSpeed, 7, 16, 65_534L);
        CanonicalSignal inactiveVehicle = one(decode(db, 256, inactiveSpeed), "VehicleSpeed");
        assertEquals(65_534L, inactiveVehicle.rawValue());
        assertEquals(65_534.0 * 0.01, inactiveVehicle.physicalValue(), 1e-9);
        assertTrue(inactiveVehicle.valueInvalid());
        assertEquals(SignalValidity.INACTIVE_RAW_CODE, inactiveVehicle.validity());
        assertEquals("inactive", inactiveVehicle.textualValue());

        byte[] rpm = new byte[8];
        SignalBitExtractorTest.writeIntel(rpm, 16, 16, 10_000L);
        CanonicalSignal engine = one(decode(db, 256, rpm), "EngineRpm");
        assertEquals(10_000.0 * 0.25, engine.physicalValue(), 1e-12);
        assertEquals("rpm", engine.unit());

        byte[] pressure = new byte[8];
        SignalBitExtractorTest.writeMotorola(pressure, 39, 16, 2_500L);
        assertEquals(2_500.0 * 0.1, one(decode(db, 256, pressure), "RailPressure").physicalValue(), 1e-12);

        byte[] voltage = new byte[8];
        SignalBitExtractorTest.writeIntel(voltage, 40, 8, 126L);
        assertEquals(126.0 * 0.1, one(decode(db, 256, voltage), "BatteryVoltage").physicalValue(), 1e-12);

        byte[] current = new byte[8];
        SignalBitExtractorTest.writeIntel(current, 48, 16, -500L);
        CanonicalSignal amps = one(decode(db, 256, current), "PackCurrent");
        assertEquals(-500L, amps.rawValue());
        assertEquals(-500.0 * 0.01, amps.physicalValue(), 1e-12);

        byte[] torque = new byte[8];
        torque[7] = 80;
        CanonicalSignal nm = one(decode(db, 256, torque), "AxleTorque");
        assertEquals(80.0 * 2.0 + -100.0, nm.physicalValue(), 0.0);
    }

    @Test
    void stringAttributeAndNonIntegralLiteralAreNotRawCodes() throws Exception {
        DbcDatabase db = parse("""
                VERSION ""
                BU_: ECU
                BO_ 256 Powertrain: 8 ECU
                 SG_ VehicleSpeed : 0|16@1+ (0.01,0) [0|655.34] "km/h" ECU
                BA_DEF_ SG_ "GenSigInvalidValue" STRING ;
                BA_ "GenSigInvalidValue" SG_ 256 VehicleSpeed "NA";
                BA_DEF_ SG_ "GenSigInactiveValue" FLOAT 0 0;
                BA_ "GenSigInactiveValue" SG_ 256 VehicleSpeed 1.5;
                """);
        assertNull(signal(db, "VehicleSpeed").invalidRawValue());
        assertNull(signal(db, "VehicleSpeed").inactiveRawValue());
        byte[] payload = new byte[8];
        SignalBitExtractorTest.writeIntel(payload, 0, 16, 0L);
        CanonicalSignal speed = one(decode(db, 256, payload), "VehicleSpeed");
        assertEquals(0.0, speed.physicalValue(), 0.0);
        assertFalse(speed.valueInvalid());
        assertTrue(db.diagnostics().stream().anyMatch(line -> line.contains("not an integral raw value")));
    }

    @Test
    void ieeeFloatAndDoubleUseBitPatternsAndFactorOffset() throws Exception {
        DbcDatabase db = parse("""
                VERSION ""
                BU_: ECU
                BO_ 320 Floats: 8 ECU
                 SG_ FloatIntel : 0|32@1- (2,-1) [0|0] "" ECU
                 SG_ TooShort : 32|16@1+ (1,0) [0|0] "" ECU
                BO_ 321 Doubles: 8 ECU
                 SG_ DoubleMotorola : 7|64@0+ (0.5,-1) [0|0] "" ECU
                SIG_VALTYPE_ 320 FloatIntel : 1;
                SIG_VALTYPE_ 320 TooShort : 1;
                SIG_VALTYPE_ 321 DoubleMotorola : 2;
                """);
        assertEquals(DbcRawEncoding.IEEE_FLOAT32, signal(db, "FloatIntel").rawEncoding());
        assertEquals(DbcRawEncoding.INTEGER, signal(db, "TooShort").rawEncoding());
        assertEquals(DbcRawEncoding.IEEE_FLOAT64, signal(db, "DoubleMotorola").rawEncoding());
        String ignored = db.diagnostics().stream()
                .filter(line -> line.startsWith(DbcParser.SIG_VALTYPE_WARNING_PREFIX))
                .filter(line -> line.contains("TooShort"))
                .findFirst()
                .orElse("");
        assertTrue(ignored.contains("IEEE float signal requires bitLength 32"), ignored);
        assertTrue(ignored.contains("Integer decoding was kept"), ignored);
        assertTrue(ignored.contains("at line "), ignored);
        assertTrue(db.diagnostics().stream().noneMatch(line ->
                line.startsWith(DbcParser.SIG_VALTYPE_WARNING_PREFIX) && line.contains("'FloatIntel'")));
        assertTrue(DbcParser.importWarnings(db).stream().anyMatch(line ->
                line.contains("TooShort") && line.contains("formula was not changed")));

        int bits = Float.floatToRawIntBits(12.5f);
        byte[] intel = new byte[8];
        intel[0] = (byte) bits;
        intel[1] = (byte) (bits >>> 8);
        intel[2] = (byte) (bits >>> 16);
        intel[3] = (byte) (bits >>> 24);
        CanonicalSignal floatSignal = one(decode(db, 320, intel), "FloatIntel");
        double expectedFloat = Float.intBitsToFloat(bits) * 2.0 - 1.0;
        assertEquals(expectedFloat, floatSignal.physicalValue(), 1e-6);
        assertEquals(Integer.toUnsignedLong(bits), floatSignal.rawValue());
        assertFalse(floatSignal.valueInvalid());

        int nanBits = 0x7fc00000;
        byte[] nan = new byte[8];
        nan[0] = (byte) nanBits;
        nan[1] = (byte) (nanBits >>> 8);
        nan[2] = (byte) (nanBits >>> 16);
        nan[3] = (byte) (nanBits >>> 24);
        CanonicalSignal nanSignal = one(decode(db, 320, nan), "FloatIntel");
        assertTrue(Double.isNaN(nanSignal.physicalValue()));
        assertEquals(SignalValidity.NON_FINITE_VALUE, nanSignal.validity());
        assertEquals(Integer.toUnsignedLong(nanBits), nanSignal.rawValue());
        assertNotEquals(0L, nanSignal.rawValue());

        long doubleBits = Double.doubleToRawLongBits(16.0);
        byte[] motorola = new byte[8];
        for (int i = 0; i < 8; i++) {
            motorola[i] = (byte) (doubleBits >>> ((7 - i) * 8));
        }
        CanonicalSignal wide = one(decode(db, 321, motorola), "DoubleMotorola");
        double expectedDouble = Double.longBitsToDouble(doubleBits) * 0.5 - 1.0;
        assertEquals(expectedDouble, wide.physicalValue(), 1e-12);
        assertEquals(doubleBits, wide.rawValue());
    }

    @Test
    void gpsScalingAndSeparateDatabasesStayIndependent() throws Exception {
        String gps = """
                VERSION ""
                BU_: IVI
                BO_ 556 GPS: 8 IVI
                 SG_ GPS_LAT_INFO : 7|32@0- (1E-007,0) [-214.7484|214.7483646] "Decimal Degrees" IVI
                 SG_ GPS_LONG_INFO : 39|32@0- (1E-007,0) [-214.7484|214.7483646] "" IVI
                """;
        String other = """
                VERSION ""
                BU_: IVI
                BO_ 556 GPS: 8 IVI
                 SG_ GPS_LAT_INFO : 7|32@0- (1,0) [0|0] "raw" IVI
                 SG_ GPS_LONG_INFO : 39|32@0- (1,0) [0|0] "" IVI
                """;
        DbcDatabase scaled = parse(gps);
        DbcDatabase unscaled = parse(other);
        byte[] payload = new byte[8];
        long latRaw = 133_315_150L;
        long lonRaw = 776_377_709L;
        SignalBitExtractorTest.writeMotorola(payload, 7, 32, latRaw);
        SignalBitExtractorTest.writeMotorola(payload, 39, 32, lonRaw);

        CanonicalSignal lat = one(decode(scaled, 556, payload, 397_000_000L), "GPS_LAT_INFO");
        CanonicalSignal lon = one(decode(scaled, 556, payload, 397_000_000L), "GPS_LONG_INFO");
        assertEquals(latRaw * 1.0e-7, lat.physicalValue(), 1e-12);
        assertEquals(lonRaw * 1.0e-7, lon.physicalValue(), 1e-12);
        assertEquals(397_000_000L, lat.timestampNanos());
        assertEquals("Decimal Degrees", lat.unit());
        assertFalse(lat.valueInvalid());

        CanonicalSignal rawLat = one(decode(unscaled, 556, payload, 742_000_000L), "GPS_LAT_INFO");
        assertEquals((double) latRaw, rawLat.physicalValue(), 0.0);
        assertEquals(742_000_000L, rawLat.timestampNanos());
        assertNotEquals(lat.physicalValue(), rawLat.physicalValue(), 0.0);
    }

    @Test
    void multiplexedLeafStillUsesItsOwnFactor() throws Exception {
        DbcDatabase db = parse("""
                VERSION ""
                BU_: ECU
                BO_ 512 MuxMsg: 8 ECU
                 SG_ Selector M : 0|8@1+ (1,0) [0|3] "" ECU
                 SG_ Temperature m1 : 8|8@1+ (1,-40) [0|0] "degC" ECU
                """);
        byte[] payload = new byte[] {1, 90, 0, 0, 0, 0, 0, 0};
        Map<String, CanonicalSignal> byName = decode(db, 512, payload).signals().stream()
                .collect(Collectors.toMap(CanonicalSignal::signalName, s -> s));
        assertEquals(1L, byName.get("Selector").rawValue());
        assertEquals(90.0 - 40.0, byName.get("Temperature").physicalValue(), 0.0);
        assertFalse(byName.containsKey("Missing"));
    }

    private static DbcDatabase parse(String text) throws Exception {
        return new DbcParser().parse(new BufferedReader(new StringReader(text)));
    }

    private static com.stellar.platform.dbc.model.DbcSignal signal(DbcDatabase db, String name) {
        return db.messages().stream()
                .flatMap(message -> message.signals().stream())
                .filter(signal -> signal.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static DecodingResult decode(DbcDatabase db, long canId, byte[] payload) {
        return decode(db, canId, payload, 100_000_000L);
    }

    private static DecodingResult decode(DbcDatabase db, long canId, byte[] payload, long timestampNanos) {
        CanonicalCanFrame frame = new CanonicalCanFrame(
                0, timestampNanos, 1, canId, false, FrameDirection.RX, payload.length, payload,
                FrameQuality.OK, SourceReference.ofLine(1)
        );
        return DECODER.decode(frame, db);
    }

    private static CanonicalSignal one(DecodingResult result, String name) {
        return result.signals().stream()
                .filter(signal -> signal.signalName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
