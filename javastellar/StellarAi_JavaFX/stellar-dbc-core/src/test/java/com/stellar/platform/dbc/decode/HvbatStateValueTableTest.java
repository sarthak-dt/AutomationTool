package com.stellar.platform.dbc.decode;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.SourceReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * HVBAT_STATE value table. Labels come from the raw code. Physical scaling does not choose them.
 */
class HvbatStateValueTableTest {

    private static final String[] LABELS = {
            "Sleep",
            "Initialization",
            "Wait",
            "Ready",
            "Drive",
            "Charge",
            "Error",
            "Reserved",
            "Reserved",
            "Diagnostic",
            "Shutdown",
            "Afterrun",
            "Precharge",
            "Balancing",
            "Reserved",
            "Reserved"
    };

    @TempDir
    Path temp;

    @Test
    void eachDefinedCodeKeepsItsTextAndItsNumbers() throws Exception {
        DbcDatabase database = database();
        DefaultDbcDecoder decoder = new DefaultDbcDecoder();
        for (int code = 0; code < LABELS.length; code++) {
            DecodingResult result = decoder.decode(frame(new byte[] {(byte) code, 0, 0}), database);
            CanonicalSignal state = signal(result, "HVBAT_STATE");
            assertEquals(code, state.rawValue(), "raw " + code);
            assertEquals((double) code, state.physicalValue(), 0.0, "physical " + code);
            assertEquals(LABELS[code], state.textualValue(), "label " + code);
            assertEquals(SignalValidity.VALID, state.validity());
            assertEquals(100_000_000L, state.timestampNanos());
        }
    }

    @Test
    void unknownCodeStaysNumericAndAGapInTheTableIsNotInvented() throws Exception {
        DbcDatabase database = database();
        DefaultDbcDecoder decoder = new DefaultDbcDecoder();

        CanonicalSignal unknown = signal(decoder.decode(frame(new byte[] {16, 0, 0}), database), "HVBAT_STATE");
        assertEquals(16L, unknown.rawValue());
        assertEquals(16.0, unknown.physicalValue(), 0.0);
        assertNull(unknown.textualValue());

        CanonicalSignal gap = signal(decoder.decode(frame(new byte[] {0, 0, 1}), database), "GAP_STATE");
        assertEquals(1L, gap.rawValue());
        assertEquals(1.0, gap.physicalValue(), 0.0);
        assertNull(gap.textualValue());

        CanonicalSignal wait = signal(decoder.decode(frame(new byte[] {0, 0, 2}), database), "GAP_STATE");
        assertEquals(2L, wait.rawValue());
        assertEquals("Wait", wait.textualValue());
    }

    @Test
    void physicalScalingDoesNotChangeTheRawCodeLookup() throws Exception {
        DbcDatabase database = database();
        DefaultDbcDecoder decoder = new DefaultDbcDecoder();
        CanonicalSignal scaled = signal(decoder.decode(frame(new byte[] {0, 1, 0}), database), "SCALED_STATE");
        assertEquals(1L, scaled.rawValue());
        assertEquals(10.5, scaled.physicalValue(), 1e-9);
        assertEquals("Initialization", scaled.textualValue());
    }

    @Test
    void repeatedCodeKeepsTheSameLabelAndInvalidIsNotAValueTableHit() throws Exception {
        DbcDatabase database = database();
        DefaultDbcDecoder decoder = new DefaultDbcDecoder();
        CanonicalSignal first = signal(decoder.decode(frame(new byte[] {3, 0, 0}), database), "HVBAT_STATE");
        CanonicalSignal second = signal(decoder.decode(frame(new byte[] {3, 0, 0}), database), "HVBAT_STATE");
        assertEquals(first.textualValue(), second.textualValue());
        assertEquals("Ready", second.textualValue());
        assertEquals(first.rawValue(), second.rawValue());
        assertEquals(first.physicalValue(), second.physicalValue(), 0.0);

        CanonicalSignal invalid = signal(decoder.decode(frame(new byte[] {(byte) 255, 0, 0}), database), "HVBAT_STATE");
        assertEquals(255L, invalid.rawValue());
        assertEquals(255.0, invalid.physicalValue(), 0.0);
        assertEquals(SignalValidity.INVALID_RAW_CODE, invalid.validity());
        assertEquals("invalid", invalid.textualValue());
    }

    private DbcDatabase database() throws Exception {
        Path dbc = temp.resolve("hvbat.dbc");
        Files.writeString(dbc, """
                VERSION ""
                NS_ :
                BS_:
                BU_: ECU
                BO_ 256 Battery: 8 ECU
                 SG_ HVBAT_STATE : 0|8@1+ (1,0) [0|15] "" ECU
                 SG_ SCALED_STATE : 8|8@1+ (0.5,10) [0|255] "" ECU
                 SG_ GAP_STATE : 16|8@1+ (1,0) [0|15] "" ECU
                VAL_ 256 HVBAT_STATE 0 "Sleep" 1 "Initialization" 2 "Wait" 3 "Ready" 4 "Drive" 5 "Charge" 6 "Error" 7 "Reserved" 8 "Reserved" 9 "Diagnostic" 10 "Shutdown" 11 "Afterrun" 12 "Precharge" 13 "Balancing" 14 "Reserved" 15 "Reserved";
                VAL_ 256 SCALED_STATE 0 "Sleep" 1 "Initialization";
                VAL_ 256 GAP_STATE 0 "Sleep" 2 "Wait" 15 "Reserved";
                BA_DEF_ SG_ "GenSigInvalidValue" INT 0 0;
                BA_ "GenSigInvalidValue" SG_ 256 HVBAT_STATE 255;
                """);
        return new DbcParser().parse(dbc);
    }

    private static CanonicalCanFrame frame(byte[] data) {
        return new CanonicalCanFrame(
                0, 100_000_000L, 1, 256L, false, FrameDirection.RX, data.length, data,
                FrameQuality.OK, SourceReference.ofLine(1));
    }

    private static CanonicalSignal signal(DecodingResult result, String name) {
        for (CanonicalSignal signal : result.signals()) {
            if (name.equals(signal.signalName())) {
                return signal;
            }
        }
        throw new AssertionError(name + " missing from " + result.diagnostics());
    }
}
