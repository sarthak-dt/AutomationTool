package com.stellar.platform.parser.blf;

import com.stellar.platform.parser.spi.ContentFormatMatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlfContentProbeTest {

    @TempDir
    Path temp;

    @Test
    void blfMagicIsDetectedWhenRenamedLog() throws Exception {
        Path file = temp.resolve("capture.LOG");
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'L').put((byte) 'O').put((byte) 'G').put((byte) 'G');
        buf.putInt(144);
        Files.write(file, buf.array());
        ContentFormatMatch match = BlfContentProbe.probe(file).orElseThrow();
        assertEquals("blf", match.formatKey());
        assertEquals(Boolean.TRUE, match.requiresDefinition());
        assertTrue(BlfContentProbe.parserCanRead(file));
    }

    @Test
    void textLogIsNotBlf() throws Exception {
        Path file = temp.resolve("notes.log");
        Files.writeString(file, "LOG started\n");
        assertTrue(BlfContentProbe.probe(file).isEmpty());
        assertFalse(BlfContentProbe.parserCanRead(file));
    }
}
