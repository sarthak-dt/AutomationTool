package com.stellar.platform.parser.spi;

import java.nio.file.Path;
import java.util.Optional;

/**
 * ServiceLoader entry point for a measurement file parser plugin.
 */
public interface ParserProvider {

    /**
     * @return unique provider id (stable across releases)
     */
    String providerId();

    /**
     * @return a new parser instance for this provider
     */
    MeasurementFileParser createParser();

    /**
     * Optional streaming CAN frame reader. Empty when this provider does not yet support frame streaming.
     */
    default Optional<AutomotiveFileReader> createReader() {
        return Optional.empty();
    }

    /**
     * Content signature probe. Empty when this plugin does not recognize the bytes.
     * Must not accept a file from its extension alone.
     */
    default Optional<ContentFormatMatch> probeContent(Path path) {
        return Optional.empty();
    }
}
