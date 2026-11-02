package com.stellar.platform.processing;

import com.stellar.platform.parser.spi.ContentFormatMatch;
import com.stellar.platform.parser.spi.ParserProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Selects a measurement format from file bytes. The extension is used only when the
 * file cannot be read, and never overrides a content match.
 */
public final class MeasurementFormatDetector {

    private final List<ParserProvider> providers;

    public MeasurementFormatDetector(List<ParserProvider> providers) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
    }

    public DetectedMeasurementFormat detect(Path path) {
        String filename = filename(path);
        String extension = extension(filename);
        if (path != null && Files.isRegularFile(path)) {
            ContentFormatMatch match = probe(path);
            return new DetectedMeasurementFormat(filename, extension, match, "");
        }
        return new DetectedMeasurementFormat(filename, extension, null, extensionHint(extension));
    }

    private ContentFormatMatch probe(Path path) {
        List<ContentFormatMatch> matches = new ArrayList<>();
        for (ParserProvider provider : providers) {
            Optional<ContentFormatMatch> match = provider.probeContent(path);
            match.ifPresent(matches::add);
        }
        ContentFormatMatch selected = null;
        for (ContentFormatMatch match : matches) {
            if (selected == null || rank(match) > rank(selected)) {
                selected = match;
            }
        }
        return selected;
    }

    /**
     * Binary signatures outrank textual inference. A supported match outranks an unsupported one.
     */
    private static int rank(ContentFormatMatch match) {
        int score = match.parserSupported() ? 20 : 0;
        String key = match.formatKey() == null ? "" : match.formatKey().toLowerCase(Locale.ROOT);
        if (key.equals("mf4") || key.equals("mdf") || key.equals("asc") || key.equals("blf")) {
            score += 10;
        }
        return score;
    }

    private static String extensionHint(String extension) {
        return switch (extension) {
            case "mf4", "mdf" -> "mf4";
            case "asc" -> "asc";
            case "blf" -> "blf";
            default -> "";
        };
    }

    private static String filename(Path path) {
        if (path == null) {
            return "";
        }
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
