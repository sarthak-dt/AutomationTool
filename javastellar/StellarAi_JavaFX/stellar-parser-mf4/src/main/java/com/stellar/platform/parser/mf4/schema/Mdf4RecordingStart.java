package com.stellar.platform.parser.mf4.schema;

import com.stellar.platform.parser.mf4.block.MdfBlockHeader;
import com.stellar.platform.parser.mf4.block.MdfBlockHeaderReader;
import com.stellar.platform.parser.mf4.block.MdfBlockType;
import com.stellar.platform.parser.mf4.id.MdfIdentification;
import com.stellar.platform.parser.mf4.id.MdfIdentificationReader;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.spi.ParserException;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Read-only ASAM MDF 4 HD recording start.
 * <p>
 * {@code hd_start_time} is a uint64 nanosecond count since 1970-01-01 00:00:00.
 * {@code hd_time_flags} bit 0 clear means that count is UTC. Bit 0 set means the same
 * count encodes local civil time. Bit 1 set means {@code hd_tz_offset_min} and
 * {@code hd_dst_offset_min} are valid, and local time is UTC plus both offsets.
 * A UTC count is not shifted by those offsets. A local count without valid offsets
 * cannot be placed on an absolute timeline. Bits other than 0 and 1, a data section
 * shorter than the 16-byte time prefix, or a count that does not fit in a signed
 * long are treated as unavailable. This reader does not use the file name, the file
 * timestamps, or the current clock, and it does not change channel samples.
 */
public final class Mdf4RecordingStart {

    private Mdf4RecordingStart() {
    }

    public record UtcOrigin(long epochNanos) {
    }

    public static Optional<UtcOrigin> read(Path path) {
        try (MdfFileAccess access = MdfFileAccess.open(path)) {
            return read(access);
        } catch (ParserException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    public static Optional<UtcOrigin> read(MdfFileAccess access) {
        try {
            Optional<HeaderTime> header = headerTime(access);
            if (header.isEmpty()) {
                return Optional.empty();
            }
            HeaderTime time = header.get();
            if (!time.localTime) {
                if (time.offsetsValid && !offsetsInRange(time.tzOffsetMin, time.dstOffsetMin)) {
                    return Optional.empty();
                }
                return Optional.of(new UtcOrigin(time.start));
            }
            if (!time.offsetsValid || !offsetsInRange(time.tzOffsetMin, time.dstOffsetMin)) {
                return Optional.empty();
            }
            long offsetNanos = Math.multiplyExact((long) time.tzOffsetMin + time.dstOffsetMin, 60_000_000_000L);
            return Optional.of(new UtcOrigin(Math.subtractExact(time.start, offsetNanos)));
        } catch (ParserException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    /**
     * The HD start count as stored.
     * When the UTC flag is clear, that count is UTC.
     * When the local flag is set, the same count encodes local civil time and is not shifted.
     * {@link #read} is unchanged: a UTC count stays UTC, and a local count is converted to UTC.
     */
    public static Optional<Long> sourceCount(Path path) {
        return sourceHeader(path).map(SourceHeader::startNanos);
    }

    /**
     * HD time fields for display. The count is not shifted here.
     * A valid offset that is outside ±14 hours is unavailable.
     */
    public static Optional<SourceHeader> sourceHeader(Path path) {
        try (MdfFileAccess access = MdfFileAccess.open(path)) {
            Optional<HeaderTime> header = headerTime(access);
            if (header.isEmpty()) {
                return Optional.empty();
            }
            HeaderTime time = header.get();
            if (time.offsetsValid && !offsetsInRange(time.tzOffsetMin, time.dstOffsetMin)) {
                return Optional.empty();
            }
            return Optional.of(new SourceHeader(
                    time.start, time.localTime, time.offsetsValid, time.tzOffsetMin, time.dstOffsetMin));
        } catch (ParserException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    public record SourceHeader(
            long startNanos,
            boolean localTime,
            boolean offsetsValid,
            int tzOffsetMin,
            int dstOffsetMin
    ) {
    }

    private record HeaderTime(long start, boolean localTime, boolean offsetsValid, int tzOffsetMin, int dstOffsetMin) {
    }

    private static Optional<HeaderTime> headerTime(MdfFileAccess access) throws ParserException {
        MdfIdentification identification = MdfIdentificationReader.read(access);
        if (!identification.isMdf4()) {
            return Optional.empty();
        }
        MdfBlockHeader header = MdfBlockHeaderReader.read(access, MdfIdentification.HD_BLOCK_OFFSET);
        if (header.type() != MdfBlockType.HD || header.declaredDataLength() < 16L) {
            return Optional.empty();
        }
        ByteBuffer data = access.readOrdered(header.dataSectionOffset(), 16, "HD-time");
        long start = data.getLong();
        if (start < 0L) {
            return Optional.empty();
        }
        int tzOffsetMin = data.getShort();
        int dstOffsetMin = data.getShort();
        int timeFlags = data.get() & 0xFF;
        if ((timeFlags & ~0x03) != 0) {
            return Optional.empty();
        }
        boolean localTime = (timeFlags & 0x01) != 0;
        boolean offsetsValid = (timeFlags & 0x02) != 0;
        return Optional.of(new HeaderTime(start, localTime, offsetsValid, tzOffsetMin, dstOffsetMin));
    }

    private static boolean offsetsInRange(int tzOffsetMin, int dstOffsetMin) {
        return tzOffsetMin >= -840 && tzOffsetMin <= 840 && dstOffsetMin >= -840 && dstOffsetMin <= 840;
    }
}
