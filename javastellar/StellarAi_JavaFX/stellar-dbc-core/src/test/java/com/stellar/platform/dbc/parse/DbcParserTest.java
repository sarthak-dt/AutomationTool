package com.stellar.platform.dbc.parse;

import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbcParserTest {

    @Test
    void parsesStandardAndExtendedMessages() throws Exception {
        DbcDatabase db = new DbcParser().parse(resource("dbc/common_decoder.dbc"));
        assertTrue(db.findMessage(0x100, false).isPresent());
        assertTrue(db.findMessage(CanIdKey.fromFrame(0x100, true)).isPresent());
        assertEquals("EngineData", db.findMessage(0x100, false).orElseThrow().name());
        assertEquals("ChassisStatus", db.findMessage(0x100, true).orElseThrow().name());
        assertTrue(db.findMessage(0x700, false).orElseThrow().sizeBytes() == 16);
    }

    @Test
    void rejectsMalformedBoOrSg() {
        DbcParseException ex = assertThrows(DbcParseException.class,
                () -> new DbcParser().parse(resource("dbc/malformed.dbc")));
        assertTrue(ex.getMessage().contains("SG_") || ex.code().name().contains("SG")
                || ex.code() == DbcDiagnosticCode.MALFORMED_DBC
                || ex.code().name().startsWith("DBC_SG_"));
    }

    @Test
    void rejectsBytesThatAreNotADbc() throws Exception {
        Path garbage = Files.createTempFile("corrupt", ".dbc");
        try {
            Files.write(garbage, new byte[]{0x00, 0x01, 0x02, 0x03, 0x41, 0x42});
            DbcParseException ex = assertThrows(DbcParseException.class, () -> new DbcParser().parse(garbage));
            assertEquals(DbcDiagnosticCode.MALFORMED_DBC, ex.code());
            assertTrue(ex.getMessage().contains("not a DBC"));
        } finally {
            Files.deleteIfExists(garbage);
        }
    }

    @Test
    void acceptsSparseDbcWithNoMessages() throws Exception {
        Path sparse = Files.createTempFile("sparse", ".dbc");
        try {
            Files.writeString(sparse, """
                    VERSION ""

                    NS_ :
                    \tCM_
                    BS_:

                    BU_: NodeA
                    """);
            DbcDatabase db = new DbcParser().parse(sparse);
            assertEquals("", db.version());
            assertEquals(List.of("NodeA"), db.nodes());
            assertTrue(db.messages().isEmpty());
        } finally {
            Files.deleteIfExists(sparse);
        }
    }

    @Test
    void rejectsBrokenMessageLineInsideAnOtherwiseValidFile() throws Exception {
        Path broken = Files.createTempFile("broken-bo", ".dbc");
        try {
            Files.writeString(broken, """
                    VERSION ""
                    BU_: NodeA
                    BO_ not-a-message
                    """);
            DbcParseException ex = assertThrows(DbcParseException.class, () -> new DbcParser().parse(broken));
            assertEquals(DbcDiagnosticCode.MALFORMED_DBC, ex.code());
            assertTrue(ex.getMessage().contains("Malformed BO_"));
        } finally {
            Files.deleteIfExists(broken);
        }
    }

    @Test
    void parsesSuppliedCustomerDbc() throws Exception {
        Path customer = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\dbcfiles\\1.dbc");
        Assumptions.assumeTrue(Files.isRegularFile(customer), "customer DBC is not on this machine");
        DbcDatabase db = new DbcParser().parse(customer);
        assertEquals(115, db.messages().size());
        assertEquals(674, db.messages().stream().mapToInt(message -> message.signals().size()).sum());
        assertNull(DbcParser.windows1252Warning(db));
        long described = db.messages().stream()
                .flatMap(message -> message.signals().stream())
                .filter(signal -> !signal.valueTable().entries().isEmpty())
                .count();
        assertEquals(216, described);
        long multiline = db.diagnostics().stream()
                .filter(line -> line.startsWith(DbcParser.MULTILINE_VAL_WARNING_PREFIX))
                .count();
        assertEquals(0, multiline);
        assertTrue(db.findMessage(1894, false).orElseThrow().signals().stream()
                .filter(signal -> signal.name().equals("ETAT_DTC"))
                .anyMatch(signal -> signal.valueTable().textFor(1).orElse("").contains("PERMANENT")));
        assertTrue(db.findMessage(1890, false).orElseThrow().signals().stream()
                .filter(signal -> signal.name().equals("NUMERO_TRAME"))
                .anyMatch(signal -> !signal.valueTable().entries().isEmpty()));
        String visible = String.join("\n", DbcParser.importWarnings(db));
        assertFalse(visible.contains("were not loaded"), visible);
        assertFalse(visible.contains("216"));
    }

    @Test
    void parsesGenericJ1939DbcWithWindows1252Warning() throws Exception {
        Path j1939 = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\NewDBC\\Generic J1939.dbc");
        Assumptions.assumeTrue(Files.isRegularFile(j1939), "Generic J1939 DBC is not on this machine");
        String before = sha256(j1939);
        DbcDatabase db = new DbcParser().parse(j1939);
        int signals = 0;
        int extended = 0;
        for (DbcMessage message : db.messages()) {
            signals += message.signals().size();
            if (message.extended()) {
                extended++;
            }
        }
        assertEquals(79, db.messages().size());
        assertEquals(346, signals);
        assertEquals(79, extended);
        String warning = DbcParser.windows1252Warning(db);
        assertTrue(warning.contains("byte 0x93"), warning);
        assertTrue(warning.contains("line 674"), warning);
        assertTrue(warning.contains("U+201C"), warning);
        assertTrue(warning.startsWith(DbcParser.WINDOWS_1252_WARNING_PREFIX), warning);
        assertTrue(DbcParser.importWarnings(db).getFirst().startsWith(DbcParser.WINDOWS_1252_WARNING_PREFIX));
        assertTrue(db.messages().stream()
                .flatMap(message -> message.signals().stream())
                .filter(signal -> "CruiseCtrlResumeSwitch".equals(signal.name()))
                .anyMatch(signal -> signal.comment() != null && signal.comment().contains("\u201Cresume")));
        assertEquals(before, sha256(j1939));
    }

    @Test
    void acceptsDegreeAndMicroSignsSharedByLatin1AndWindows1252() throws Exception {
        Path file = Files.createTempFile("legacy-units", ".dbc");
        try {
            String text = """
                    VERSION ""
                    BU_: NodeA
                    BO_ 256 Engine: 8 NodeA
                     SG_ Temp : 16|8@1- (1,-40) [-40|215] "\u00B0C" NodeA
                     SG_ Volume : 24|16@1+ (1,0) [0|65534] "\u00B5l" NodeA
                    """;
            Files.write(file, text.getBytes(StandardCharsets.ISO_8859_1));
            DbcDatabase db = new DbcParser().parse(file);
            assertEquals("\u00B0C", db.findMessage(0x100, false).orElseThrow().signals().get(0).unit());
            assertEquals("\u00B5l", db.findMessage(0x100, false).orElseThrow().signals().get(1).unit());
            assertNull(DbcParser.windows1252Warning(db));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void acceptsDefinedWindows1252BytesAndWarns() throws Exception {
        Path file = Files.createTempFile("windows-1252", ".dbc");
        try {
            byte[] raw = """
                    VERSION ""
                    BU_: NodeA
                    BO_ 256 Engine: 8 NodeA
                     SG_ Speed : 0|16@1+ (0.25,0) [0|8000] "rpm" NodeA
                    CM_ SG_ 256 Speed "position \u0000resume";
                    """.getBytes(StandardCharsets.US_ASCII);
            int quote = indexOf(raw, (byte) 0x00);
            raw[quote] = (byte) 0x93;
            Files.write(file, raw);
            DbcDatabase db = new DbcParser().parse(file);
            assertEquals(1, db.messages().size());
            assertEquals(1, db.messages().iterator().next().signals().size());
            assertFalse(db.findMessage(0x100, false).orElseThrow().extended());
            String comment = db.findMessage(0x100, false).orElseThrow().signals().get(0).comment();
            assertTrue(comment.contains("\u201Cresume"), comment);
            String warning = DbcParser.windows1252Warning(db);
            assertTrue(warning.contains("byte 0x93"), warning);
            assertTrue(warning.contains("U+201C"), warning);
            assertTrue(warning.startsWith(DbcParser.WINDOWS_1252_WARNING_PREFIX), warning);
            assertEquals(0.25d, db.findMessage(0x100, false).orElseThrow().signals().get(0).factor());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void rejectsUndefinedWindows1252Bytes() throws Exception {
        for (int undefined : new int[] {0x81, 0x8D, 0x8F, 0x90, 0x9D}) {
            Path file = Files.createTempFile("undefined-1252", ".dbc");
            try {
                byte[] raw = "VERSION \"\u0000\"\r\nBU_: NodeA\r\n".getBytes(StandardCharsets.US_ASCII);
                raw[9] = (byte) undefined;
                Files.write(file, raw);
                DbcParseException ex = assertThrows(DbcParseException.class, () -> new DbcParser().parse(file));
                assertEquals(DbcDiagnosticCode.MALFORMED_DBC, ex.code());
                assertTrue(ex.getMessage().contains("not a defined Windows-1252"), ex.getMessage());
                assertTrue(ex.getMessage().contains("not guessed"), ex.getMessage());
                assertEquals(1L, ex.lineNumber());
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }

    @Test
    void rejectsBrokenUtf8EvenWhenADefinedWindows1252ByteIsPresent() throws Exception {
        Path file = Files.createTempFile("mixed-broken-utf8", ".dbc");
        try {
            byte[] raw = "VERSION \"\u0000\u0000\"\r\nBU_: NodeA\r\n".getBytes(StandardCharsets.US_ASCII);
            raw[9] = (byte) 0x93;
            raw[10] = (byte) 0xC3;
            Files.write(file, raw);
            DbcParseException ex = assertThrows(DbcParseException.class, () -> new DbcParser().parse(file));
            assertEquals(DbcDiagnosticCode.MALFORMED_DBC, ex.code());
            assertTrue(ex.getMessage().contains("not reinterpreted"), ex.getMessage());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void keepsUtf8QuotesWithoutAWindows1252Warning() throws Exception {
        Path file = Files.createTempFile("utf8-quote", ".dbc");
        try {
            String text = """
                    VERSION ""
                    BU_: NodeA
                    BO_ 256 Engine: 8 NodeA
                     SG_ Speed : 0|16@1+ (1,0) [0|0] "" NodeA
                    CM_ SG_ 256 Speed "position \u201Cresume";
                    """;
            Files.writeString(file, text, StandardCharsets.UTF_8);
            DbcDatabase db = new DbcParser().parse(file);
            assertNull(DbcParser.windows1252Warning(db));
            assertTrue(db.findMessage(0x100, false).orElseThrow().signals().get(0).comment().contains("\u201C"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void rejectsBrokenUtf8InsteadOfReinterpretingIt() throws Exception {
        Path file = Files.createTempFile("broken-utf8", ".dbc");
        try {
            byte[] raw = "VERSION \"\u0000\"\r\nBU_: NodeA\r\n".getBytes(StandardCharsets.US_ASCII);
            raw[9] = (byte) 0xC3;
            Files.write(file, raw);
            DbcParseException ex = assertThrows(DbcParseException.class, () -> new DbcParser().parse(file));
            assertEquals(DbcDiagnosticCode.MALFORMED_DBC, ex.code());
            assertTrue(ex.getMessage().contains("not reinterpreted"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void parsesN597DefinitionWithoutChangingTheFile() throws Exception {
        Path n597 = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\MF4Files\\City - Highway_MF4\\Required DBC\\N597_ABSEMS_CAN dbc _Rev7 (1).dbc");
        Assumptions.assumeTrue(Files.isRegularFile(n597), "N597 DBC is not on this machine");
        String before = sha256(n597);
        assertEquals("493BC73398F3FFA8029D9F8FC8C687472A6A5C4F973A8B98AC31631A971FABA2", before);
        DbcDatabase db = new DbcParser().parse(n597);
        assertEquals(28, db.messages().size());
        assertEquals(217, db.messages().stream().mapToInt(message -> message.signals().size()).sum());
        assertNull(DbcParser.windows1252Warning(db));
        assertTrue(db.messages().stream().flatMap(m -> m.signals().stream()).anyMatch(s -> s.unit().contains("\u00B0")));
        assertTrue(db.messages().stream().flatMap(m -> m.signals().stream()).anyMatch(s -> s.unit().contains("\u00B5")));
        assertEquals(before, sha256(n597));
    }

    @Test
    void rejectsDlcAbove64WithoutTruncatingAndKeepsZeroThrough64() throws Exception {
        Path valid = Files.createTempFile("dlc-range", ".dbc");
        Path oversized = Files.createTempFile("dlc-100", ".dbc");
        try {
            Files.writeString(valid, """
                    VERSION ""
                    BU_: N
                    BO_ 1 Wide: 64 N
                     SG_ A : 0|8@1+ (1,0) [0|0] "" N
                    BO_ 2 Empty: 0 N
                    """);
            DbcDatabase ok = new DbcParser().parse(valid);
            assertEquals(64, ok.findMessage(1, false).orElseThrow().sizeBytes());
            assertEquals(0, ok.findMessage(2, false).orElseThrow().sizeBytes());

            Files.writeString(oversized, """
                    VERSION ""
                    BU_: N
                    BO_ 2024 OBD2_DTC_HEADER: 100 Vector__XXX
                     SG_ A : 0|8@1+ (1,0) [0|0] "" Vector__XXX
                    BO_ 256 Kept: 8 N
                     SG_ Speed : 0|8@1+ (0.5,1) [0|0] "" N
                    """);
            DbcParseException ex = assertThrows(DbcParseException.class, () -> new DbcParser().parse(oversized));
            assertEquals(DbcDiagnosticCode.UNSUPPORTED_MESSAGE_SIZE, ex.code());
            assertEquals(3L, ex.lineNumber());
            assertTrue(ex.getMessage().contains("Unsupported DLC 100"), ex.getMessage());
            assertTrue(ex.getMessage().contains("OBD2_DTC_HEADER"), ex.getMessage());
            assertTrue(ex.getMessage().contains("id 2024"), ex.getMessage());
            assertTrue(ex.getMessage().contains(oversized.getFileName().toString()), ex.getMessage());
            assertTrue(ex.getMessage().contains("not truncated"), ex.getMessage());
        } finally {
            Files.deleteIfExists(valid);
            Files.deleteIfExists(oversized);
        }
    }

    @Test
    void rejectsCustomerDlc100AtItsBoLine() throws Exception {
        Path customer = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\Multiple DBC's to match\\can1-11-bit-OBD2-isotp-DTCs-header-v4.0.dbc");
        Assumptions.assumeTrue(Files.isRegularFile(customer), "DLC 100 DBC is not on this machine");
        DbcParseException ex = assertThrows(DbcParseException.class, () -> new DbcParser().parse(customer));
        assertEquals(DbcDiagnosticCode.UNSUPPORTED_MESSAGE_SIZE, ex.code());
        assertEquals(38L, ex.lineNumber());
        assertTrue(ex.getMessage().contains("can1-11-bit-OBD2-isotp-DTCs-header-v4.0.dbc"), ex.getMessage());
        assertTrue(ex.getMessage().contains("OBD2_DTC_HEADER"), ex.getMessage());
        assertTrue(ex.getMessage().contains("id 2024"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Unsupported DLC 100"), ex.getMessage());
        assertTrue(ex.getMessage().contains("not truncated"), ex.getMessage());
    }

    @Test
    void loadsMultilineValAndKeepsTheSingleLineDescription() throws Exception {
        Path file = Files.createTempFile("multiline-val", ".dbc");
        try {
            Files.writeString(file, """
                    VERSION ""
                    BU_: N
                    BO_ 256 M: 8 N
                     SG_ Mode : 0|8@1+ (1,0) [0|0] "" N
                     SG_ Speed : 8|8@1+ (0.1,0) [0|0] "" N
                    VAL_ 256 Mode 0 "Off" 1 "On" ;
                    VAL_ 256 Speed 1 "fast
                    state" 0 "slow" ;
                    SIG_VALTYPE_ 256 Speed : 1 ;
                    """);
            DbcDatabase db = new DbcParser().parse(file);
            assertEquals(2, db.findMessage(256, false).orElseThrow().signals().size());
            assertEquals(0.1d, db.findMessage(256, false).orElseThrow().signals().stream()
                    .filter(signal -> signal.name().equals("Speed"))
                    .findFirst().orElseThrow().factor());
            assertEquals(2, db.findMessage(256, false).orElseThrow().signals().stream()
                    .filter(signal -> signal.name().equals("Mode"))
                    .findFirst().orElseThrow().valueTable().entries().size());
            var speed = db.findMessage(256, false).orElseThrow().signals().stream()
                    .filter(signal -> signal.name().equals("Speed"))
                    .findFirst().orElseThrow();
            assertEquals("fast\nstate", speed.valueTable().textFor(1).orElseThrow());
            assertEquals("slow", speed.valueTable().textFor(0).orElseThrow());
            assertEquals(com.stellar.platform.dbc.model.DbcRawEncoding.INTEGER,
                    db.findMessage(256, false).orElseThrow().signals().stream()
                            .filter(signal -> signal.name().equals("Speed"))
                            .findFirst().orElseThrow().rawEncoding());
            assertTrue(db.diagnostics().stream().noneMatch(line ->
                    line.startsWith(DbcParser.MULTILINE_VAL_WARNING_PREFIX)));
            String visible = String.join("\n", DbcParser.importWarnings(db));
            assertFalse(visible.contains("was not loaded"), visible);
            assertTrue(visible.contains("signal 'Speed'"), visible);
            assertTrue(visible.contains("integer decoding"), visible);
            assertFalse(visible.contains("Unrecognized DBC line"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static String sha256(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        return HexFormat.of().formatHex(digest).toUpperCase(Locale.ROOT);
    }

    private static int indexOf(byte[] raw, byte value) {
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == value) {
                return i;
            }
        }
        throw new IllegalStateException("byte not found");
    }

    private static Path resource(String name) throws Exception {
        return Path.of(DbcParserTest.class.getClassLoader().getResource(name).toURI());
    }
}
