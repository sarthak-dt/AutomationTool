package com.stellar.platform.desktop.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericFormatDetectionTest {

    @TempDir
    Path temp;

    @Test
    void mdf330DatAndLogAreRecognizedAndNotRoutedToMdf4() throws Exception {
        Path dat = temp.resolve("Partition 0.dat");
        Path log = temp.resolve("Partition 0.LOG");
        Files.write(dat, mdf3());
        Files.write(log, mdf3());
        for (Path file : new Path[] {dat, log}) {
            FileKindDetector.Inspection inspection = FileKindDetector.inspect(file);
            FileKind expected = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".log")
                    ? FileKind.MEASUREMENT_LOG
                    : FileKind.MEASUREMENT_DAT;
            assertEquals(expected, inspection.kind());
            assertTrue(inspection.recognized());
            assertTrue(inspection.parserSupported());
            assertEquals("MDF 3.30", inspection.uiLabel());
            assertFalse(MeasurementSourceCapability.requiresDefinition(inspection.kind()));
            assertTrue(MeasurementSourceCapability.supportsDirectProcessing(inspection.kind()));
        }
    }

    @Test
    void mdf4RenamedDatIsSupportedMdf() throws Exception {
        Path file = temp.resolve("vehicle.dat");
        Files.write(file, mdf4());
        FileKindDetector.Inspection inspection = FileKindDetector.inspect(file);
        assertEquals(FileKind.MEASUREMENT_DAT, inspection.kind());
        assertTrue(inspection.parserSupported());
        assertEquals("MDF 4.10", inspection.uiLabel());
        assertFalse(MeasurementSourceCapability.requiresDefinition(FileKind.MEASUREMENT_DAT));
    }

    @Test
    void randomTextMf4IsUnknown() throws Exception {
        Path file = temp.resolve("notes.MF4");
        Files.writeString(file, "hello\n");
        FileKindDetector.Inspection inspection = FileKindDetector.inspect(file);
        assertEquals(FileKind.UNKNOWN, inspection.kind());
        assertTrue(inspection.detected().unknownMessage().contains(
                "StellarAI could not determine the measurement structure automatically."));
    }

    @Test
    void ascContentInDatAndBlfMagicInLog() throws Exception {
        Path asc = temp.resolve("trace.dat");
        Files.writeString(asc, "date Thu Jan 01 12:00:00.000 am 2026\nbase hex timestamps absolute\n");
        FileKindDetector.Inspection ascInspection = FileKindDetector.inspect(asc);
        assertEquals(FileKind.MEASUREMENT_DAT, ascInspection.kind());
        assertEquals("asc", ascInspection.detected().content().formatKey());
        assertFalse(MeasurementSourceCapability.requiresDefinition(FileKind.MEASUREMENT_DAT));

        Path blf = temp.resolve("capture.log");
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'L').put((byte) 'O').put((byte) 'G').put((byte) 'G').putInt(144);
        Files.write(blf, buf.array());
        FileKindDetector.Inspection blfInspection = FileKindDetector.inspect(blf);
        assertEquals(FileKind.MEASUREMENT_LOG, blfInspection.kind());
        assertEquals("blf", blfInspection.detected().content().formatKey());
        assertFalse(MeasurementSourceCapability.requiresDefinition(FileKind.MEASUREMENT_LOG));
    }

    @Test
    void dbcExtensionStillDetectsDefinition() throws Exception {
        Path dbc = temp.resolve("engine.dbc");
        Files.writeString(dbc, "VERSION \"\"\n\n");
        assertEquals(FileKind.DEFINITION_DBC, FileKindDetector.detect(dbc));
    }

    private static byte[] mdf3() {
        byte[] raw = mdf3Physical();
        return raw;
    }

    private static byte[] mdf4() {
        byte[] raw = new byte[64];
        writeAscii(raw, 0, "MDF     ");
        writeAscii(raw, 8, "4.10");
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putShort(28, (short) 410);
        return raw;
    }

    private static byte[] mdf3Physical() {
        ByteBuffer buf = ByteBuffer.allocate(220).order(ByteOrder.LITTLE_ENDIAN);
        byte[] id = new byte[64];
        writeAscii(id, 0, "MDF     ");
        writeAscii(id, 8, "3.30");
        writeAscii(id, 16, "vSpy");
        ByteBuffer.wrap(id).order(ByteOrder.LITTLE_ENDIAN).putShort(28, (short) 330);
        buf.put(id);
        buf.position(64);
        buf.put((byte) 'H').put((byte) 'D').putShort((short) 208).putInt(80);
        buf.position(80);
        buf.put((byte) 'D').put((byte) 'G').putShort((short) 28).putInt(0).putInt(110);
        buf.position(110);
        buf.put((byte) 'C').put((byte) 'G').putShort((short) 26).putInt(0).putInt(140);
        buf.position(140);
        buf.put((byte) 'C').put((byte) 'N').putShort((short) 160).putInt(0).putInt(200);
        buf.position(164);
        buf.putShort((short) 0);
        buf.position(200);
        buf.put((byte) 'C').put((byte) 'C');
        return buf.array();
    }

    private static void writeAscii(byte[] raw, int offset, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, raw, offset, bytes.length);
    }
}
