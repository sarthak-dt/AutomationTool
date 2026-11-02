package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.spi.ParserResumeCursor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * MF4-owned cursor payload. Coordinates are complete-record boundaries inside a resolved
 * DT/DZ payload segment, not arbitrary file bytes.
 */
public record Mf4ResumePayload(
        int dgIndex,
        long dgAddress,
        int segmentIndex,
        long nextByteOffset,
        long recordsConsumed,
        long framesEmitted,
        long nativeEmitted,
        Long lastTimestampNanos,
        String schemaFingerprint,
        Map<Long, Long> remainingCycles
) {

    public Mf4ResumePayload {
        dgIndex = Math.max(0, dgIndex);
        segmentIndex = Math.max(0, segmentIndex);
        nextByteOffset = Math.max(0L, nextByteOffset);
        recordsConsumed = Math.max(0L, recordsConsumed);
        framesEmitted = Math.max(0L, framesEmitted);
        nativeEmitted = Math.max(0L, nativeEmitted);
        schemaFingerprint = schemaFingerprint == null ? "" : schemaFingerprint;
        remainingCycles = remainingCycles == null ? Map.of() : Map.copyOf(remainingCycles);
    }

    public String encode() {
        StringJoiner cycles = new StringJoiner(",");
        remainingCycles.forEach((cg, left) -> cycles.add(cg + ":" + left));
        return "dgIndex=" + dgIndex
                + "\ndgAddress=" + dgAddress
                + "\nsegmentIndex=" + segmentIndex
                + "\nnextByteOffset=" + nextByteOffset
                + "\nrecordsConsumed=" + recordsConsumed
                + "\nframesEmitted=" + framesEmitted
                + "\nnativeEmitted=" + nativeEmitted
                + "\nlastTs=" + (lastTimestampNanos == null ? "" : lastTimestampNanos)
                + "\nschema=" + schemaFingerprint
                + "\ncycles=" + cycles;
    }

    public ParserResumeCursor toCursor() {
        return ParserResumeCursor.mf4(encode());
    }

    public static Optional<Mf4ResumePayload> fromCursor(ParserResumeCursor cursor) {
        if (cursor == null || !cursor.isMf4V1()) {
            return Optional.empty();
        }
        return parse(cursor.payload());
    }

    public static Optional<Mf4ResumePayload> parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, String> fields = new LinkedHashMap<>();
            for (String line : payload.split("\n", -1)) {
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                fields.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
            Map<Long, Long> cycles = new LinkedHashMap<>();
            String cycleText = fields.getOrDefault("cycles", "");
            if (!cycleText.isBlank()) {
                for (String part : cycleText.split(",")) {
                    int colon = part.indexOf(':');
                    if (colon <= 0) {
                        return Optional.empty();
                    }
                    cycles.put(Long.parseLong(part.substring(0, colon).trim()),
                            Long.parseLong(part.substring(colon + 1).trim()));
                }
            }
            String ts = fields.getOrDefault("lastTs", "");
            return Optional.of(new Mf4ResumePayload(
                    parseInt(fields.get("dgIndex")),
                    parseLong(fields.get("dgAddress")),
                    parseInt(fields.get("segmentIndex")),
                    parseLong(fields.get("nextByteOffset")),
                    parseLong(fields.get("recordsConsumed")),
                    parseLong(fields.get("framesEmitted")),
                    parseLong(fields.get("nativeEmitted")),
                    ts.isBlank() ? null : Long.parseLong(ts),
                    fields.getOrDefault("schema", ""),
                    cycles
            ));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    public static String schemaFingerprint(Iterable<com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema> schemas) {
        StringBuilder raw = new StringBuilder();
        for (var schema : schemas) {
            raw.append(schema.dataGroupAddress()).append('|')
                    .append(schema.channelGroupAddress()).append('|')
                    .append(schema.recordId()).append('|')
                    .append(schema.completeRecordLength()).append('|')
                    .append(schema.dataSource().dataBlockRootAddress()).append(';');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static int parseInt(String value) {
        return Integer.parseInt(value == null || value.isBlank() ? "0" : value);
    }

    private static long parseLong(String value) {
        return Long.parseLong(value == null || value.isBlank() ? "0" : value);
    }
}
