package com.stellar.platform.parser.mf4;

import com.stellar.platform.domain.MeasurementDescriptor;
import com.stellar.platform.domain.MeasurementId;
import com.stellar.platform.parser.mf4.id.MdfIdentificationReader;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.spi.MeasurementFileParser;
import com.stellar.platform.parser.spi.ParserContext;
import com.stellar.platform.parser.spi.ParserException;

import java.nio.file.Path;

/**
 * MF4 measurement parser entry — identification-aware open/descriptor.
 * Frame streaming is provided by {@link Mf4FileReader}.
 */
public final class Mf4MeasurementFileParser implements MeasurementFileParser {

    public static final String FORMAT_KEY = "mf4";

    @Override
    public String formatKey() {
        return FORMAT_KEY;
    }

    @Override
    public boolean supports(Path path) {
        return MdfContentProbe.parserCanRead(path);
    }

    @Override
    public MeasurementDescriptor open(Path path, ParserContext context) throws ParserException {
        if (path == null) {
            throw new ParserException("MF4 path must not be null");
        }
        try (MdfFileAccess access = MdfFileAccess.open(path)) {
            if (!MdfIdentificationReader.looksLikeMdf(access)) {
                throw new ParserException("Not an MDF file (identification signature mismatch): " + path);
            }
            MdfIdentificationReader.read(access);
        }
        String fileName = path.getFileName().toString();
        return new MeasurementDescriptor(new MeasurementId("mf4:" + fileName), fileName);
    }
}
