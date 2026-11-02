package com.stellar.platform.parser.text;

import java.nio.charset.Charset;
import java.util.List;

/**
 * Inferred text measurement layout. Built from a bounded sample so a later mapping UI can
 * replace the inference without changing the normalized sample type.
 */
public record TextLayout(
        Kind kind,
        char delimiter,
        Charset charset,
        int timeColumn,
        double nanosPerUnit,
        boolean isoTimestamp,
        List<SignalColumn> signals,
        boolean header,
        String confidence
) {
    public enum Kind {
        DELIMITED,
        STRUCTURED
    }

    public record SignalColumn(int index, String name, String unit, boolean booleanValues) {
    }
}
