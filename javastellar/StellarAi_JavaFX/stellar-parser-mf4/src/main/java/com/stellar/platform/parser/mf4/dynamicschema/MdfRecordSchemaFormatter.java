package com.stellar.platform.parser.mf4.dynamicschema;

/**
 * Deterministic human-readable formatter for dynamic record schemas.
 */
public final class MdfRecordSchemaFormatter {

    private MdfRecordSchemaFormatter() {
    }

    public static String format(MdfDynamicSchemaResult result) {
        StringBuilder sb = new StringBuilder();
        long lastDg = Long.MIN_VALUE;
        for (MdfRecordSchema schema : result.recordSchemas()) {
            if (schema.dataGroupAddress() != lastDg) {
                lastDg = schema.dataGroupAddress();
                sb.append("DG @ 0x").append(Long.toHexString(schema.dataGroupAddress())).append('\n');
                sb.append("  recordIdSize = ").append(schema.recordIdSize()).append('\n');
                sb.append("  dataSource = ").append(formatSource(schema.dataSource())).append('\n');
            }
            sb.append("  CG @ 0x").append(Long.toHexString(schema.channelGroupAddress())).append('\n');
            sb.append("    recordId = ").append(schema.recordId()).append('\n');
            sb.append("    declaredCycles = ").append(schema.declaredCycleCount()).append('\n');
            sb.append("    dataBytes = ").append(schema.dataBytesPerRecord()).append('\n');
            sb.append("    invalidationBytes = ").append(schema.invalidationBytesPerRecord()).append('\n');
            sb.append("    completeRecordLength = ").append(schema.completeRecordLength()).append('\n');
            sb.append("    status = ").append(schema.status()).append('\n');
            sb.append("    physicalDataExists = ").append(schema.physicalDataExists()).append('\n');
            sb.append("    Channels:").append('\n');
            for (MdfChannelFieldSchema field : schema.channelFields()) {
                sb.append("      ").append(field.name().isBlank() ? "<unnamed>" : field.name()).append('\n');
                sb.append("        byteOffset = ").append(field.byteOffset()).append('\n');
                sb.append("        bitOffset = ").append(field.bitOffset()).append('\n');
                sb.append("        bitLength = ").append(field.bitLength()).append('\n');
                sb.append("        dataType = ").append(field.dataTypeKind()).append('\n');
                sb.append("        supported = ").append(field.supported()).append('\n');
                if (field.canRoleCandidate().role() != MdfCanRole.NONE) {
                    sb.append("        canRole = ").append(field.canRoleCandidate().role())
                            .append(" (").append(field.canRoleCandidate().confidence()).append(')')
                            .append('\n');
                }
            }
        }
        if (!result.diagnostics().isEmpty()) {
            sb.append("Diagnostics:").append('\n');
            for (MdfSchemaDiagnostic d : result.diagnostics()) {
                sb.append("  - [").append(d.severity()).append("] ")
                        .append(d.code()).append(": ").append(d.reason()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String formatSource(MdfDataSourceSchema source) {
        if (source.orderedNodes().isEmpty()) {
            return source.rootType().name() + " (empty)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < source.orderedNodes().size(); i++) {
            if (i > 0) {
                sb.append(" -> ");
            }
            sb.append(source.orderedNodes().get(i).blockType().name());
        }
        return sb.toString();
    }
}
