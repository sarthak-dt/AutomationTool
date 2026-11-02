package com.stellar.platform.parser.spi;

import java.util.Objects;

/**
 * What a parser plugin can prove from file bytes.
 * Null capability flags mean the probe could not determine that fact.
 * Callers must not treat null as false.
 */
public record ContentFormatMatch(
        String formatKey,
        String formatName,
        String version,
        String programIdentifier,
        boolean parserSupported,
        Boolean containsRawCan,
        Boolean containsDecodedSignals,
        Boolean requiresDefinition
) {

    public ContentFormatMatch {
        formatKey = Objects.requireNonNull(formatKey, "formatKey");
        formatName = Objects.requireNonNull(formatName, "formatName");
        version = version == null ? "" : version.trim();
        programIdentifier = programIdentifier == null ? "" : programIdentifier.trim();
    }

    public String displayLabel() {
        if (version.isBlank()) {
            return formatName;
        }
        return formatName + " " + version;
    }

    public String unsupportedVersionMessage() {
        return "Detected " + displayLabel() + ", but this version is not currently supported.";
    }
}
