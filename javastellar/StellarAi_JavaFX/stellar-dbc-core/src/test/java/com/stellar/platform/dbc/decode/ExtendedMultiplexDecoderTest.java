package com.stellar.platform.dbc.decode;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcDiagnosticCode;
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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic (non-proprietary) regression for Vector-style extended multiplexing:
 * root {@code M} → secondary {@code mNM} → leaf {@code mN}, Motorola extraction, factor/offset.
 */
class ExtendedMultiplexDecoderTest {

    @TempDir
    Path temp;

    @Test
    void hierarchicalMux_selectsLeafBySecondaryValue_notRoot() throws Exception {
        DbcDatabase database = parseSyntheticDbc();
        DefaultDbcDecoder decoder = new DefaultDbcDecoder();

        // Root mux=0x41 (65), secondary PID=0x05, coolant raw=0x7B → 83 degC
        byte[] pid05 = new byte[]{0x03, 0x41, 0x05, 0x7B, 0x00, 0x00, 0x00, 0x00};
        DecodingResult cool = decoder.decode(frame(0x100, pid05), database);
        assertEquals(DecodeStatus.DECODED, cool.status(), String.valueOf(cool.diagnostics()));
        Map<String, CanonicalSignal> coolMap = byName(cool);
        assertEquals(Set.of("Svc", "Pid", "CoolantTemp"), coolMap.keySet());
        assertEquals(65L, coolMap.get("Svc").rawValue());
        assertEquals(5L, coolMap.get("Pid").rawValue());
        assertEquals(123L, coolMap.get("CoolantTemp").rawValue());
        assertEquals(83.0, coolMap.get("CoolantTemp").physicalValue(), 1e-9);
        assertEquals("degC", coolMap.get("CoolantTemp").unit());
        assertFalse(cool.diagnostics().stream()
                .anyMatch(d -> d.code() == DbcDiagnosticCode.OUT_OF_RANGE_PHYSICAL_VALUE));

        // Same root (65), different PID → different leaf; MonStatus must NOT fire for PID=5
        byte[] pid0C = new byte[]{0x04, 0x41, 0x0C, 0x1A, (byte) 0xF8, 0x00, 0x00, 0x00};
        DecodingResult rpm = decoder.decode(frame(0x100, pid0C), database);
        Map<String, CanonicalSignal> rpmMap = byName(rpm);
        assertEquals(Set.of("Svc", "Pid", "EngineRpm"), rpmMap.keySet());
        assertEquals(12L, rpmMap.get("Pid").rawValue());
        assertEquals(6904L, rpmMap.get("EngineRpm").rawValue());
        assertEquals(1726.0, rpmMap.get("EngineRpm").physicalValue(), 1e-9);

        // Root=65 and secondary PID=65 → leaf gated by secondary (not by treating m65 as root match alone)
        byte[] pid41 = new byte[]{0x03, 0x41, 0x41, 0x07, 0x00, 0x00, 0x00, 0x00};
        DecodingResult mon = decoder.decode(frame(0x100, pid41), database);
        Map<String, CanonicalSignal> monMap = byName(mon);
        assertEquals(Set.of("Svc", "Pid", "MonStatus"), monMap.keySet());
        assertEquals(65L, monMap.get("Pid").rawValue());
        assertEquals(7L, monMap.get("MonStatus").rawValue());
    }

    @Test
    void batchParity_countsFramesMatchedActiveSignalsAndRows() throws Exception {
        DbcDatabase database = parseSyntheticDbc();
        DefaultDbcDecoder decoder = new DefaultDbcDecoder();

        byte[][] payloads = {
                {0x03, 0x41, 0x05, 0x7B, 0x00, 0x00, 0x00, 0x00},
                {0x04, 0x41, 0x0C, 0x1A, (byte) 0xF8, 0x00, 0x00, 0x00},
                {0x03, 0x41, 0x41, 0x07, 0x00, 0x00, 0x00, 0x00},
                {0x03, 0x41, 0x05, 0x50, 0x00, 0x00, 0x00, 0x00},
                // unmatched id
                {0x03, 0x41, 0x05, 0x7B, 0x00, 0x00, 0x00, 0x00}
        };
        long[] ids = {0x100, 0x100, 0x100, 0x100, 0x200};

        Set<String> active = new HashSet<>();
        int signalRows = 0;
        int matched = 0;
        for (int i = 0; i < payloads.length; i++) {
            DecodingResult r = decoder.decode(frame(ids[i], payloads[i]), database);
            if (r.status() == DecodeStatus.UNMATCHED) {
                continue;
            }
            matched++;
            signalRows += r.signals().size();
            r.signals().forEach(s -> active.add(s.signalName()));
        }

        assertEquals(5, payloads.length);
        assertEquals(4, matched);
        assertEquals(4 * 3, signalRows);
        assertEquals(Set.of("Svc", "Pid", "CoolantTemp", "EngineRpm", "MonStatus"), active);
        assertEquals(5, active.size());
    }

    private DbcDatabase parseSyntheticDbc() throws Exception {
        Path dbc = temp.resolve("extended_mux_synthetic.dbc");
        Files.writeString(dbc, """
                VERSION ""
                NS_ :
                BS_:
                BU_:
                BO_ 256 MuxMsg: 8 Vector__XXX
                 SG_ Svc M : 15|8@0+ (1,0) [0|15] "" Vector__XXX
                 SG_ Pid m65M : 23|8@0+ (1,0) [0|255] "" Vector__XXX
                 SG_ CoolantTemp m5 : 31|8@0+ (1,-40) [-40|215] "degC" Vector__XXX
                 SG_ EngineRpm m12 : 31|16@0+ (0.25,0) [0|16383.75] "rpm" Vector__XXX
                 SG_ MonStatus m65 : 31|8@0+ (1,0) [0|255] "" Vector__XXX
                """);
        return new DbcParser().parse(dbc);
    }

    private static CanonicalCanFrame frame(long canId, byte[] data) {
        return new CanonicalCanFrame(
                0L, 221_140_000L, 0, canId, false, FrameDirection.RX, data.length, data,
                FrameQuality.OK, new SourceReference(1L, 0L)
        );
    }

    private static Map<String, CanonicalSignal> byName(DecodingResult result) {
        Map<String, CanonicalSignal> map = new HashMap<>();
        for (CanonicalSignal s : result.signals()) {
            map.put(s.signalName(), s);
        }
        return map;
    }
}
