package com.stellar.platform.server.decoder;

import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecoderRecordingClockTest {

    private static final long UTC_START = 1_674_028_365_000_000_000L;
    private static final long SAMPLE_NANOS = 1_394_950_000L;

    @TempDir
    Path temp;

    @Test
    void validPositiveOffsetIsAppliedOnce() throws Exception {
        Path file = header(temp.resolve("plus-five.mf4"), (short) 300, (short) 0, 2);
        DecoderRecordingClock.Read read = DecoderRecordingClock.read(file, "mf4");
        assertEquals("2023-01-18 12:52:45.000", read.clock().format(0));
        assertEquals("2023-01-18 12:52:46.394", read.clock().format(SAMPLE_NANOS));
    }

    @Test
    void invalidOffsetFlagIsNotApplied() throws Exception {
        Path file = header(temp.resolve("invalid.mf4"), (short) 300, (short) 0, 0);
        DecoderRecordingClock.Read read = DecoderRecordingClock.read(file, "mf4");
        assertEquals("2023-01-18 07:52:45.000", read.clock().format(0));
        assertEquals("2023-01-18 07:52:46.394", read.clock().format(SAMPLE_NANOS));
    }

    @Test
    void localTimeFlagIsNotShiftedAgain() throws Exception {
        Path localOnly = header(temp.resolve("local-flag.mf4"), (short) 300, (short) 0, 1);
        Path localWithOffset = header(temp.resolve("local-offset.mf4"), (short) 300, (short) 60, 3);
        assertEquals("2023-01-18 07:52:45.000", DecoderRecordingClock.read(localOnly, "mf4").clock().format(0));
        DecoderRecordingClock.Read read = DecoderRecordingClock.read(localWithOffset, "mf4");
        assertEquals("2023-01-18 07:52:45.000", read.clock().format(0));
        assertEquals("2023-01-18 07:52:46.394", read.clock().format(SAMPLE_NANOS));
    }

    @Test
    void daylightOffsetIsAddedToTheTimezoneOffset() throws Exception {
        Path file = header(temp.resolve("dst.mf4"), (short) 60, (short) 60, 2);
        assertEquals("2023-01-18 09:52:45.000", DecoderRecordingClock.read(file, "mf4").clock().format(0));
    }

    @Test
    void zeroOffsetLeavesTheUtcCount() throws Exception {
        Path file = header(temp.resolve("zero.mf4"), (short) 0, (short) 0, 2);
        assertEquals("2023-01-18 07:52:45.000", DecoderRecordingClock.read(file, "mf4").clock().format(0));
    }

    @Test
    void negativeOffsetMovesTheUtcCountBackward() throws Exception {
        Path file = header(temp.resolve("minus-five.mf4"), (short) -300, (short) 0, 2);
        assertEquals("2023-01-18 02:52:45.000", DecoderRecordingClock.read(file, "mf4").clock().format(0));
    }

    @Test
    void outOfRangeOffsetStaysUnavailable() throws Exception {
        Path file = header(temp.resolve("wide.mf4"), (short) 900, (short) 0, 2);
        assertFalse(DecoderRecordingClock.read(file, "mf4").clock().available());
    }

    private static Path header(Path file, short tz, short dst, int flags) throws Exception {
        try (SyntheticMdf4Builder builder = SyntheticMdf4Builder.create(file)) {
            builder.writeIdBlock("4.10", 410, 0);
            builder.writeHd(0, 0, 0, UTC_START, tz, dst, flags);
        }
        return file;
    }

    @Test
    void mdf3DatDoesNotUseTheMdf4HeaderClock() throws Exception {
        Path file = Files.createTempFile("mdf3-", ".dat");
        try {
            byte[] prefix = new byte[100];
            System.arraycopy("MDF     ".getBytes(StandardCharsets.US_ASCII), 0, prefix, 0, 8);
            System.arraycopy("3.30    ".getBytes(StandardCharsets.US_ASCII), 0, prefix, 8, 8);
            System.arraycopy("HD".getBytes(StandardCharsets.US_ASCII), 0, prefix, 64, 2);
            System.arraycopy("03:02:2020".getBytes(StandardCharsets.US_ASCII), 0, prefix, 82, 10);
            System.arraycopy("12:39:52".getBytes(StandardCharsets.US_ASCII), 0, prefix, 92, 8);
            Files.write(file, prefix);
            DecoderRecordingClock.Read read = DecoderRecordingClock.read(file, "mf4");
            assertTrue(read.clock().available());
            assertEquals("2020-02-03 12:39:52.000", read.clock().format(0));
            assertEquals("2020-02-03 12:39:52.147", read.clock().format(147_000_000L));
            assertTrue(read.reason().contains("MDF 3"));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
