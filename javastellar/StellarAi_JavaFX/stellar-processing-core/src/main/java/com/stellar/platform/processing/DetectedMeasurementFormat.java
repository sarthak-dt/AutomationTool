package com.stellar.platform.processing;

import com.stellar.platform.parser.spi.ContentFormatMatch;

import java.util.Locale;

/**
 * Result of content-first measurement detection.
 * A non-null {@link #content()} match always wins over {@link #extensionHint()}.
 */
public record DetectedMeasurementFormat(
        String originalFilename,
        String originalExtension,
        ContentFormatMatch content,
        String extensionHint
) {

    public DetectedMeasurementFormat {
        originalFilename = originalFilename == null ? "" : originalFilename;
        originalExtension = originalExtension == null ? "" : originalExtension.toLowerCase(Locale.ROOT);
        extensionHint = extensionHint == null ? "" : extensionHint;
    }

    public boolean recognized() {
        return content != null;
    }

    public boolean parserSupported() {
        return content != null && content.parserSupported();
    }

    /**
     * Format key the registry may route to a parser.
     * Content that is recognized but not supported does not route.
     * An extension hint is used only when no content match exists.
     */
    public String routingFormatKey() {
        if (content != null) {
            return content.parserSupported() ? content.formatKey() : "";
        }
        return extensionHint;
    }

    public String displayLabel() {
        if (content != null) {
            return content.displayLabel();
        }
        return "";
    }

    public String unknownMessage() {
        StringBuilder text = new StringBuilder(
                "StellarAI could not determine the measurement structure automatically.");
        if (!originalFilename.isBlank()) {
            text.append(" File: ").append(originalFilename).append('.');
        }
        if (!originalExtension.isBlank()) {
            text.append(" Extension: ").append(originalExtension).append('.');
        }
        text.append(" Content: unrecognized.");
        return text.toString();
    }
}
