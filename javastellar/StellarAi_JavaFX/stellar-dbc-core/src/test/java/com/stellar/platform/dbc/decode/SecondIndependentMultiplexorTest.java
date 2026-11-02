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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two signals marked {@code M} do not name which selector gates an {@code mN} leaf.
 * The leaf is omitted and a diagnostic is reported.
 */
class SecondIndependentMultiplexorTest {

    @TempDir
    Path temp;

    @Test
    void secondIndependentMultiplexorDoesNotEmitALeafFromTheFirstSelector() throws Exception {
        Path dbc = temp.resolve("two-m.dbc");
        Files.writeString(dbc, """
                VERSION ""
                NS_ :
                BS_:
                BU_: ECU
                BO_ 1570 AmbiguousMux: 8 ECU
                 SG_ Left M : 0|8@1+ (1,0) [0|255] "" ECU
                 SG_ Right M : 8|8@1+ (1,0) [0|255] "" ECU
                 SG_ Leaf m0 : 16|8@1+ (1,0) [0|255] "" ECU
                 SG_ Keep : 24|8@1+ (1,0) [0|255] "" ECU
                """);
        DbcDatabase database = new DbcParser().parse(dbc);
        DecodingResult result = new DefaultDbcDecoder().decode(frame(1570L, new byte[] {0x00, 0x01, 0x63, 0x05}), database);
        assertEquals(Set.of("Left", "Right", "Keep"), names(result));
        assertEquals(0L, signal(result, "Left").rawValue());
        assertEquals(1L, signal(result, "Right").rawValue());
        assertEquals(5L, signal(result, "Keep").rawValue());
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                d.code() == DbcDiagnosticCode.UNSUPPORTED_MULTIPLEXING
                        && d.message().contains("independent multiplexors")));
    }

    @Test
    void selectorCycleEmitsTheCommonSignalAndADiagnostic() throws Exception {
        Path dbc = temp.resolve("cycle.dbc");
        Files.writeString(dbc, """
                VERSION ""
                NS_ :
                BS_:
                BU_: ECU
                BO_ 1571 CycleMux: 4 ECU
                 SG_ Alpha M : 0|8@1+ (1,0) [0|255] "" ECU
                 SG_ Beta m1 : 8|8@1+ (1,0) [0|255] "" ECU
                 SG_ Keep : 16|8@1+ (1,0) [0|255] "" ECU
                SG_MUL_VAL_ 1571 Beta Alpha 1-1;
                SG_MUL_VAL_ 1571 Alpha Beta 1-1;
                """);
        DbcDatabase database = new DbcParser().parse(dbc);
        DecodingResult result = new DefaultDbcDecoder().decode(frame(1571L, new byte[] {0x01, 0x01, 0x05, 0x00}), database);
        assertEquals(Set.of("Keep"), names(result));
        assertEquals(5L, signal(result, "Keep").rawValue());
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                d.code() == DbcDiagnosticCode.UNSUPPORTED_MULTIPLEXING && d.message().contains("Cycle")));
    }

    private static CanonicalCanFrame frame(long canId, byte[] data) {
        return new CanonicalCanFrame(
                0, 100_000_000L, 1, canId, false, FrameDirection.RX, data.length, data,
                FrameQuality.OK, SourceReference.ofLine(1));
    }

    private static Set<String> names(DecodingResult result) {
        return result.signals().stream().map(CanonicalSignal::signalName).collect(Collectors.toSet());
    }

    private static CanonicalSignal signal(DecodingResult result, String name) {
        return result.signals().stream().filter(s -> name.equals(s.signalName())).findFirst().orElseThrow();
    }
}
