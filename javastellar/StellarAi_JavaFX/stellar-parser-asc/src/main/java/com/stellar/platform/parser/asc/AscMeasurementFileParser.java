package com.stellar.platform.parser.asc;

import com.stellar.platform.domain.MeasurementDescriptor;
import com.stellar.platform.domain.MeasurementId;
import com.stellar.platform.parser.spi.MeasurementFileParser;
import com.stellar.platform.parser.spi.ParserContext;
import com.stellar.platform.parser.spi.ParserException;

import java.nio.file.Path;

/**
 * ASC measurement descriptor. Frames are streamed by {@link AscFileReader}.
 */
public final class AscMeasurementFileParser implements MeasurementFileParser {

    static final String FORMAT_KEY = "asc";

    @Override
    public String formatKey() {
        return FORMAT_KEY;
    }

    @Override
    public boolean supports(Path path) {
        return AscContentProbe.parserCanRead(path);
    }

    @Override
    public MeasurementDescriptor open(Path path, ParserContext context) throws ParserException {
        if (!supports(path)) {
            throw new ParserException("Not an ASC path: " + path);
        }
        if (!java.nio.file.Files.isRegularFile(path)) {
            throw new ParserException("ASC file could not be parsed: not a readable file: " + path);
        }
        String fileName = path.getFileName().toString();
        return new MeasurementDescriptor(new MeasurementId("asc:" + fileName), fileName);
    }
}
