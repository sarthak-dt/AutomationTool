package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.analysis.MdfIssueSeverity;
import com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes all channel fields from one raw record using a compiled {@link MdfRecordDecodePlan}.
 */
public final class MdfRecordFieldDecoder {

    private MdfRecordFieldDecoder() {
    }

    public static MdfDecodedRecord decode(
            MdfRecordSchema schema,
            byte[] completeRecord,
            long physicalOffset,
            long recordId
    ) {
        MdfRecordDecodePlan plan = MdfRecordDecodePlan.compile(schema);
        int len = completeRecord == null ? 0 : completeRecord.length;
        return decode(plan, completeRecord, 0, len, physicalOffset, recordId);
    }

    public static MdfDecodedRecord decode(
            MdfRecordDecodePlan plan,
            byte[] buffer,
            int offset,
            int length,
            long physicalOffset,
            long recordId
    ) {
        MdfRecordSchema schema = plan.schema();
        if (buffer == null || length == 0) {
            List<MdfDecodeDiagnostic> diagnostics = new ArrayList<>(1);
            diagnostics.add(new MdfDecodeDiagnostic(
                    MdfDecodeDiagnosticCode.TRUNCATED_RECORD,
                    MdfIssueSeverity.RECOVERABLE,
                    "Empty record buffer",
                    schema.dataGroupAddress(),
                    schema.channelGroupAddress(),
                    null,
                    physicalOffset
            ));
            return new MdfDecodedRecord(schema, physicalOffset, recordId, List.of(), diagnostics);
        }

        int idSize = plan.recordIdSize();
        int dataBytes = plan.dataBytes();
        int invalBytes = plan.invalidationBytes();
        int expected = idSize + dataBytes + invalBytes;
        if (length < expected) {
            List<MdfDecodeDiagnostic> diagnostics = new ArrayList<>(1);
            diagnostics.add(new MdfDecodeDiagnostic(
                    MdfDecodeDiagnosticCode.TRUNCATED_RECORD,
                    MdfIssueSeverity.RECOVERABLE,
                    "Record buffer length " + length + " < expected " + expected,
                    schema.dataGroupAddress(),
                    schema.channelGroupAddress(),
                    null,
                    physicalOffset
            ));
            return new MdfDecodedRecord(schema, physicalOffset, recordId, List.of(), diagnostics);
        }

        int dataBase = offset + plan.dataRegionOffset();
        int invalBase = offset + plan.invalidationRegionOffset();
        MdfFieldDecodePlan[] fieldPlans = plan.fields();
        List<MdfDecodedField> fields = new ArrayList<>(fieldPlans.length);
        List<MdfDecodeDiagnostic> diagnostics = null;

        for (MdfFieldDecodePlan fieldPlan : fieldPlans) {
            if (fieldPlan.compositionContainer()) {
                fields.add(new MdfDecodedField(
                        fieldPlan.field(),
                        new MdfBitFieldExtractor.ExtractedValue.Unsupported("composition container (not scalar)"),
                        false,
                        "composition container skipped"
                ));
                continue;
            }
            boolean invalid = MdfBitFieldExtractor.isInvalidationBitSet(buffer, invalBase, invalBytes, fieldPlan);
            MdfBitFieldExtractor.ExtractedValue value =
                    MdfBitFieldExtractor.extract(buffer, dataBase, dataBytes, fieldPlan);
            String diag = null;
            if (invalid) {
                diag = "invalidation bit set";
                if (diagnostics == null) {
                    diagnostics = new ArrayList<>();
                }
                diagnostics.add(new MdfDecodeDiagnostic(
                        MdfDecodeDiagnosticCode.INVALIDATION_MARKED_DATA,
                        MdfIssueSeverity.INFO,
                        "Channel '" + fieldPlan.field().name() + "' marked invalid",
                        schema.dataGroupAddress(),
                        schema.channelGroupAddress(),
                        fieldPlan.field().channelAddress(),
                        physicalOffset
                ));
            }
            if (value instanceof MdfBitFieldExtractor.ExtractedValue.Unsupported u) {
                diag = u.reason();
                MdfDecodeDiagnosticCode code = u.reason().contains("exceed")
                        ? MdfDecodeDiagnosticCode.INVALID_FIELD_BOUNDARY
                        : MdfDecodeDiagnosticCode.UNSUPPORTED_DATA_TYPE;
                if (diagnostics == null) {
                    diagnostics = new ArrayList<>();
                }
                diagnostics.add(new MdfDecodeDiagnostic(
                        code,
                        MdfIssueSeverity.WARNING,
                        "Channel '" + fieldPlan.field().name() + "': " + u.reason(),
                        schema.dataGroupAddress(),
                        schema.channelGroupAddress(),
                        fieldPlan.field().channelAddress(),
                        physicalOffset
                ));
            }
            fields.add(new MdfDecodedField(fieldPlan.field(), value, invalid, diag));
        }
        return new MdfDecodedRecord(
                schema,
                physicalOffset,
                recordId,
                fields,
                diagnostics == null ? List.of() : diagnostics
        );
    }
}