package com.stellar.platform.parser.asc;

import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.ContentFormatMatch;
import com.stellar.platform.parser.spi.MeasurementFileParser;
import com.stellar.platform.parser.spi.ParserProvider;

import java.nio.file.Path;
import java.util.Optional;

/**
 * ServiceLoader registration for the ASC parser plugin.
 */
public final class AscParserProvider implements ParserProvider {

    public static final String PROVIDER_ID = "stellar-parser-asc";

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public MeasurementFileParser createParser() {
        return new AscMeasurementFileParser();
    }

    @Override
    public Optional<AutomotiveFileReader> createReader() {
        return Optional.of(new AscFileReader());
    }

    @Override
    public Optional<ContentFormatMatch> probeContent(Path path) {
        return AscContentProbe.probe(path);
    }
}
