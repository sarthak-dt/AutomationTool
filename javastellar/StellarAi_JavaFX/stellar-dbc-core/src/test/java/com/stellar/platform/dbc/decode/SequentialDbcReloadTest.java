package com.stellar.platform.dbc.decode;

import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.SourceReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SequentialDbcReloadTest {

    @TempDir
    Path tempDir;

    @Test
    void secondDbcCompletelyReplacesMessageLookup() throws Exception {
        Path dbcA = tempDir.resolve("a.dbc");
        Path dbcB = tempDir.resolve("b.dbc");
        Files.writeString(dbcA, String.join("\n",
                "VERSION \"\"",
                "NS_ :",
                "BS_:",
                "BU_:",
                "BO_ 100 A_MSG: 8 Vector__XXX",
                " SG_ Alpha : 0|8@1+ (1,0) [0|255] \"\" Vector__XXX"
        ), StandardCharsets.UTF_8);
        Files.writeString(dbcB, String.join("\n",
                "VERSION \"\"",
                "NS_ :",
                "BS_:",
                "BU_:",
                "BO_ 200 B_MSG: 8 Vector__XXX",
                " SG_ Beta : 0|8@1+ (1,0) [0|255] \"\" Vector__XXX"
        ), StandardCharsets.UTF_8);

        DbcParser parser = new DbcParser();
        DbcDatabase a = parser.parse(dbcA);
        DbcDatabase b = parser.parse(dbcB);
        DefaultDbcDecoder decoder = new DefaultDbcDecoder();

        CanonicalCanFrame f100 = frame(100L, new byte[] {7, 0, 0, 0, 0, 0, 0, 0});
        CanonicalCanFrame f200 = frame(200L, new byte[] {9, 0, 0, 0, 0, 0, 0, 0});

        DecodingResult withA = decoder.decode(f100, a);
        assertEquals(DecodeStatus.DECODED, withA.status());
        assertEquals("Alpha", withA.signals().get(0).signalName());
        assertEquals(DecodeStatus.UNMATCHED, decoder.decode(f200, a).status());

        DecodingResult withB = decoder.decode(f200, b);
        assertEquals(DecodeStatus.DECODED, withB.status());
        assertEquals("Beta", withB.signals().get(0).signalName());
        assertEquals(DecodeStatus.UNMATCHED, decoder.decode(f100, b).status());
        assertTrue(b.findMessage(CanIdKey.fromFrame(100L, false)).isEmpty());
    }

    private static CanonicalCanFrame frame(long canId, byte[] payload) {
        return new CanonicalCanFrame(
                1L, 1L, 0, canId, false, FrameDirection.RX, payload.length, payload,
                FrameQuality.VALID, new SourceReference(1, 0)
        );
    }
}
