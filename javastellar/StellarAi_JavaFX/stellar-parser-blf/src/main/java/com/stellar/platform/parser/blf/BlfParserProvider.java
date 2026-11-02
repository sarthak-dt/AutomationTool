package com.stellar.platform.parser.blf;

import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.ContentFormatMatch;
import com.stellar.platform.parser.spi.MeasurementFileParser;
import com.stellar.platform.parser.spi.ParserProvider;

import java.nio.file.Path;
import java.util.Optional;

/**
 * ServiceLoader registration for the BLF parser plugin.
 */
public final class BlfParserProvider implements ParserProvider {

    public static final String PROVIDER_ID = "stellar-parser-blf";

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public MeasurementFileParser createParser() {
        return new BlfMeasurementFileParser();
    }

    @Override
    public Optional<AutomotiveFileReader> createReader() {
        return Optional.of(new BlfFileReader());
    }

    @Override
    public Optional<ContentFormatMatch> probeContent(Path path) {
        return BlfContentProbe.probe(path);
    }
}
