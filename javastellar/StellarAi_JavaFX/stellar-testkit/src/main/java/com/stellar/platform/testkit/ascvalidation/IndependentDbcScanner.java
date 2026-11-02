package com.stellar.platform.testkit.ascvalidation;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Independent DBC inventory from {@code BO_}/{@code SG_} text. Does not use {@code DbcParser}.
 */
public final class IndependentDbcScanner {

    public static final long EXTENDED_FLAG = 0x8000_0000L;

    private IndependentDbcScanner() {
    }

    public static DbcInventory scan(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Map<Long, DbcMessageTruth> messages = new LinkedHashMap<>();
        DbcMessageTruth current = null;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("BO_ ")) {
                    current = parseBo(trimmed);
                    messages.put(key(current.canId(), current.extended()), current);
                } else if (trimmed.startsWith("SG_ ") && current != null) {
                    current.signals.add(parseSg(trimmed));
                }
            }
        }
        return new DbcInventory(path.getFileName().toString(), List.copyOf(messages.values()));
    }

    private static long key(long canId, boolean extended) {
        return extended ? (canId | EXTENDED_FLAG) : canId;
    }

    private static DbcMessageTruth parseBo(String line) {
        String[] tokens = line.split("\\s+");
        if (tokens.length < 3) {
            throw new IllegalArgumentException("invalid BO_ line: " + line);
        }
        long raw = Long.parseLong(tokens[1]);
        boolean extended = (raw & EXTENDED_FLAG) != 0;
        long canId = extended ? (raw & 0x1FFF_FFFFL) : (raw & 0x7FFL);
        String name = tokens[2].replace(":", "");
        return new DbcMessageTruth(canId, extended, name, new ArrayList<>());
    }

    private static DbcSignalTruth parseSg(String line) {
        String rest = line.substring(4).trim();
        int colon = rest.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("invalid SG_ line: " + line);
        }
        String namePart = rest.substring(0, colon).trim();
        String name = namePart.split("\\s+")[0];
        String layout = rest.substring(colon + 1).trim();
        int bar = layout.indexOf('|');
        int at = layout.indexOf('@');
        int paren = layout.indexOf('(');
        int comma = layout.indexOf(',', paren);
        int close = layout.indexOf(')', comma);
        int startBit = Integer.parseInt(layout.substring(0, bar).trim());
        int bitLength = Integer.parseInt(layout.substring(bar + 1, at).trim());
        char endian = layout.charAt(at + 1);
        char signed = layout.charAt(at + 2);
        double factor = Double.parseDouble(layout.substring(paren + 1, comma).trim());
        double offset = Double.parseDouble(layout.substring(comma + 1, close).trim());
        String unit = "";
        int q1 = layout.indexOf('"', close);
        if (q1 >= 0) {
            int q2 = layout.indexOf('"', q1 + 1);
            if (q2 > q1) {
                unit = layout.substring(q1 + 1, q2);
            }
        }
        boolean intel = endian == '1';
        boolean isSigned = signed == '-';
        return new DbcSignalTruth(name, startBit, bitLength, intel, isSigned, factor, offset, unit);
    }

    public static boolean looksLikeGpsLatitude(String signalName) {
        String n = normalize(signalName);
        return n.equals("latitude") || n.equals("gpslatitude") || n.equals("gnsslatitude")
                || n.contains("latitude") && !n.contains("factor") && !n.contains("offset");
    }

    public static boolean looksLikeGpsLongitude(String signalName) {
        String n = normalize(signalName);
        return n.equals("longitude") || n.equals("gpslongitude") || n.equals("gnsslongitude")
                || n.contains("longitude") && !n.contains("factor") && !n.contains("offset");
    }

    private static String normalize(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
    }

    public record DbcSignalTruth(
            String name,
            int startBit,
            int bitLength,
            boolean intel,
            boolean signed,
            double factor,
            double offset,
            String unit
    ) {
    }

    public record DbcMessageTruth(
            long canId,
            boolean extended,
            String name,
            List<DbcSignalTruth> signals
    ) {
    }

    public record DbcInventory(String fileName, List<DbcMessageTruth> messages) {
        public int messageCount() {
            return messages.size();
        }

        public int signalCount() {
            return messages.stream().mapToInt(m -> m.signals.size()).sum();
        }

        public DbcMessageTruth find(long canId, boolean extended) {
            for (DbcMessageTruth message : messages) {
                if (message.canId == canId && message.extended == extended) {
                    return message;
                }
            }
            return null;
        }
    }
}
