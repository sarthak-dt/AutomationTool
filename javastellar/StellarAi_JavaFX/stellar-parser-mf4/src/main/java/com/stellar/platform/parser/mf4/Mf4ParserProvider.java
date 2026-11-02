package com.stellar.platform.parser.mf4;

import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.ContentFormatMatch;
import com.stellar.platform.parser.spi.MeasurementFileParser;
import com.stellar.platform.parser.spi.ParserProvider;

import java.nio.file.Path;
import java.util.Optional;

/**
 * ServiceLoader registration for the MF4 parser plugin.
 */
public final class Mf4ParserProvider implements ParserProvider {

    public static final String PROVIDER_ID = "stellar-parser-mf4";

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public MeasurementFileParser createParser() {
        return new Mf4MeasurementFileParser();
    }

    @Override
    public Optional<AutomotiveFileReader> createReader() {
        return Optional.of(new Mf4FileReader());
    }

    @Override
    public Optional<ContentFormatMatch> probeContent(Path path) {
        return MdfContentProbe.probe(path);
    }
}
