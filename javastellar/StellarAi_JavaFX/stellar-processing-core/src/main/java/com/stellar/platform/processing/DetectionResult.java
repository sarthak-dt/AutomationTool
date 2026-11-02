package com.stellar.platform.processing;

import com.stellar.platform.parser.spi.ContentFormatMatch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Content inspection result. The imported source type is stored separately and is not overwritten.
 */
public record DetectionResult(
        ContainerFormat containerFormat,
        String version,
        DetectionConfidence confidence,
        String readerId,
        boolean text,
        DetectedMeasurementFormat detected
) {

    public DetectionResult {
        containerFormat = containerFormat == null ? ContainerFormat.UNKNOWN_BINARY : containerFormat;
        version = version == null ? "" : version;
        confidence = confidence == null ? DetectionConfidence.LOW : confidence;
        readerId = readerId == null ? "" : readerId;
    }

    public boolean automaticProcessingAllowed() {
        return confidence != DetectionConfidence.LOW
                && detected != null
                && detected.parserSupported();
    }

    public static DetectionResult from(Path path, DetectedMeasurementFormat detected) {
        DetectedMeasurementFormat safe = detected == null
                ? new DetectedMeasurementFormat("", "", null, "")
                : detected;
        ContentFormatMatch content = safe.content();
        if (content == null) {
            boolean binary = looksBinary(path);
            return new DetectionResult(
                    binary ? ContainerFormat.UNKNOWN_BINARY : ContainerFormat.UNKNOWN_TEXT,
                    "",
                    DetectionConfidence.LOW,
                    "",
                    !binary,
                    safe
            );
        }
        ContainerFormat container = containerOf(content);
        boolean textual = container == ContainerFormat.DELIMITED_TEXT || container == ContainerFormat.STRUCTURED_TEXT;
        DetectionConfidence confidence = confidenceOf(content, textual);
        return new DetectionResult(
                container,
                content.version(),
                confidence,
                content.parserSupported() ? content.formatKey() : "",
                textual,
                safe
        );
    }

    private static ContainerFormat containerOf(ContentFormatMatch content) {
        String key = content.formatKey() == null ? "" : content.formatKey().toLowerCase(Locale.ROOT);
        String name = content.formatName() == null ? "" : content.formatName().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "mf4", "mdf" -> ContainerFormat.MDF;
            case "asc" -> ContainerFormat.ASC;
            case "blf" -> ContainerFormat.BLF;
            case "text" -> name.startsWith("structured")
                    ? ContainerFormat.STRUCTURED_TEXT
                    : ContainerFormat.DELIMITED_TEXT;
            default -> ContainerFormat.UNKNOWN_BINARY;
        };
    }

    private static DetectionConfidence confidenceOf(ContentFormatMatch content, boolean textual) {
        String program = content.programIdentifier() == null ? "" : content.programIdentifier();
        if (program.startsWith("confidence:")) {
            String token = program.substring("confidence:".length()).trim().toUpperCase(Locale.ROOT);
            try {
                return DetectionConfidence.valueOf(token);
            } catch (IllegalArgumentException ignored) {
                return DetectionConfidence.LOW;
            }
        }
        if (!content.parserSupported()) {
            return DetectionConfidence.LOW;
        }
        return textual ? DetectionConfidence.MEDIUM : DetectionConfidence.HIGH;
    }

    private static boolean looksBinary(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return true;
        }
        byte[] prefix = new byte[512];
        try (InputStream in = Files.newInputStream(path)) {
            int n = in.read(prefix);
            if (n <= 0) {
                return false;
            }
            int nonText = 0;
            for (int i = 0; i < n; i++) {
                int value = prefix[i] & 0xFF;
                if (value == 0) {
                    return true;
                }
                if (value < 0x09 || (value > 0x0D && value < 0x20)) {
                    nonText++;
                }
            }
            return nonText * 5 > n;
        } catch (IOException e) {
            return true;
        }
    }
}
