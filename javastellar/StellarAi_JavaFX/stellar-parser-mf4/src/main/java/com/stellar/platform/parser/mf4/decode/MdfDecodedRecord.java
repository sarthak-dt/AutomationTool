package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema;

import java.util.List;
import java.util.Objects;

/**
 * One fully decoded runtime record (fields extracted; not yet a CanonicalCanFrame).
 */
public record MdfDecodedRecord(
        MdfRecordSchema schema,
        long physicalOffset,
        long recordId,
        List<MdfDecodedField> fields,
        List<MdfDecodeDiagnostic> diagnostics
) {

    public MdfDecodedRecord {
        Objects.requireNonNull(schema, "schema");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }
}
