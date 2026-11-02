package com.stellar.platform.dbc.parse;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Optional local compatibility hook for the real OBD2 DBC (not committed to Git).
 */
class Obd2DbcCompatibilityTest {

    private static final Path[] CANDIDATES = {
            Path.of("C:\\Users\\ADMIN\\Desktop\\DriveTech\\11_bit_OBD2 2.dbc"),
            Path.of(System.getProperty("user.home"), "Desktop", "DriveTech", "11_bit_OBD2 2.dbc"),
            Path.of("external-test-data", "11_bit_OBD2 2.dbc")
    };

    static boolean obd2DbcPresent() {
        for (Path p : CANDIDATES) {
            if (Files.isRegularFile(p)) {
                return true;
            }
        }
        return false;
    }

    static Path resolveObd2() {
        for (Path p : CANDIDATES) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        throw new IllegalStateException("OBD2 DBC not found");
    }

    @Test
    @EnabledIf("obd2DbcPresent")
    void parsesElevenBitObd2Dbc_withoutFatalSgSyntaxError() throws Exception {
        Path dbc = resolveObd2();
        DbcDatabase database = new DbcParser().parse(dbc);

        int messages = database.messages().size();
        int signals = database.messages().stream().mapToInt(m -> m.signals().size()).sum();

        assertTrue(messages >= 1, "expected at least one BO_");
        assertTrue(signals >= 1, "expected SG_ signals to parse");

        DbcMessage obd2 = database.messages().stream()
                .filter(m -> "OBD2".equals(m.name()))
                .findFirst()
                .orElseThrow();
        assertFalse(obd2.signals().isEmpty());

        DbcSignal s01 = obd2.signals().stream()
                .filter(s -> "S01PID".equals(s.name()))
                .findFirst()
                .orElseThrow();
        assertTrue(s01.multiplexValueOptional().orElse(-1) == 65);
        assertTrue(s01.hasExplicitMultiplexConditions(), "S01PID must resolve SG_MUL_VAL_");
        assertEquals("S", s01.multiplexConditions().getFirst().multiplexorSignalName());
        assertEquals(65L, s01.multiplexConditions().getFirst().ranges().getFirst().minimum());
        assertEquals(65L, s01.multiplexConditions().getFirst().ranges().getFirst().maximum());

        long withMulVal = obd2.signals().stream().filter(DbcSignal::hasExplicitMultiplexConditions).count();
        assertTrue(withMulVal >= 100, "expected most signals to carry SG_MUL_VAL_ conditions, got " + withMulVal);

        List<String> diags = database.diagnostics();
        System.out.println("OBD2 DBC parse summary for " + dbc);
        System.out.println("  BO_ messages: " + messages);
        System.out.println("  SG_ signals: " + signals);
        System.out.println("  signals with SG_MUL_VAL_: " + withMulVal);
        System.out.println("  soft diagnostics: " + diags.size());
        diags.stream().limit(30).forEach(d -> System.out.println("  - " + d));

        assertTrue(
                diags.stream().noneMatch(d -> d.contains("Malformed SG_")),
                "must not fatally fail on valid SG_ syntax"
        );
        assertTrue(
                diags.stream().noneMatch(d -> d.contains("Unsupported extended multiplexing")),
                "SG_MUL_VAL_ must be parsed, not reported as unsupported"
        );
        assertTrue(
                diags.stream().noneMatch(d -> d.contains("Unresolved SG_MUL_VAL_")),
                "no unresolved SG_MUL_VAL_ references"
        );
    }
}
