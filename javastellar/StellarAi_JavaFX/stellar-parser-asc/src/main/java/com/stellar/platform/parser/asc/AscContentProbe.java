package com.stellar.platform.parser.asc;

import com.stellar.platform.parser.spi.ContentFormatMatch;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Recognizes a Vector ASC log from its header pair, not from the file extension.
 * A text file is ASC only when both a {@code date} line and a {@code base hex|dec} line
 * appear before any other payload line.
 */
public final class AscContentProbe {

    private static final int MAX_LINES = 40;

    private AscContentProbe() {
    }

    public static Optional<ContentFormatMatch> probe(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        boolean date = false;
        boolean base = false;
        int looked = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while (looked < MAX_LINES && (line = reader.readLine()) != null) {
                String trimmed = stripBom(line).trim();
                if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*")) {
                    continue;
                }
                looked++;
                String lower = trimmed.toLowerCase(Locale.ROOT);
                if (lower.startsWith("date ")) {
                    date = true;
                    continue;
                }
                if (lower.startsWith("base ") && (lower.contains(" hex") || lower.endsWith(" hex")
                        || lower.contains(" dec") || lower.endsWith(" dec")
                        || lower.contains(" decimal"))) {
                    base = true;
                    continue;
                }
                if (isHeaderNoise(lower)) {
                    continue;
                }
                break;
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        if (!date || !base) {
            return Optional.empty();
        }
        return Optional.of(new ContentFormatMatch(
                AscMeasurementFileParser.FORMAT_KEY,
                "ASC",
                "",
                "",
                true,
                Boolean.TRUE,
                Boolean.FALSE,
                Boolean.TRUE));
    }

    public static boolean parserCanRead(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        if (!Files.isRegularFile(path)) {
            return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".asc");
        }
        return probe(path).isPresent();
    }

    private static boolean isHeaderNoise(String lower) {
        return lower.equals("internal events logged")
                || lower.equals("no internal events logged")
                || lower.startsWith("begin triggerblock")
                || lower.startsWith("end triggerblock")
                || lower.contains("start of measurement")
                || lower.startsWith("version");
    }

    private static String stripBom(String line) {
        if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
            return line.substring(1);
        }
        return line;
    }
}
