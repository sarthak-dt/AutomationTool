package com.stellar.platform.processing.map.time;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads a recording clock from measurement bytes. Content bytes choose the adapter,
 * so a DAT or LOG file uses the MF4, ASC, BLF, or text clock inside it.
 * The read is a header or a short text sample. Signal samples are not decoded.
 */
public final class RecordingClockReader {

    private static final int PREFIX_BYTES = 65_536;
    private static final ConcurrentHashMap<String, RecordingClock> CACHE = new ConcurrentHashMap<>();

    private RecordingClockReader() {
    }

    public static Map<String, RecordingClock> readAll(List<String> measurementIds, Map<String, Path> pathsById) {
        Map<String, RecordingClock> clocks = new LinkedHashMap<>();
        Map<String, Path> paths = pathsById == null ? Map.of() : pathsById;
        if (measurementIds == null) {
            return clocks;
        }
        for (String id : measurementIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            Path path = resolve(paths, id);
            clocks.put(id, path == null
                    ? RecordingClock.unordered(id, TimestampSource.NONE, ClockKind.MISSING, TimeUnitKind.NONE,
                    "measurement file is not available")
                    : read(path, id));
        }
        return clocks;
    }

    public static RecordingClock read(Path path, String measurementId) {
        return read(path, measurementId, OptionalInt.empty());
    }

    /**
     * {@code userOffsetMinutes} is empty in production. A future timezone choice can pass a value.
     */
    public static RecordingClock read(Path path, String measurementId, OptionalInt userOffsetMinutes) {
        String id = measurementId == null ? "" : measurementId;
        OptionalInt offset = userOffsetMinutes == null ? OptionalInt.empty() : userOffsetMinutes;
        if (path == null) {
            return RecordingClock.unordered(id, TimestampSource.NONE, ClockKind.MISSING, TimeUnitKind.NONE,
                    "measurement file is not available");
        }
        Path absolute;
        try {
            absolute = path.toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            return RecordingClock.unordered(id, TimestampSource.NONE, ClockKind.MISSING, TimeUnitKind.NONE,
                    "measurement path is not usable");
        }
        String cacheKey = cacheKey(absolute, offset);
        RecordingClock cached = CACHE.get(cacheKey);
        if (cached != null && id.equals(cached.measurementId())) {
            return cached;
        }
        RecordingClock clock = TimestampNormalizer.normalize(observeUncached(absolute, id), offset);
        if (id.equals(clock.measurementId())) {
            CACHE.put(cacheKey, clock);
        }
        return clock;
    }

    static void clearCacheForTests() {
        CACHE.clear();
    }

    /**
     * Header observation before it is judged comparable. Desktop chronology does not use this.
     */
    public static TimestampObservation observe(Path path) {
        return observeUncached(path, "measurement");
    }

    private static TimestampObservation observeUncached(Path path, String measurementId) {
        try {
            if (path == null || !Files.isRegularFile(path)) {
                return TimestampObservation.none(measurementId, "measurement file is not available");
            }
            byte[] prefix = readPrefix(path, PREFIX_BYTES);
            String format = detect(prefix);
            return switch (format) {
                case "mf4", "mdf3" -> Mf4RecordingStartAdapter.read(measurementId, prefix);
                case "blf" -> BlfRecordingStartAdapter.read(measurementId, prefix);
                case "asc" -> AscRecordingStartAdapter.read(measurementId, new String(prefix, StandardCharsets.ISO_8859_1));
                case "text" -> TextRecordingStartAdapter.read(measurementId, new String(prefix, StandardCharsets.ISO_8859_1));
                default -> TimestampObservation.none(measurementId, "format has no recording-time adapter");
            };
        } catch (IOException | RuntimeException ex) {
            return TimestampObservation.none(measurementId, "recording start could not be read");
        }
    }

    static String detect(byte[] prefix) {
        if (prefix == null || prefix.length < 4) {
            return "";
        }
        String id = ascii(prefix, 0, Math.min(8, prefix.length));
        if (id.startsWith("MDF") || id.startsWith("UnFinMF")) {
            return mdf3(prefix) ? "mdf3" : "mf4";
        }
        if (prefix[0] == 'L' && prefix[1] == 'O' && prefix[2] == 'G' && prefix[3] == 'G') {
            return "blf";
        }
        String text = new String(prefix, StandardCharsets.ISO_8859_1);
        if (looksLikeAsc(text)) {
            return "asc";
        }
        if (looksLikeText(text)) {
            return "text";
        }
        return "";
    }

    private static boolean mdf3(byte[] prefix) {
        if (prefix.length < 16) {
            return false;
        }
        String version = ascii(prefix, 8, 8).trim();
        if (version.startsWith("3")) {
            return true;
        }
        if (version.startsWith("4")) {
            return false;
        }
        return prefix.length >= 66 && "HD".equals(ascii(prefix, 64, 2)) && !"##HD".equals(ascii(prefix, 64, 4));
    }

    private static boolean looksLikeAsc(String text) {
        for (String raw : text.split("\\R", 20)) {
            String line = raw.trim();
            if (line.regionMatches(true, 0, "date ", 0, 5) || line.regionMatches(true, 0, "base ", 0, 5)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeText(String text) {
        String[] lines = text.split("\\R", 5);
        int rows = 0;
        for (String line : lines) {
            if (!line.isBlank()) {
                rows++;
            }
        }
        return rows >= 2;
    }

    private static Path resolve(Map<String, Path> paths, String id) {
        Path direct = paths.get(id);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, Path> entry : paths.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(id)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String cacheKey(Path path, OptionalInt offset) {
        long size = -1L;
        long modified = -1L;
        try {
            size = Files.size(path);
            modified = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            // A missing file still gets a key so the miss is cached until the path changes.
        }
        return path + "|" + size + "|" + modified + "|" + (offset.isPresent() ? offset.getAsInt() : "none");
    }

    private static byte[] readPrefix(Path path, int max) throws IOException {
        byte[] buffer = new byte[max];
        int read = 0;
        try (InputStream in = Files.newInputStream(path)) {
            while (read < max) {
                int n = in.read(buffer, read, max - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
        }
        return read == max ? buffer : Arrays.copyOf(buffer, read);
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII).replace('\0', ' ');
    }
}
