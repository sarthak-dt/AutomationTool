package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.dynamicschema.MdfChannelFieldSchema;

import java.util.Objects;

/**
 * One decoded channel field within a runtime record.
 */
public record MdfDecodedField(
        MdfChannelFieldSchema schema,
        MdfBitFieldExtractor.ExtractedValue value,
        boolean invalid,
        String diagnostic
) {

    public MdfDecodedField {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(value, "value");
    }

    public boolean valid() {
        return !invalid && !(value instanceof MdfBitFieldExtractor.ExtractedValue.Unsupported);
    }
}
