package com.stellar.platform.processing.map.time;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Reads the MDF header recording start. Does not decode channel samples.
 * MDF 4 uses {@code hd_start_time_ns} and the HD time flags.
 * MDF 3 uses the HD civil date and time strings, which have no timezone.
 */
final class Mf4RecordingStartAdapter {

    private Mf4RecordingStartAdapter() {
    }

    static TimestampObservation read(String measurementId, byte[] filePrefix) {
        if (filePrefix == null || filePrefix.length < 64) {
            return TimestampObservation.none(measurementId, "MDF header is truncated");
        }
        String id = ascii(filePrefix, 0, 8);
        if (!id.startsWith("MDF") && !id.startsWith("UnFinMF")) {
            return TimestampObservation.none(measurementId, "not an MDF header");
        }
        if (isMdf3(filePrefix)) {
            return readMdf3(measurementId, filePrefix);
        }
        return readMdf4(measurementId, filePrefix);
    }

    private static boolean isMdf3(byte[] prefix) {
        String version = ascii(prefix, 8, Math.min(8, prefix.length - 8)).trim();
        if (version.startsWith("3")) {
            return true;
        }
        if (version.startsWith("4")) {
            return false;
        }
        return prefix.length >= 66 && ascii(prefix, 64, 2).equals("HD") && !ascii(prefix, 64, 4).equals("##HD");
    }

    private static TimestampObservation readMdf4(String measurementId, byte[] prefix) {
        if (prefix.length < 88 || !"##HD".equals(ascii(prefix, 64, 4))) {
            return TimestampObservation.none(measurementId, "MF4 HD block is missing");
        }
        ByteBuffer buffer = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN);
        long links = buffer.getLong(80);
        if (links < 0 || links > 32) {
            return TimestampObservation.none(measurementId, "MF4 HD link count is not usable");
        }
        int dataOffset = 64 + 24 + (int) links * 8;
        if (dataOffset < 0 || prefix.length < dataOffset + 13) {
            return TimestampObservation.none(measurementId, "MF4 HD time fields are truncated");
        }
        long start = buffer.getLong(dataOffset);
        int tz = buffer.getShort(dataOffset + 8);
        int dst = buffer.getShort(dataOffset + 10);
        int flags = prefix[dataOffset + 12] & 0xFF;
        boolean local = (flags & 0x01) != 0;
        boolean offsetsValid = (flags & 0x02) != 0;
        if (start <= 0L) {
            return new TimestampObservation(
                    measurementId, TimestampSource.MF4_HD, TimestampRawKind.EPOCH_NANOS, TimeUnitKind.NANOSECONDS,
                    null, false, local, 0L, null, "MF4 recording start is zero");
        }
        Integer offset = null;
        boolean zoneExplicit = !local;
        if (local && offsetsValid) {
            offset = tz + dst;
            zoneExplicit = true;
        }
        String detail = local
                ? (offsetsValid ? "MF4 HD local start with offsets" : "MF4 HD local start without offsets")
                : "MF4 HD UTC start";
        return new TimestampObservation(
                measurementId, TimestampSource.MF4_HD, TimestampRawKind.EPOCH_NANOS, TimeUnitKind.NANOSECONDS,
                offset, zoneExplicit, local, start, null, detail);
    }

    private static TimestampObservation readMdf3(String measurementId, byte[] prefix) {
        if (prefix.length < 100 || !"HD".equals(ascii(prefix, 64, 2))) {
            return TimestampObservation.none(measurementId, "MDF 3 HD block is missing");
        }
        String date = ascii(prefix, 82, 10).trim();
        String time = ascii(prefix, 92, 8).trim();
        if (!date.matches("\\d{2}:\\d{2}:\\d{4}") || !time.matches("\\d{2}:\\d{2}:\\d{2}")) {
            return TimestampObservation.none(measurementId, "MDF 3 date or time is not readable");
        }
        try {
            int day = Integer.parseInt(date.substring(0, 2));
            int month = Integer.parseInt(date.substring(3, 5));
            int year = Integer.parseInt(date.substring(6, 10));
            int hour = Integer.parseInt(time.substring(0, 2));
            int minute = Integer.parseInt(time.substring(3, 5));
            int second = Integer.parseInt(time.substring(6, 8));
            var civil = java.time.LocalDateTime.of(year, month, day, hour, minute, second);
            return new TimestampObservation(
                    measurementId, TimestampSource.MDF3_HD, TimestampRawKind.CIVIL_LOCAL, TimeUnitKind.NONE,
                    null, false, true, 0L, civil, "MDF 3 HD date and time have no timezone");
        } catch (RuntimeException ex) {
            return TimestampObservation.none(measurementId, "MDF 3 date or time is not a calendar value");
        }
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            return "";
        }
        return new String(bytes, offset, length, StandardCharsets.US_ASCII).replace('\0', ' ');
    }
}
