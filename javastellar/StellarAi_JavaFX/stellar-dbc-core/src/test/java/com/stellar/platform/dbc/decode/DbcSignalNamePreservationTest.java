package com.stellar.platform.dbc.decode;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.parse.DbcDiagnosticCode;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.SourceReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbcSignalNamePreservationTest {

    @TempDir
    Path tempDir;

    @Test
    void parserAndDecoderPreserveFullSignalNamesBeginningWithS() throws Exception {
        Path dbc = tempDir.resolve("mux_names.dbc");
        Files.writeString(dbc, String.join("\n",
                "VERSION \"\"",
                "NS_ :",
                "BS_:",
                "BU_:",
                "BO_ 100 MSG: 8 Vector__XXX",
                " SG_ S M : 7|8@0+ (1,0) [0|255] \"\" Vector__XXX",
                " SG_ S01PID0C_EngineRPM m12 : 15|16@0+ (0.25,0) [0|16383.75] \"rpm\" Vector__XXX",
                " SG_ S01PID0D_VehicleSpeed m13 : 15|8@0+ (1,0) [0|255] \"km/h\" Vector__XXX",
                " SG_ S01PID05_EngineCoolantTemp m5 : 15|8@0+ (1,-40) [-40|215] \"degC\" Vector__XXX"
        ), StandardCharsets.UTF_8);

        DbcDatabase db = new DbcParser().parse(dbc);
        Set<String> names = db.messages().iterator().next().signals().stream()
                .map(DbcSignal::name)
                .collect(Collectors.toSet());
        assertTrue(names.contains("S"));
        assertTrue(names.contains("S01PID0C_EngineRPM"));
        assertTrue(names.contains("S01PID0D_VehicleSpeed"));
        assertTrue(names.contains("S01PID05_EngineCoolantTemp"));
        assertFalse(names.stream().anyMatch(n -> n.equals("S01") || n.equals("S0")));

        byte[] payload = new byte[] {13, 40, 0, 0, 0, 0, 0, 0};
        CanonicalCanFrame frame = new CanonicalCanFrame(
                1L, 1_000L, 0, 100L, false, FrameDirection.RX, 8, payload,
                FrameQuality.VALID, new SourceReference(1, 0)
        );
        DecodingResult result = new DefaultDbcDecoder().decode(frame, db);
        List<String> decodedNames = result.signals().stream().map(CanonicalSignal::signalName).toList();
        assertTrue(decodedNames.contains("S01PID0D_VehicleSpeed"), () -> decodedNames.toString());
        assertTrue(decodedNames.contains("S"));
        assertEquals("S01PID0D_VehicleSpeed",
                decodedNames.stream().filter(n -> n.contains("VehicleSpeed")).findFirst().orElseThrow());
    }

    @Test
    void inactiveMuxEmitsDiagnosticWithoutTruncatingNames() throws Exception {
        Path dbc = tempDir.resolve("mux_inactive.dbc");
        Files.writeString(dbc, String.join("\n",
                "VERSION \"\"",
                "NS_ :",
                "BS_:",
                "BU_:",
                "BO_ 100 MSG: 8 Vector__XXX",
                " SG_ S M : 7|8@0+ (1,0) [0|255] \"\" Vector__XXX",
                " SG_ S01PID0D_VehicleSpeed m13 : 15|8@0+ (1,0) [0|255] \"km/h\" Vector__XXX"
        ), StandardCharsets.UTF_8);
        DbcDatabase db = new DbcParser().parse(dbc);
        byte[] payload = new byte[] {3, 0, 0, 0, 0, 0, 0, 0};
        CanonicalCanFrame frame = new CanonicalCanFrame(
                1L, 1_000L, 0, 100L, false, FrameDirection.RX, 8, payload,
                FrameQuality.VALID, new SourceReference(1, 0)
        );
        DecodingResult result = new DefaultDbcDecoder().decode(frame, db);
        assertEquals(List.of("S"), result.signals().stream().map(CanonicalSignal::signalName).toList());
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.code() == DbcDiagnosticCode.MUX_NO_ACTIVE_LEAF));
        assertTrue(db.messages().iterator().next().signals().stream()
                .anyMatch(s -> s.name().equals("S01PID0D_VehicleSpeed")));
    }
}
