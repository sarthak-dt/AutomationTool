package com.stellar.platform.parser.mf4.schema;

import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mdf4RecordingStartTest {

    private static final long UTC_START = 1_674_028_365_000_000_000L;

    @TempDir
    Path temp;

    @Test
    void utcOriginIgnoresAValidSourceOffset() throws Exception {
        Path file = temp.resolve("utc-offset.mf4");
        write(file, UTC_START, (short) 300, (short) 0, 2);
        assertEquals(UTC_START, Mdf4RecordingStart.read(file).orElseThrow().epochNanos());
    }

    @Test
    void utcOriginWithoutOffsetsStaysOnTheHeaderCount() throws Exception {
        Path file = temp.resolve("utc-plain.mf4");
        write(file, UTC_START, (short) 0, (short) 0, 0);
        assertEquals(UTC_START, Mdf4RecordingStart.read(file).orElseThrow().epochNanos());
    }

    @Test
    void localOriginSubtractsTimezoneAndDst() throws Exception {
        Path file = temp.resolve("local.mf4");
        long fiveHours = 300L * 60_000_000_000L;
        long oneHour = 60L * 60_000_000_000L;
        write(file, UTC_START + fiveHours + oneHour, (short) 300, (short) 60, 3);
        assertEquals(UTC_START, Mdf4RecordingStart.read(file).orElseThrow().epochNanos());
    }

    @Test
    void localTimeWithoutOffsetsIsUnavailable() throws Exception {
        Path file = temp.resolve("local-ambiguous.mf4");
        write(file, UTC_START, (short) 300, (short) 0, 1);
        assertTrue(Mdf4RecordingStart.read(file).isEmpty());
    }

    @Test
    void unknownTimeFlagBitsAreUnavailable() throws Exception {
        Path file = temp.resolve("flags.mf4");
        write(file, UTC_START, (short) 0, (short) 0, 4);
        assertTrue(Mdf4RecordingStart.read(file).isEmpty());
    }

    @Test
    void anOffsetOutsideTheMdfRangeIsUnavailable() throws Exception {
        Path file = temp.resolve("bad-offset.mf4");
        write(file, UTC_START, (short) 841, (short) 0, 2);
        assertTrue(Mdf4RecordingStart.read(file).isEmpty());
    }

    @Test
    void aShortTimePrefixIsUnavailable() throws Exception {
        Path file = temp.resolve("short.mf4");
        try (SyntheticMdf4Builder builder = SyntheticMdf4Builder.create(file)) {
            builder.writeIdBlock("4.10", 410, 0);
            builder.writeBlock("##HD", new long[6], new byte[8]);
        }
        assertTrue(Mdf4RecordingStart.read(file).isEmpty());
    }

    @Test
    void aMissingHeaderIsUnavailable() throws Exception {
        Path file = temp.resolve("no-hd.mf4");
        try (SyntheticMdf4Builder builder = SyntheticMdf4Builder.create(file)) {
            builder.writeIdBlock("4.10", 410, 0);
        }
        assertTrue(Mdf4RecordingStart.read(file).isEmpty());
    }

    @Test
    void twoRecordingsKeepIndependentOrigins() throws Exception {
        Path first = temp.resolve("a.mf4");
        Path second = temp.resolve("b.mf4");
        write(first, UTC_START, (short) 300, (short) 0, 2);
        write(second, UTC_START + 86_400_000_000_000L, (short) 0, (short) 0, 0);
        long left = Mdf4RecordingStart.read(first).orElseThrow().epochNanos();
        long right = Mdf4RecordingStart.read(second).orElseThrow().epochNanos();
        assertEquals(UTC_START, left);
        assertEquals(86_400_000_000_000L, right - left);
    }

    private static void write(Path file, long start, short tz, short dst, int flags) throws Exception {
        try (SyntheticMdf4Builder builder = SyntheticMdf4Builder.create(file)) {
            builder.writeIdBlock("4.10", 410, 0);
            builder.writeHd(0, 0, 0, start, tz, dst, flags);
        }
    }
}
