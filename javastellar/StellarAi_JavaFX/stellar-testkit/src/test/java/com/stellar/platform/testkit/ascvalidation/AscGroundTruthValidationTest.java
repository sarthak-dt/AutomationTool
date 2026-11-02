package com.stellar.platform.testkit.ascvalidation;

import com.stellar.platform.testkit.ascvalidation.AscDataValidator.GpsClass;
import com.stellar.platform.testkit.ascvalidation.AscDataValidator.ValidationResult;
import com.stellar.platform.testkit.ascvalidation.IndependentAscScanner.RawFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent ground-truth checks. Expected physical values are computed from payload + DBC
 * layout in this test, not from StellarAI decode output.
 */
class AscGroundTruthValidationTest {

    @TempDir
    Path temp;

    @Test
    void independentIntelDecoderMatchesHandCalculatedEngineSpeed() {
        byte[] payload = hex("10 27 5A 01 AA 00 00 00");
        long raw = IndependentIntelDecoder.extractUnsigned(payload, 0, 16);
        assertEquals(0x2710L, raw);
        assertEquals(2500.0, IndependentIntelDecoder.toPhysical(raw, 0.25, 0.0), 1e-12);
        long tempRaw = IndependentIntelDecoder.extractSigned(payload, 16, 8);
        assertEquals(90L, tempRaw);
        assertEquals(50.0, IndependentIntelDecoder.toPhysical(tempRaw, 1.0, -40.0), 1e-12);
    }

    @Test
    void independentGpsCoordinatesFromGoldenPayloads() {
        byte[] first = hex("20 FD 09 0B 58 A3 05 2C");
        assertEquals(185_204_000L, IndependentIntelDecoder.extractSigned(first, 0, 32));
        assertEquals(738_567_000L, IndependentIntelDecoder.extractSigned(first, 32, 32));
        assertEquals(18.5204, IndependentIntelDecoder.toPhysical(185_204_000L, 1e-7, 0.0), 1e-12);
        assertEquals(73.8567, IndependentIntelDecoder.toPhysical(738_567_000L, 1e-7, 0.0), 1e-12);

        byte[] middle = hex("08 01 0A 0B 40 A7 05 2C");
        assertEquals(185_205_000L, IndependentIntelDecoder.extractSigned(middle, 0, 32));
        assertEquals(738_568_000L, IndependentIntelDecoder.extractSigned(middle, 32, 32));

        byte[] last = hex("F0 04 0A 0B 28 AB 05 2C");
        assertEquals(185_206_000L, IndependentIntelDecoder.extractSigned(last, 0, 32));
        assertEquals(738_569_000L, IndependentIntelDecoder.extractSigned(last, 32, 32));
    }

    @Test
    void goldenAscInventoryAndPayloadSpotChecks() throws Exception {
        Path asc = resource("ascvalidation/golden.asc");
        var inv = IndependentAscScanner.scan(asc);
        assertEquals(9, inv.validFrames());
        assertEquals(4, inv.uniqueCanIds().size());
        assertEquals(8, inv.standardCount());
        assertEquals(1, inv.extendedCount());
        assertEquals(8, inv.rxCount());
        assertEquals(1, inv.txCount());
        assertEquals(1, inv.malformed());
        assertEquals(1, inv.skipped());
        assertEquals(1_000_000_000L, inv.firstTimestampNanos());
        assertEquals(4_000_000_000L, inv.lastTimestampNanos());
        assertEquals(3_000_000_000L, inv.durationNanos());

        RawFrame firstEngine = inv.representative(1, 0x100, false, "first");
        assertEquals(1_000_000_000L, firstEngine.timestampNanos());
        assertEquals(1, firstEngine.channel());
        assertEquals(0x100L, firstEngine.canId());
        assertEquals(8, firstEngine.dlc());
        assertArrayEquals(hex("10 27 5A 01 AA 00 00 00"), firstEngine.data());

        RawFrame midEngine = inv.representative(1, 0x100, false, "middle");
        assertArrayEquals(hex("20 4E 64 02 BB 00 00 00"), midEngine.data());
        RawFrame lastEngine = inv.representative(1, 0x100, false, "last");
        assertEquals("TX", lastEngine.direction());
        assertArrayEquals(hex("30 75 6E 03 CC 00 00 00"), lastEngine.data());

        RawFrame gpsFirst = inv.representative(1, 0x300, false, "first");
        assertArrayEquals(hex("20 FD 09 0B 58 A3 05 2C"), gpsFirst.data());
    }

    @Test
    void goldenPipelineMatchesIndependentTruth() throws Exception {
        Path asc = resource("ascvalidation/golden.asc");
        Path dbc = resource("ascvalidation/golden.dbc");
        ValidationResult result = AscDataValidator.validate(asc, dbc, temp.resolve("golden-store"));
        System.out.println(result.formatReport());

        assertTrue(result.frameMismatches().isEmpty(), result.frameMismatches().toString());
        assertEquals(9, result.parsedFrames());
        assertEquals(9, result.raw().validFrames());
        assertEquals(3, result.coverage().matched().size());
        assertTrue(result.coverage().unmatchedAsc().contains("S:123"));
        assertEquals(7, result.coverage().signalsDefinedInMatched());
        assertEquals(10, result.decodedPhysicalSignals());
        assertEquals(10, result.catalogEntries());
        assertEquals(GpsClass.GPS_DISCOVERED_AND_VALID, result.gps());
        assertTrue(result.valueChecks().stream().allMatch(c -> c.pass()), failing(result));
        assertTrue(result.sourceDataMatch());

        var latFirst = result.valueChecks().stream()
                .filter(c -> "Latitude".equals(c.signal()) && c.timestampNanos() == 3_000_000_000L)
                .findFirst()
                .orElseThrow();
        assertTrue(latFirst.expected().startsWith("18.5204"));
    }

    @Test
    void engineSampleHasFiveDecodedSignalsAndNoGps() throws Exception {
        Path asc = resource("ascvalidation/engine_sample.asc");
        Path dbc = resource("dbc/pipeline.dbc");
        ValidationResult result = AscDataValidator.validate(asc, dbc, temp.resolve("engine-store"));
        System.out.println(result.formatReport());
        assertTrue(result.frameMismatches().isEmpty());
        assertEquals(3, result.raw().validFrames());
        assertEquals(3, result.raw().uniqueCanIds().size());
        assertEquals(2, result.coverage().matched().size());
        assertTrue(result.coverage().unmatchedAsc().contains("S:123"));
        assertEquals(5, result.coverage().signalsDefinedInMatched());
        assertEquals(5, result.decodedPhysicalSignals());
        assertEquals(5, result.catalogEntries());
        assertEquals(GpsClass.GPS_NOT_PRESENT_IN_SOURCE, result.gps());
        assertTrue(result.valueChecks().stream().filter(c -> !"FRAME".equals(c.signal())
                && !c.signal().startsWith("CAN ")).allMatch(c -> c.pass()), failing(result));
        assertTrue(result.sourceDataMatch());
    }

    @Test
    void desktopEngineThreeSignalsNoGps() throws Exception {
        Path asc = resource("ascvalidation/desktop_engine.asc");
        Path dbc = resource("ascvalidation/desktop_engine.dbc");
        ValidationResult result = AscDataValidator.validate(asc, dbc, temp.resolve("desktop-store"));
        System.out.println(result.formatReport());
        assertEquals(1, result.raw().validFrames());
        assertEquals(3, result.decodedPhysicalSignals());
        assertEquals(GpsClass.GPS_NOT_PRESENT_IN_SOURCE, result.gps());
        assertTrue(result.sourceDataMatch());
    }

    @Test
    void classicalBasicInventoryWithoutDbc() throws Exception {
        Path asc = resource("asc/classical_basic.asc");
        ValidationResult result = AscDataValidator.validate(asc, null, temp.resolve("classic-store"));
        assertEquals(4, result.raw().validFrames());
        assertEquals(4, result.parsedFrames());
        assertTrue(result.frameMismatches().isEmpty());
        assertEquals(GpsClass.GPS_NOT_PRESENT_IN_SOURCE, result.gps());
        assertTrue(result.sourceDataMatch());
    }

    private static String failing(ValidationResult result) {
        StringBuilder sb = new StringBuilder();
        result.valueChecks().stream().filter(c -> !c.pass()).forEach(c ->
                sb.append(c.signal()).append(' ').append(c.expected()).append(" vs ").append(c.stellar()).append('\n'));
        return sb.toString();
    }

    private static byte[] hex(String spaced) {
        return HexFormat.of().parseHex(spaced.replace(" ", ""));
    }

    private static Path resource(String name) throws Exception {
        var url = AscGroundTruthValidationTest.class.getClassLoader().getResource(name);
        if (url == null) {
            throw new IllegalStateException("missing " + name);
        }
        return Path.of(url.toURI());
    }
}
