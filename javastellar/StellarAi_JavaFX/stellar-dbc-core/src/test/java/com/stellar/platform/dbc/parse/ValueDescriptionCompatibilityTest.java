package com.stellar.platform.dbc.parse;

import com.stellar.platform.dbc.decode.DecodingResult;
import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.model.DbcValueTable;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.SourceReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multiline {@code VAL_} and Vector named {@code VAL_TABLE_} references.
 * A signal receives a named table through {@code SGTYPE_} and {@code SIG_TYPE_REF_}.
 */
class ValueDescriptionCompatibilityTest {

    private static final String[] HVBAT = {
            "Sleep", "Initialization", "Wait", "Ready", "Drive", "Charge", "Error", "Reserved",
            "Reserved", "Diagnostic", "Shutdown", "Afterrun", "Precharge", "Balancing", "Reserved", "Reserved"
    };

    private static final String DBC = """
            VERSION ""
            NS_ :
            BS_:
            BU_: ECU
            VAL_TABLE_ GroupState 0 "Sleep" 1 "Drive" ;
            BO_ 256 Battery: 8 ECU
             SG_ HVBAT_STATE : 0|8@1+ (1,0) [0|15] "" ECU
             SG_ SPARSE : 8|8@1+ (1,0) [0|15] "" ECU
             SG_ QUOTED : 16|8@1+ (1,0) [0|255] "" ECU
             SG_ OTHER : 24|8@1+ (1,0) [0|255] "" ECU
             SG_ NAMED : 32|8@1+ (1,0) [0|15] "" ECU
             SG_ LATE : 40|8@1+ (1,0) [0|15] "" ECU
             SG_ BARE : 48|8@1+ (1,0) [0|15] "" ECU
             SG_ DUP : 56|8@1+ (1,0) [0|15] "" ECU
            BO_ 257 Share: 4 ECU
             SG_ NAMED2 : 0|8@1+ (1,0) [0|15] "" ECU
             SG_ CLASH : 8|8@1+ (1,0) [0|15] "" ECU
             SG_ GHOST : 16|8@1+ (1,0) [0|15] "" ECU
            BO_ 1552 Power: 8 ECU
             SG_ Selector M : 0|8@1+ (1,0) [0|3] "" ECU
             SG_ Speed m0 : 8|8@1+ (1,0) [0|255] "km/h" ECU
             SG_ Current m1 : 16|8@1+ (1,0) [0|255] "A" ECU
            BO_ 1568 ExtMux: 8 ECU
             SG_ Group M : 0|8@1+ (1,0) [0|255] "" ECU
             SG_ Branch m1 : 8|8@1+ (1,0) [0|255] "" ECU
             SG_ Voltage : 16|8@1+ (0.1,0) [0|25.5] "V" ECU
             SG_ Current m2 : 24|8@1+ (1,0) [0|255] "" ECU
             SG_ Speed m0 : 32|8@1+ (1,0) [0|255] "" ECU
            SG_MUL_VAL_ 1568 Branch Group 1-1 ;
            SG_MUL_VAL_ 1568 Current Group 1-1 ;
            SG_MUL_VAL_ 1568 Current Branch 2-2 ;
            SG_MUL_VAL_ 1568 Speed Group 0-0 ;
            SGTYPE_ HvbatType : 8@1+ (5,0) [0|15] "" 0 , HvbatState ;
            SGTYPE_ GroupType : 8@1+ (9,0) [0|1] "" 0 , GroupState ;
            SGTYPE_ LateType : 8@1+ (1,0) [0|15] "" 0 , LateState ;
            SGTYPE_ GhostType : 8@1+ (1,0) [0|1] "" 0 , NotATable ;
            SIG_TYPE_REF_ 256 NAMED : HvbatType ;
            SIG_TYPE_REF_ 256 LATE : LateType ;
            SIG_TYPE_REF_ 256 BARE : NoSuchType ;
            SIG_TYPE_REF_ 257 NAMED2 : HvbatType ;
            SIG_TYPE_REF_ 257 CLASH : HvbatType ;
            SIG_TYPE_REF_ 257 GHOST : GhostType ;
            SIG_TYPE_REF_ 1552 Selector : GroupType ;
            SIG_TYPE_REF_ 1568 Group : GroupType ;
            VAL_ 256 HVBAT_STATE 0 "Sleep"
            1 "Initialization"
            2 "Wait"
            3 "Ready"
            4 "Drive"
            5 "Charge"
            6 "Error"
            7 "Reserved"
            8 "Reserved"
            9 "Diagnostic"
            10 "Shutdown"
            11 "Afterrun"
            12 "Precharge"
            13 "Balancing"
            14 "Reserved"
            15 "Reserved" ;
            VAL_ 256 SPARSE 0 "Sleep" 15 "Reserved" ;
            VAL_ 256 QUOTED 0 "say \\"Hi\\", ok; next" 1 "a/b" ;
            VAL_ 256 OTHER 0 "Only other" ;
            VAL_ 256 DUP 1 "one" 1 "two" ;
            VAL_ 257 CLASH 0 "Local" ;
            VAL_ 1568 Voltage 0 "low
            rail" 1 "high" ;
            VAL_ 1568 Current 4 "Flowing" ;
            VAL_TABLE_ HvbatState 0 "Sleep" 1 "Initialization" 2 "Wait" 3 "Ready" 4 "Drive" 5 "Charge" 6 "Error" 7 "Reserved" 8 "Reserved" 9 "Diagnostic" 10 "Shutdown" 11 "Afterrun" 12 "Precharge" 13 "Balancing" 14 "Reserved" 15 "Reserved" ;
            VAL_TABLE_ LateState 0 "Before" 4 "After" ;
            VAL_TABLE_ GroupState 9 "nope" ;
            """;

    @TempDir
    Path temp;

    @Test
    void multilineAndNamedTablesKeepRawCodesAndDoNotLeak() throws Exception {
        DbcDatabase db = database();
        DbcSignal state = signal(db, 256, "HVBAT_STATE");
        assertNull(state.valueTable().tableName());
        assertEquals(1.0, state.factor(), 0.0);
        for (int code = 0; code < HVBAT.length; code++) {
            assertEquals(HVBAT[code], state.valueTable().textFor(code).orElseThrow(), "code " + code);
        }

        DbcSignal named = signal(db, 256, "NAMED");
        DbcSignal named2 = signal(db, 257, "NAMED2");
        assertEquals("HvbatState", named.valueTable().tableName());
        assertEquals("HvbatState", named2.valueTable().tableName());
        assertEquals(1.0, named.factor(), 0.0);
        assertEquals(state.valueTable().entries(), named.valueTable().entries());
        assertNotSame(named.valueTable().entries(), named2.valueTable().entries());
        assertEquals("Only other", signal(db, 256, "OTHER").valueTable().textFor(0).orElseThrow());
        assertFalse(signal(db, 256, "OTHER").valueTable().entries().containsValue("Sleep"));

        assertEquals("Sleep", signal(db, 256, "SPARSE").valueTable().textFor(0).orElseThrow());
        assertEquals("Reserved", signal(db, 256, "SPARSE").valueTable().textFor(15).orElseThrow());
        assertTrue(signal(db, 256, "SPARSE").valueTable().textFor(1).isEmpty());
        assertEquals("say \"Hi\", ok; next", signal(db, 256, "QUOTED").valueTable().textFor(0).orElseThrow());
        assertEquals("a/b", signal(db, 256, "QUOTED").valueTable().textFor(1).orElseThrow());
        assertEquals("two", signal(db, 256, "DUP").valueTable().textFor(1).orElseThrow());
        assertEquals(Map.of(0L, "Before", 4L, "After"), signal(db, 256, "LATE").valueTable().entries());
        assertEquals("LateState", signal(db, 256, "LATE").valueTable().tableName());
        assertTrue(signal(db, 256, "BARE").valueTable().entries().isEmpty());
        assertTrue(signal(db, 257, "GHOST").valueTable().entries().isEmpty());
        assertEquals("Local", signal(db, 257, "CLASH").valueTable().textFor(0).orElseThrow());
        assertNull(signal(db, 257, "CLASH").valueTable().tableName());
        assertEquals("Sleep", signal(db, 1552, "Selector").valueTable().textFor(0).orElseThrow());
        assertEquals("low\nrail", signal(db, 1568, "Voltage").valueTable().textFor(0).orElseThrow());

        String diagnostics = String.join("\n", db.diagnostics());
        assertTrue(diagnostics.contains("Duplicate raw code 1"), diagnostics);
        assertTrue(diagnostics.contains("later label 'two' was kept"), diagnostics);
        assertTrue(diagnostics.contains("unknown signal type 'NoSuchType'"), diagnostics);
        assertTrue(diagnostics.contains("Unresolved VAL_TABLE_ 'NotATable'"), diagnostics);
        assertTrue(diagnostics.contains("conflicts with VAL_TABLE_ 'HvbatState'"), diagnostics);
        assertTrue(diagnostics.contains("Duplicate VAL_TABLE_ 'GroupState'"), diagnostics);
        assertTrue(diagnostics.contains("The first definition was kept"), diagnostics);
        assertFalse(diagnostics.contains("NoSuchType") && signal(db, 256, "OTHER").valueTable().textFor(0).isEmpty());
    }

    @Test
    void selectorMatchUsesTheRawCodeOnSimpleAndExtendedFrames() {
        DbcDatabase db = databaseUnchecked();
        DefaultDbcDecoder decoder = new DefaultDbcDecoder();

        DecodingResult sleep = decoder.decode(frame(1552, new byte[] {0, 40, 0, 0, 0, 0, 0, 0}), db);
        CanonicalSignal selector = one(sleep, "Selector");
        assertEquals(0L, selector.rawValue());
        assertEquals(0.0, selector.physicalValue(), 0.0);
        assertEquals("Sleep", selector.textualValue());
        assertEquals(40L, one(sleep, "Speed").rawValue());
        assertTrue(sleep.signals().stream().noneMatch(signal -> "Current".equals(signal.signalName())));

        DecodingResult drive = decoder.decode(frame(1552, new byte[] {1, 40, 7, 0, 0, 0, 0, 0}), db);
        assertEquals(1L, one(drive, "Selector").rawValue());
        assertEquals("Drive", one(drive, "Selector").textualValue());
        assertEquals(7L, one(drive, "Current").rawValue());
        assertTrue(drive.signals().stream().noneMatch(signal -> "Speed".equals(signal.signalName())));

        DecodingResult extendedSleep = decoder.decode(frame(1568, new byte[] {0, 0, 0, 0, 9, 0, 0, 0}), db);
        assertEquals(0L, one(extendedSleep, "Group").rawValue());
        assertEquals("Sleep", one(extendedSleep, "Group").textualValue());
        assertEquals(0.0, one(extendedSleep, "Voltage").physicalValue(), 0.0);
        assertEquals("low\nrail", one(extendedSleep, "Voltage").textualValue());
        assertEquals(9L, one(extendedSleep, "Speed").rawValue());
        assertTrue(extendedSleep.signals().stream().noneMatch(signal -> "Current".equals(signal.signalName())));
        assertTrue(extendedSleep.signals().stream().noneMatch(signal -> "Branch".equals(signal.signalName())));

        DecodingResult extendedDrive = decoder.decode(frame(1568, new byte[] {1, 2, 100, 4, 9, 0, 0, 0}), db);
        assertEquals(1L, one(extendedDrive, "Group").rawValue());
        assertEquals("Drive", one(extendedDrive, "Group").textualValue());
        assertEquals(2L, one(extendedDrive, "Branch").rawValue());
        assertEquals(4L, one(extendedDrive, "Current").rawValue());
        assertEquals("Flowing", one(extendedDrive, "Current").textualValue());
        assertTrue(extendedDrive.signals().stream().noneMatch(signal -> "Speed".equals(signal.signalName())));
        assertEquals(100_000_000L, one(extendedDrive, "Group").timestampNanos());
    }

    @Test
    void unterminatedStatementDoesNotConsumeTheNextMessage() throws Exception {
        Path file = temp.resolve("open.dbc");
        Files.writeString(file, """
                VERSION ""
                BU_: ECU
                BO_ 256 Battery: 1 ECU
                 SG_ Mode : 0|8@1+ (1,0) [0|1] "" ECU
                VAL_ 256 Mode 0 "Off"
                BO_ 257 Next: 1 ECU
                 SG_ Keep : 0|8@1+ (1,0) [0|1] "" ECU
                VAL_ 257 Keep 0 "Kept" ;
                VAL_ 256 Mode HvbatState ;
                """);
        DbcDatabase db = new DbcParser().parse(file);
        assertTrue(signal(db, 256, "Mode").valueTable().entries().isEmpty());
        assertEquals("Kept", signal(db, 257, "Keep").valueTable().textFor(0).orElseThrow());
        String diagnostics = String.join("\n", db.diagnostics());
        assertTrue(diagnostics.contains(DbcParser.MULTILINE_VAL_WARNING_PREFIX), diagnostics);
        assertTrue(diagnostics.contains("no terminating semicolon"), diagnostics);
        assertTrue(diagnostics.contains("names 'HvbatState'"), diagnostics);
        assertTrue(diagnostics.contains("SIG_TYPE_REF_"), diagnostics);
        assertTrue(String.join("\n", DbcParser.importWarnings(db)).contains("1 value description was not loaded"));
    }

    private DbcDatabase database() throws Exception {
        Path file = temp.resolve("values.dbc");
        Files.writeString(file, DBC);
        return new DbcParser().parse(file);
    }

    private DbcDatabase databaseUnchecked() {
        try {
            return database();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static DbcSignal signal(DbcDatabase db, long id, String name) {
        return db.findMessage(id, false).orElseThrow().signals().stream()
                .filter(signal -> signal.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static CanonicalSignal one(DecodingResult result, String name) {
        for (CanonicalSignal signal : result.signals()) {
            if (name.equals(signal.signalName())) {
                return signal;
            }
        }
        throw new AssertionError(name + " missing from " + result.diagnostics());
    }

    private static CanonicalCanFrame frame(long id, byte[] data) {
        return new CanonicalCanFrame(
                0, 100_000_000L, 1, id, false, FrameDirection.RX, data.length, data,
                FrameQuality.OK, SourceReference.ofLine(1));
    }
}
