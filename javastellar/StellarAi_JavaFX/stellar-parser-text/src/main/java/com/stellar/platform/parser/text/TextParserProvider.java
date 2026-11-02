package com.stellar.platform.parser.text;

import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.ContentFormatMatch;
import com.stellar.platform.parser.spi.MeasurementFileParser;
import com.stellar.platform.parser.spi.ParserProvider;

import java.nio.file.Path;
import java.util.Optional;

/**
 * ServiceLoader registration for conservative text measurement ingestion.
 */
public final class TextParserProvider implements ParserProvider {

    public static final String PROVIDER_ID = "stellar-parser-text";

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public MeasurementFileParser createParser() {
        return new TextMeasurementFileParser();
    }

    @Override
    public Optional<AutomotiveFileReader> createReader() {
        return Optional.of(new TextTableReader());
    }

    @Override
    public Optional<ContentFormatMatch> probeContent(Path path) {
        return TextTableProbe.probe(path);
    }
}
