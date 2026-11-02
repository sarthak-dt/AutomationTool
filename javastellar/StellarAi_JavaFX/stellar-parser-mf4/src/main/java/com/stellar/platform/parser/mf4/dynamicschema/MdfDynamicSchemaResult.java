package com.stellar.platform.parser.mf4.dynamicschema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate Dynamic Schema Builder output.
 */
public final class MdfDynamicSchemaResult {

    private final List<MdfRecordSchema> recordSchemas;
    private final Map<Long, Map<Long, MdfRecordSchema>> byDgAndRecordId;
    private final List<MdfSchemaDiagnostic> diagnostics;

    public MdfDynamicSchemaResult(
            List<MdfRecordSchema> recordSchemas,
            List<MdfSchemaDiagnostic> diagnostics
    ) {
        this.recordSchemas = List.copyOf(Objects.requireNonNull(recordSchemas, "recordSchemas"));
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        Map<Long, Map<Long, MdfRecordSchema>> lookup = new LinkedHashMap<>();
        for (MdfRecordSchema schema : this.recordSchemas) {
            lookup.computeIfAbsent(schema.dataGroupAddress(), dg -> new LinkedHashMap<>())
                    .put(schema.recordId(), schema);
        }
        Map<Long, Map<Long, MdfRecordSchema>> frozen = new LinkedHashMap<>();
        for (Map.Entry<Long, Map<Long, MdfRecordSchema>> e : lookup.entrySet()) {
            frozen.put(e.getKey(), Map.copyOf(e.getValue()));
        }
        this.byDgAndRecordId = Map.copyOf(frozen);
    }

    public List<MdfRecordSchema> recordSchemas() {
        return recordSchemas;
    }

    public List<MdfSchemaDiagnostic> diagnostics() {
        return diagnostics;
    }

    public Map<Long, Map<Long, MdfRecordSchema>> byDgAndRecordId() {
        return byDgAndRecordId;
    }

    public Optional<MdfRecordSchema> find(long dgAddress, long recordId) {
        Map<Long, MdfRecordSchema> byId = byDgAndRecordId.get(dgAddress);
        if (byId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(recordId));
    }

    public List<MdfRecordSchema> usableSchemas() {
        return recordSchemas.stream().filter(MdfRecordSchema::usable).toList();
    }
}
