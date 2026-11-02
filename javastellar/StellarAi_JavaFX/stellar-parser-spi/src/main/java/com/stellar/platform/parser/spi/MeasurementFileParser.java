package com.stellar.platform.parser.spi;

import com.stellar.platform.domain.MeasurementDescriptor;

import java.nio.file.Path;

/**
 * Format-independent contract for reading a measurement file.
 * Concrete formats live in separate implementation modules.
 */
public interface MeasurementFileParser {

    /**
     * @return a short format key such as {@code "mf4"}, {@code "asc"}, or {@code "blf"}
     */
    String formatKey();

    /**
     * @return {@code true} if this parser can handle the given path (by extension/header sniffing later)
     */
    boolean supports(Path path);

    /**
     * Opens the file and returns a high-level descriptor only.
     * Full decode pipelines are intentionally out of scope for the skeleton.
     */
    MeasurementDescriptor open(Path path, ParserContext context) throws ParserException;
}
