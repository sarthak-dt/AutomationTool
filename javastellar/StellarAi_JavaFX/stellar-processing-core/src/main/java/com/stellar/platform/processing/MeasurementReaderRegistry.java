package com.stellar.platform.processing;

import com.stellar.platform.parser.spi.AutomotiveFileReader;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Selects a reader from a content detection result. The filename extension is not the selector.
 */
public final class MeasurementReaderRegistry {

    private final ParserRegistry parsers;

    public MeasurementReaderRegistry(ParserRegistry parsers) {
        this.parsers = Objects.requireNonNull(parsers, "parsers");
    }

    public static MeasurementReaderRegistry load() {
        return new MeasurementReaderRegistry(ParserRegistry.load());
    }

    public DetectionResult detect(Path path) {
        return DetectionResult.from(path, parsers.detect(path));
    }

    public Optional<AutomotiveFileReader> findReader(Path path, DetectionResult detection) {
        Objects.requireNonNull(path, "path");
        if (detection == null || !detection.automaticProcessingAllowed()) {
            return Optional.empty();
        }
        return parsers.resolve(path, detection.detected());
    }
}
