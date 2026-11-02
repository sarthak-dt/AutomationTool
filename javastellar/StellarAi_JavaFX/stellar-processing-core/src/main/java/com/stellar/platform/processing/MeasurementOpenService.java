package com.stellar.platform.processing;

import com.stellar.platform.domain.MeasurementDescriptor;
import com.stellar.platform.parser.spi.EmptyParserContext;
import com.stellar.platform.parser.spi.MeasurementFileParser;
import com.stellar.platform.parser.spi.ParserException;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Minimal facade demonstrating SPI-based parser selection.
 * Full processing pipelines are intentionally out of scope.
 */
public final class MeasurementOpenService {

    private final ParserRegistry registry;

    public MeasurementOpenService(ParserRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public Optional<MeasurementDescriptor> tryOpen(Path path) throws ParserException {
        for (MeasurementFileParser parser : registry.createParsers()) {
            if (parser.supports(path)) {
                return Optional.of(parser.open(path, EmptyParserContext.INSTANCE));
            }
        }
        return Optional.empty();
    }
}
