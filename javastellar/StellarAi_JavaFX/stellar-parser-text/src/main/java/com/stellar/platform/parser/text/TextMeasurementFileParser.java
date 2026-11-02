package com.stellar.platform.parser.text;

import com.stellar.platform.domain.MeasurementDescriptor;
import com.stellar.platform.domain.MeasurementId;
import com.stellar.platform.parser.spi.MeasurementFileParser;
import com.stellar.platform.parser.spi.ParserContext;
import com.stellar.platform.parser.spi.ParserException;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Descriptor open for a recognized text measurement. Samples are streamed by {@link TextTableReader}.
 */
public final class TextMeasurementFileParser implements MeasurementFileParser {

    @Override
    public String formatKey() {
        return TextTableProbe.FORMAT_KEY;
    }

    @Override
    public boolean supports(Path path) {
        return TextTableProbe.probe(path).isPresent();
    }

    @Override
    public MeasurementDescriptor open(Path path, ParserContext context) throws ParserException {
        if (!supports(path) || !Files.isRegularFile(path)) {
            throw new ParserException("StellarAI could not determine the measurement structure automatically.");
        }
        String name = path.getFileName().toString();
        return new MeasurementDescriptor(new MeasurementId("text:" + name), name);
    }
}
