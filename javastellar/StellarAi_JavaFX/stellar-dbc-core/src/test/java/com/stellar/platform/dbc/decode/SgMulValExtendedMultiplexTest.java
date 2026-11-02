package com.stellar.platform.dbc.decode;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMultiplexCondition;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.SourceReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic extended multiplexing via SG_MUL_VAL_ (non-proprietary names).
 */
class SgMulValExtendedMultiplexTest {

    @TempDir
    Path temp;

    @Test
    void sgMulVal_selectsCorrectLeafPerSelector_andRejectsOutsideRange() throws Exception {
        DbcDatabase database = parseSynthetic();
        DbcSignal temperature = database.messages().iterator().next().signals().stream()
                .filter(s -> s.name().equals("Temperature"))
                .findFirst()
                .orElseThrow();
        assertTrue(temperature.hasExplicitMultiplexConditions());
        DbcMultiplexCondition cond = temperature.multiplexConditions().getFirst();
        assertEquals("Mode", cond.multiplexorSignalName());
        assertEquals(1, cond.ranges().size());
        assertEquals(1L, cond.ranges().getFirst().minimum());
        assertEquals(1L, cond.ranges().getFirst().maximum());

        DbcSignal pressure = database.messages().iterator().next().signals().stream()
                .filter(s -> s.name().equals("Pressure"))
                .findFirst()
                .orElseThrow();
        assertEquals(10L, pressure.multiplexConditions().getFirst().ranges().getFirst().minimum());
        assertEquals(12L, pressure.multiplexConditions().getFirst().ranges().getFirst().maximum());

        DefaultDbcDecoder decoder = new DefaultDbcDecoder();

        DecodingResult a = decoder.decode(frame(new byte[]{0x01, 0x2A}), database);
        assertEquals(Set.of("Mode", "Temperature"), names(a));
        assertEquals(1L, byName(a).get("Mode").rawValue());
        assertEquals(42L, byName(a).get("Temperature").rawValue());
        assertEquals(42.0, byName(a).get("Temperature").physicalValue(), 1e-9);

        DecodingResult b = decoder.decode(frame(new byte[]{0x02, 0x64}), database);
        assertEquals(Set.of("Mode", "Speed"), names(b));
        assertEquals(100L, byName(b).get("Speed").rawValue());

        DecodingResult mid = decoder.decode(frame(new byte[]{0x0B, 0x07}), database);
        assertEquals(Set.of("Mode", "Pressure"), names(mid));
        assertEquals(7L, byName(mid).get("Pressure").rawValue());

        DecodingResult outside = decoder.decode(frame(new byte[]{0x03, 0x55}), database);
        assertEquals(Set.of("Mode"), names(outside));
        assertFalse(names(outside).contains("Temperature"));
        assertFalse(names(outside).contains("Speed"));
        assertFalse(names(outside).contains("Pressure"));
    }

    @Test
    void sgMulVal_hierarchicalSecondaryMultiplexor() throws Exception {
        Path dbc = temp.resolve("sg_mul_val_hierarchical.dbc");
        Files.writeString(dbc, """
                VERSION ""
                NS_ :
                BS_:
                BU_:
                BO_ 256 MuxMsg: 8 Vector__XXX
                 SG_ Mode M : 15|8@0+ (1,0) [0|255] "" Vector__XXX
                 SG_ Pid m65M : 23|8@0+ (1,0) [0|255] "" Vector__XXX
                 SG_ Temperature m5 : 31|8@0+ (1,-40) [-40|215] "C" Vector__XXX
                 SG_ Speed m13 : 31|8@0+ (1,0) [0|255] "km/h" Vector__XXX
                SG_MUL_VAL_ 256 Pid Mode 65-65;
                SG_MUL_VAL_ 256 Temperature Pid 5-5;
                SG_MUL_VAL_ 256 Speed Pid 13-13;
                """);
        DbcDatabase database = new DbcParser().parse(dbc);
        DefaultDbcDecoder decoder = new DefaultDbcDecoder();

        DecodingResult cool = decoder.decode(
                frame(new byte[]{0x03, 0x41, 0x05, 0x7B, 0x00, 0x00, 0x00, 0x00}), database);
        assertEquals(Set.of("Mode", "Pid", "Temperature"), names(cool));
        assertEquals(65L, byName(cool).get("Mode").rawValue());
        assertEquals(5L, byName(cool).get("Pid").rawValue());
        assertEquals(123L, byName(cool).get("Temperature").rawValue());
        assertEquals(83.0, byName(cool).get("Temperature").physicalValue(), 1e-9);

        DecodingResult speed = decoder.decode(
                frame(new byte[]{0x03, 0x41, 0x0D, 0x2A, 0x00, 0x00, 0x00, 0x00}), database);
        assertEquals(Set.of("Mode", "Pid", "Speed"), names(speed));
        assertEquals(42L, byName(speed).get("Speed").rawValue());

        DecodingResult wrongRoot = decoder.decode(
                frame(new byte[]{0x03, 0x03, 0x05, 0x7B, 0x00, 0x00, 0x00, 0x00}), database);
        assertEquals(Set.of("Mode"), names(wrongRoot));
        assertEquals(3L, byName(wrongRoot).get("Mode").rawValue());
    }

    private DbcDatabase parseSynthetic() throws Exception {
        Path dbc = temp.resolve("sg_mul_val_synthetic.dbc");
        Files.writeString(dbc, """
                VERSION ""
                NS_ :
                BS_:
                BU_:
                BO_ 256 MuxMsg: 2 Vector__XXX
                 SG_ Mode M : 0|8@1+ (1,0) [0|255] "" Vector__XXX
                 SG_ Temperature m1 : 8|8@1+ (1,0) [0|255] "C" Vector__XXX
                 SG_ Speed m2 : 8|8@1+ (1,0) [0|255] "km/h" Vector__XXX
                 SG_ Pressure m10 : 8|8@1+ (1,0) [0|255] "kPa" Vector__XXX
                SG_MUL_VAL_ 256 Temperature Mode 1-1;
                SG_MUL_VAL_ 256 Speed Mode 2-2;
                SG_MUL_VAL_ 256 Pressure Mode 10-12;
                """);
        return new DbcParser().parse(dbc);
    }

    private static CanonicalCanFrame frame(byte[] data) {
        return new CanonicalCanFrame(
                0L, 1L, 0, 0x100, false, FrameDirection.RX, data.length, data,
                FrameQuality.OK, new SourceReference(1L, 0L)
        );
    }

    private static Set<String> names(DecodingResult result) {
        return result.signals().stream().map(CanonicalSignal::signalName).collect(Collectors.toSet());
    }

    private static Map<String, CanonicalSignal> byName(DecodingResult result) {
        Map<String, CanonicalSignal> map = new HashMap<>();
        for (CanonicalSignal s : result.signals()) {
            map.put(s.signalName(), s);
        }
        return map;
    }
}