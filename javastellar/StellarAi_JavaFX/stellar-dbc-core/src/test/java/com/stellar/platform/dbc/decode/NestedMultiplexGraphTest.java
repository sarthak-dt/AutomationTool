package com.stellar.platform.dbc.decode;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMultiplexGraph;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two-level nested multiplex graph using arbitrary non-proprietary names.
 *
 * Service
 *   -> value A -> Mode
 *                  -> value 1 -> Temperature
 *                  -> value 2 -> Speed
 *   -> value B -> Pressure
 */
class NestedMultiplexGraphTest {

    @TempDir
    Path temp;

    @Test
    void twoLevelGraph_selectsCorrectBranches() throws Exception {
        Path dbc = temp.resolve("two_level_mux.dbc");
        Files.writeString(dbc, """
                VERSION ""
                NS_ :
                BS_:
                BU_:
                BO_ 256 NestedMsg: 8 Vector__XXX
                 SG_ Service M : 15|8@0+ (1,0) [0|255] "" Vector__XXX
                 SG_ Mode m10M : 23|8@0+ (1,0) [0|255] "" Vector__XXX
                 SG_ Temperature m1 : 31|8@0+ (1,0) [0|255] "C" Vector__XXX
                 SG_ Speed m2 : 31|8@0+ (1,0) [0|255] "km/h" Vector__XXX
                 SG_ Pressure m20 : 31|8@0+ (1,0) [0|255] "kPa" Vector__XXX
                SG_MUL_VAL_ 256 Mode Service 10-10;
                SG_MUL_VAL_ 256 Temperature Mode 1-1;
                SG_MUL_VAL_ 256 Speed Mode 2-2;
                SG_MUL_VAL_ 256 Pressure Service 20-20;
                """);
        DbcDatabase database = new DbcParser().parse(dbc);
        DbcMultiplexGraph graph = database.messages().iterator().next().multiplexGraph();
        assertEquals(3, graph.muxLevelCount());
        assertTrue(graph.diagnostics().isEmpty(), graph.diagnostics()::toString);
        assertTrue(graph.renderTree().contains("Mode"));
        assertTrue(graph.renderTree().contains("MULTIPLEXED_MULTIPLEXOR"));

        DefaultDbcDecoder decoder = new DefaultDbcDecoder();

        DecodingResult tempResult = decoder.decode(
                frame(new byte[]{0x03, 0x0A, 0x01, 0x2A, 0, 0, 0, 0}), database);
        assertEquals(Set.of("Service", "Mode", "Temperature"), names(tempResult));
        assertEquals(42L, byName(tempResult).get("Temperature").rawValue());

        DecodingResult speedResult = decoder.decode(
                frame(new byte[]{0x03, 0x0A, 0x02, 0x64, 0, 0, 0, 0}), database);
        assertEquals(Set.of("Service", "Mode", "Speed"), names(speedResult));
        assertEquals(100L, byName(speedResult).get("Speed").rawValue());

        DecodingResult pressureResult = decoder.decode(
                frame(new byte[]{0x03, 0x14, 0x00, 0x07, 0, 0, 0, 0}), database);
        assertEquals(Set.of("Service", "Pressure"), names(pressureResult));
        assertEquals(7L, byName(pressureResult).get("Pressure").rawValue());

        DecodingResult inactive = decoder.decode(
                frame(new byte[]{0x03, 0x03, 0x01, 0x2A, 0, 0, 0, 0}), database);
        assertEquals(Set.of("Service"), names(inactive));
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