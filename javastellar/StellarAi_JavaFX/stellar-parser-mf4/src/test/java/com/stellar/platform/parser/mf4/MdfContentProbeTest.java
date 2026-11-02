package com.stellar.platform.parser.mf4;

import com.stellar.platform.parser.spi.ContentFormatMatch;
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

class MdfContentProbeTest {

    @TempDir
    Path temp;

    @Test
    void mdf330HeaderIsRecognizedOnDatLogAndXyzButNotParsedAsMdf4() throws Exception {
        for (String name : new String[] {"capture.dat", "capture.DAT", "capture.log", "capture.LOG", "capture.xyz"}) {
            Path file = temp.resolve(name);
            Files.write(file, mdf3Header());
            ContentFormatMatch match = MdfContentProbe.probe(file).orElseThrow();
            assertEquals("3.30", match.version());
            assertEquals("vSpy", match.programIdentifier());
            assertEquals("MDF 3.30", match.displayLabel());
            assertTrue(match.parserSupported());
            assertTrue(MdfContentProbe.parserCanRead(file));
        }
    }

    @Test
    void mdf4HeaderIsSupportedRegardlessOfExtension() throws Exception {
        Path file = temp.resolve("renamed.dat");
        Files.write(file, mdf4Header());
        ContentFormatMatch match = MdfContentProbe.probe(file).orElseThrow();
        assertEquals("4.10", match.version());
        assertTrue(match.parserSupported());
        assertTrue(MdfContentProbe.parserCanRead(file));
        assertEquals(null, match.requiresDefinition());
    }

    @Test
    void randomTextWithMf4ExtensionIsNotMdf() throws Exception {
        Path file = temp.resolve("notes.mf4");
        Files.writeString(file, "this is not a measurement\n");
        assertTrue(MdfContentProbe.probe(file).isEmpty());
        assertFalse(MdfContentProbe.parserCanRead(file));
    }

    @Test
    void mdf3PhysicalChannelsDoNotRequireDbc() throws Exception {
        Path file = temp.resolve("signals.log");
        Files.write(file, mdf3WithOnePhysicalChannel());
        ContentFormatMatch match = MdfContentProbe.probe(file).orElseThrow();
        assertTrue(match.parserSupported());
        assertEquals(Boolean.TRUE, match.containsDecodedSignals());
        assertEquals(Boolean.FALSE, match.containsRawCan());
        assertEquals(Boolean.FALSE, match.requiresDefinition());
    }

    private static byte[] mdf3Header() {
        byte[] raw = new byte[64];
        writeAscii(raw, 0, "MDF     ");
        writeAscii(raw, 8, "3.30");
        writeAscii(raw, 16, "vSpy");
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putShort(28, (short) 330);
        return raw;
    }

    private static byte[] mdf4Header() {
        byte[] raw = new byte[64];
        writeAscii(raw, 0, "MDF     ");
        writeAscii(raw, 8, "4.10");
        writeAscii(raw, 16, "stellar ");
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putShort(28, (short) 410);
        return raw;
    }

    private static byte[] mdf3WithOnePhysicalChannel() {
        ByteBuffer buf = ByteBuffer.allocate(220).order(ByteOrder.LITTLE_ENDIAN);
        byte[] id = mdf3Header();
        buf.put(id);
        buf.position(64);
        buf.put((byte) 'H');
        buf.put((byte) 'D');
        buf.putShort((short) 208);
        buf.putInt(80);
        buf.position(80);
        buf.put((byte) 'D');
        buf.put((byte) 'G');
        buf.putShort((short) 28);
        buf.putInt(0);
        buf.putInt(110);
        buf.position(110);
        buf.put((byte) 'C');
        buf.put((byte) 'G');
        buf.putShort((short) 26);
        buf.putInt(0);
        buf.putInt(140);
        buf.position(140);
        buf.put((byte) 'C');
        buf.put((byte) 'N');
        buf.putShort((short) 160);
        buf.putInt(0);
        buf.putInt(200);
        buf.position(140 + 24);
        buf.putShort((short) 0);
        buf.position(200);
        buf.put((byte) 'C');
        buf.put((byte) 'C');
        return buf.array();
    }

    private static void writeAscii(byte[] raw, int offset, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, raw, offset, Math.min(bytes.length, 8));
    }
}
