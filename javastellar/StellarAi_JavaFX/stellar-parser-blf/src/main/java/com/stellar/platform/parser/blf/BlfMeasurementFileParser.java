package com.stellar.platform.parser.blf;

import com.stellar.platform.domain.MeasurementDescriptor;
import com.stellar.platform.domain.MeasurementId;
import com.stellar.platform.parser.spi.MeasurementFileParser;
import com.stellar.platform.parser.spi.ParserContext;
import com.stellar.platform.parser.spi.ParserException;

import java.nio.file.Path;

/**
 * BLF measurement descriptor. Frames are streamed by {@link BlfFileReader}.
 */
public final class BlfMeasurementFileParser implements MeasurementFileParser {

    static final String FORMAT_KEY = "blf";

    @Override
    public String formatKey() {
        return FORMAT_KEY;
    }

    @Override
    public boolean supports(Path path) {
        return BlfContentProbe.parserCanRead(path);
    }

    @Override
    public MeasurementDescriptor open(Path path, ParserContext context) throws ParserException {
        if (!supports(path)) {
            throw new ParserException("Not a BLF path: " + path);
        }
        if (!java.nio.file.Files.isRegularFile(path)) {
            throw new ParserException("BLF file could not be parsed: not a readable file: " + path);
        }
        String fileName = path.getFileName().toString();
        return new MeasurementDescriptor(new MeasurementId("blf:" + fileName), fileName);
    }
}
