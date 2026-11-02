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
 * Independent ASC inventory. Does not call {@code AscFileReader}.
 * Classic CAN data frames only; other records are counted as skipped/malformed.
 */
public final class IndependentAscScanner {

    private IndependentAscScanner() {
    }

    public static AscInventory scan(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        long size = Files.size(path);
        List<RawFrame> frames = new ArrayList<>();
        long malformed = 0;
        long skipped = 0;
        String date = "";
        String timestampMode = "unspecified";
        String base = "hex";
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            long lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber == 1 && !line.isEmpty() && line.charAt(0) == '\uFEFF') {
                    line = line.substring(1);
                }
                String trimmed = stripComment(line).trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String lower = trimmed.toLowerCase(Locale.ROOT);
                if (lower.startsWith("date ")) {
                    date = trimmed.substring(5).trim();
                    continue;
                }
                if (lower.startsWith("base ")) {
                    if (lower.contains(" dec") || lower.contains(" decimal")) {
                        base = "dec";
                    } else {
                        base = "hex";
                    }
                    if (lower.contains("relative")) {
                        timestampMode = "relative";
                    } else if (lower.contains("absolute")) {
                        timestampMode = "absolute";
                    }
                    continue;
                }
                if (isIgnorable(lower)) {
                    continue;
                }
                if (lower.contains("canfd")) {
                    skipped++;
                    continue;
                }
                RawFrame parsed = parseFrame(trimmed, lineNumber, base);
                if (parsed == null) {
                    if (startsWithDigit(trimmed)) {
                        if (looksRemote(trimmed)) {
                            skipped++;
                        } else {
                            malformed++;
                        }
                    } else {
                        malformed++;
                    }
                    continue;
                }
                frames.add(parsed);
            }
        }
        return new AscInventory(
                path.getFileName().toString(),
                size,
                date,
                timestampMode,
                base,
                frames,
                malformed,
                skipped
        );
    }

    private static boolean isIgnorable(String lower) {
        return lower.startsWith("internal events")
                || lower.startsWith("no internal events")
                || lower.startsWith("begin trigger")
                || lower.startsWith("end trigger")
                || lower.contains("start of measurement")
                || lower.startsWith("version")
                || lower.startsWith("//")
                || lower.startsWith("*")
                || lower.contains("errorframe")
                || lower.startsWith("statistic:");
    }

    private static boolean startsWithDigit(String line) {
        return !line.isEmpty() && line.charAt(0) >= '0' && line.charAt(0) <= '9';
    }

    private static boolean looksRemote(String line) {
        String[] tokens = tokenize(line);
        for (String token : tokens) {
            if ("r".equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    private static String stripComment(String line) {
        int idx = line.indexOf("//");
        if (idx < 0) {
            return line;
        }
        String before = line.substring(0, idx).trim();
        if (before.isEmpty() || !startsWithDigit(before)) {
            return "";
        }
        return before;
    }

    private static RawFrame parseFrame(String line, long lineNumber, String base) {
        String[] tokens = tokenize(line);
        if (tokens.length < 6) {
            return null;
        }
        Long timestampNanos;
        try {
            timestampNanos = secondsToNanos(tokens[0]);
        } catch (RuntimeException ex) {
            return null;
        }
        int channel;
        try {
            channel = Integer.parseInt(tokens[1], 10);
        } catch (NumberFormatException ex) {
            return null;
        }
        int index = 2;
        IdParse id = parseId(tokens, index, base);
        if (id == null) {
            return null;
        }
        index = id.next;
        if (index >= tokens.length) {
            return null;
        }
        String dirTok = tokens[index];
        String direction;
        if ("rx".equalsIgnoreCase(dirTok) || "r".equalsIgnoreCase(dirTok)) {
            direction = "RX";
        } else if ("tx".equalsIgnoreCase(dirTok) || "t".equalsIgnoreCase(dirTok)) {
            direction = "TX";
        } else {
            return null;
        }
        index++;
        if (index >= tokens.length || !"d".equalsIgnoreCase(tokens[index])) {
            return null;
        }
        index++;
        if (index >= tokens.length) {
            return null;
        }
        int dlc;
        try {
            dlc = Integer.parseInt(tokens[index], 10);
        } catch (NumberFormatException ex) {
            return null;
        }
        if (dlc < 0 || dlc > 8) {
            return null;
        }
        index++;
        byte[] data = new byte[dlc];
        int radix = "dec".equals(base) ? 10 : 16;
        for (int i = 0; i < dlc; i++) {
            if (index + i >= tokens.length) {
                return null;
            }
            try {
                int value = Integer.parseInt(tokens[index + i], radix);
                if (value < 0 || value > 255) {
                    return null;
                }
                data[i] = (byte) value;
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return new RawFrame(lineNumber, timestampNanos, channel, id.canId, id.extended, direction, dlc, data);
    }

    private static IdParse parseId(String[] tokens, int index, String base) {
        if (index >= tokens.length) {
            return null;
        }
        String token = tokens[index];
        boolean extended = false;
        if (token.length() > 1 && (token.endsWith("x") || token.endsWith("X"))) {
            extended = true;
            token = token.substring(0, token.length() - 1);
        }
        Long canId = parseUnsigned(token, base);
        if (canId == null) {
            return null;
        }
        int next = index + 1;
        if (!extended && next < tokens.length && "x".equalsIgnoreCase(tokens[next])) {
            extended = true;
            next++;
        }
        if (canId > 0x7FFL) {
            extended = true;
        }
        return new IdParse(canId, extended, next);
    }

    private static Long parseUnsigned(String raw, String base) {
        String token = raw;
        int radix = "dec".equals(base) ? 10 : 16;
        if (token.regionMatches(true, 0, "0x", 0, 2)) {
            token = token.substring(2);
            radix = 16;
        }
        try {
            return Long.parseLong(token, radix);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static long secondsToNanos(String token) {
        int dot = token.indexOf('.');
        String secondsPart = dot < 0 ? token : token.substring(0, dot);
        String fraction = dot < 0 ? "" : token.substring(dot + 1);
        if (secondsPart.isEmpty()) {
            secondsPart = "0";
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                throw new IllegalArgumentException("invalid timestamp");
            }
        }
        long seconds = Long.parseLong(secondsPart);
        char[] digits = new char[9];
        int copy = Math.min(9, fraction.length());
        for (int i = 0; i < copy; i++) {
            digits[i] = fraction.charAt(i);
        }
        for (int i = copy; i < 9; i++) {
            digits[i] = '0';
        }
        return seconds * 1_000_000_000L + Long.parseLong(new String(digits));
    }

    static String[] tokenize(String line) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = line.length();
        while (i < n) {
            while (i < n && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int start = i;
            while (i < n && line.charAt(i) != ' ' && line.charAt(i) != '\t') {
                i++;
            }
            out.add(line.substring(start, i));
        }
        return out.toArray(String[]::new);
    }

    private record IdParse(long canId, boolean extended, int next) {
    }

    public record RawFrame(
            long lineNumber,
            long timestampNanos,
            int channel,
            long canId,
            boolean extended,
            String direction,
            int dlc,
            byte[] data
    ) {
        public String payloadHex() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < data.length; i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(String.format(Locale.ROOT, "%02X", data[i] & 0xFF));
            }
            return sb.toString();
        }
    }

    public record IdStats(
            int channel,
            long canId,
            boolean extended,
            long frameCount,
            long firstTimestampNanos,
            long lastTimestampNanos,
            List<Integer> dlcs
    ) {
    }

    public static final class AscInventory {
        private final String fileName;
        private final long fileSize;
        private final String dateHeader;
        private final String timestampMode;
        private final String base;
        private final List<RawFrame> frames;
        private final long malformed;
        private final long skipped;

        AscInventory(
                String fileName,
                long fileSize,
                String dateHeader,
                String timestampMode,
                String base,
                List<RawFrame> frames,
                long malformed,
                long skipped
        ) {
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.dateHeader = dateHeader;
            this.timestampMode = timestampMode;
            this.base = base;
            this.frames = List.copyOf(frames);
            this.malformed = malformed;
            this.skipped = skipped;
        }

        public String fileName() {
            return fileName;
        }

        public long fileSize() {
            return fileSize;
        }

        public String dateHeader() {
            return dateHeader;
        }

        public String timestampMode() {
            return timestampMode;
        }

        public String base() {
            return base;
        }

        public List<RawFrame> frames() {
            return frames;
        }

        public long malformed() {
            return malformed;
        }

        public long skipped() {
            return skipped;
        }

        public long validFrames() {
            return frames.size();
        }

        public long standardCount() {
            return frames.stream().filter(f -> !f.extended).count();
        }

        public long extendedCount() {
            return frames.stream().filter(f -> f.extended).count();
        }

        public long rxCount() {
            return frames.stream().filter(f -> "RX".equals(f.direction)).count();
        }

        public long txCount() {
            return frames.stream().filter(f -> "TX".equals(f.direction)).count();
        }

        public long firstTimestampNanos() {
            return frames.isEmpty() ? Long.MIN_VALUE : frames.getFirst().timestampNanos;
        }

        public long lastTimestampNanos() {
            return frames.isEmpty() ? Long.MIN_VALUE : frames.getLast().timestampNanos;
        }

        public long durationNanos() {
            if (frames.size() < 2) {
                return 0L;
            }
            return lastTimestampNanos() - firstTimestampNanos();
        }

        public List<Integer> channels() {
            return frames.stream().map(f -> f.channel).distinct().sorted().toList();
        }

        public List<Long> uniqueCanIds() {
            return frames.stream().map(f -> f.canId).distinct().sorted().toList();
        }

        public Map<Integer, Long> dlcDistribution() {
            Map<Integer, Long> out = new LinkedHashMap<>();
            for (RawFrame frame : frames) {
                out.merge(frame.dlc, 1L, Long::sum);
            }
            return out;
        }

        public List<IdStats> perId() {
            Map<String, List<RawFrame>> grouped = new LinkedHashMap<>();
            for (RawFrame frame : frames) {
                String key = frame.channel + "|" + frame.canId + "|" + frame.extended;
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(frame);
            }
            List<IdStats> stats = new ArrayList<>();
            for (List<RawFrame> group : grouped.values()) {
                RawFrame first = group.getFirst();
                List<Integer> dlcs = group.stream().map(f -> f.dlc).distinct().sorted().toList();
                stats.add(new IdStats(
                        first.channel,
                        first.canId,
                        first.extended,
                        group.size(),
                        first.timestampNanos,
                        group.getLast().timestampNanos,
                        dlcs
                ));
            }
            return stats;
        }

        public RawFrame representative(int channel, long canId, boolean extended, String which) {
            List<RawFrame> group = frames.stream()
                    .filter(f -> f.channel == channel && f.canId == canId && f.extended == extended)
                    .toList();
            if (group.isEmpty()) {
                return null;
            }
            return switch (which) {
                case "first" -> group.getFirst();
                case "last" -> group.getLast();
                default -> group.get(group.size() / 2);
            };
        }
    }
}
