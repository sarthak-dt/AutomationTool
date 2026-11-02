package com.stellar.platform.processing.map.time;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.DateTimeException;
import java.time.LocalDateTime;

/**
 * Reads the BLF LOGG SYSTEMTIME at file offset 40, the same fields the BLF header parser reads.
 * The header has no timezone, so the clock stays local.
 * Object timestamps remain elapsed offsets and are not added to this start.
 */
final class BlfRecordingStartAdapter {

    private BlfRecordingStartAdapter() {
    }

    static TimestampObservation read(String measurementId, byte[] prefix) {
        if (prefix == null || prefix.length < 60
                || prefix[0] != 'L' || prefix[1] != 'O' || prefix[2] != 'G' || prefix[3] != 'G') {
            return TimestampObservation.none(measurementId, "BLF header is missing");
        }
        ByteBuffer buffer = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN);
        int year = Short.toUnsignedInt(buffer.getShort(40));
        int month = Short.toUnsignedInt(buffer.getShort(42));
        int day = Short.toUnsignedInt(buffer.getShort(46));
        int hour = Short.toUnsignedInt(buffer.getShort(48));
        int minute = Short.toUnsignedInt(buffer.getShort(50));
        int second = Short.toUnsignedInt(buffer.getShort(52));
        int millis = Short.toUnsignedInt(buffer.getShort(54));
        if (year <= 0) {
            return TimestampObservation.none(measurementId, "BLF recording start is absent");
        }
        try {
            LocalDateTime civil = LocalDateTime.of(year, month, day, hour, minute, second, millis * 1_000_000);
            return new TimestampObservation(
                    measurementId, TimestampSource.BLF_HEADER, TimestampRawKind.CIVIL_LOCAL, TimeUnitKind.NONE,
                    null, false, true, 0L, civil, "BLF SYSTEMTIME has no timezone");
        } catch (DateTimeException ex) {
            return TimestampObservation.none(measurementId, "BLF SYSTEMTIME is not a calendar value");
        }
    }
}
