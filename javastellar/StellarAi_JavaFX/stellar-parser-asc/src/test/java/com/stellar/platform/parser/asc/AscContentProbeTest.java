package com.stellar.platform.parser.asc;

import com.stellar.platform.parser.spi.ContentFormatMatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AscContentProbeTest {

    @TempDir
    Path temp;

    @Test
    void ascHeaderIsDetectedWhenRenamedDat() throws Exception {
        Path file = temp.resolve("trace.dat");
        Files.writeString(file, """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                  0.100000 1 100 Rx d 8 10 27 5A 01 00 00 00 00
                """);
        ContentFormatMatch match = AscContentProbe.probe(file).orElseThrow();
        assertEquals("asc", match.formatKey());
        assertTrue(match.parserSupported());
        assertEquals(Boolean.TRUE, match.requiresDefinition());
        assertEquals(Boolean.TRUE, match.containsRawCan());
        assertTrue(AscContentProbe.parserCanRead(file));
    }

    @Test
    void arbitraryTextLogIsNotAsc() throws Exception {
        Path file = temp.resolve("notes.log");
        Files.writeString(file, "2020-02-03 engine started\nspeed=10\n");
        assertTrue(AscContentProbe.probe(file).isEmpty());
        assertFalse(AscContentProbe.parserCanRead(file));
    }

    @Test
    void missingAscPathStillUsesExtensionHint() {
        assertTrue(AscContentProbe.parserCanRead(temp.resolve("missing.asc")));
        assertFalse(AscContentProbe.parserCanRead(temp.resolve("missing.dat")));
    }
}
