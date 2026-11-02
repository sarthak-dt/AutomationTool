package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.dynamicschema.MdfChannelFieldSchema;
import com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema;

import java.util.List;
import java.util.Objects;

/**
 * Immutable compiled record layout and dispatch, built once per {@link MdfRecordSchema}.
 */
public final class MdfRecordDecodePlan {

    private final MdfRecordSchema schema;
    private final MdfSchemaDispatch.Kind kind;
    private final int recordIdSize;
    private final int dataBytes;
    private final int invalidationBytes;
    private final int completeRecordLength;
    private final boolean vlsdChannelGroup;
    private final boolean hasResolvableVlsd;
    private final MdfFieldDecodePlan[] fields;
    private final MdfCanExtractionPlan canPlan;

    private MdfRecordDecodePlan(
            MdfRecordSchema schema,
            MdfSchemaDispatch.Kind kind,
            int recordIdSize,
            int dataBytes,
            int invalidationBytes,
            int completeRecordLength,
            boolean vlsdChannelGroup,
            boolean hasResolvableVlsd,
            MdfFieldDecodePlan[] fields,
            MdfCanExtractionPlan canPlan
    ) {
        this.schema = schema;
        this.kind = kind;
        this.recordIdSize = recordIdSize;
        this.dataBytes = dataBytes;
        this.invalidationBytes = invalidationBytes;
        this.completeRecordLength = completeRecordLength;
        this.vlsdChannelGroup = vlsdChannelGroup;
        this.hasResolvableVlsd = hasResolvableVlsd;
        this.fields = fields;
        this.canPlan = canPlan;
    }

    public static MdfRecordDecodePlan compile(MdfRecordSchema schema) {
        Objects.requireNonNull(schema, "schema");
        List<MdfChannelFieldSchema> channelFields = schema.channelFields();
        MdfFieldDecodePlan[] fields = new MdfFieldDecodePlan[channelFields.size()];
        boolean vlsd = false;
        for (int i = 0; i < channelFields.size(); i++) {
            fields[i] = MdfFieldDecodePlan.compile(channelFields.get(i), i);
            if (fields[i].resolvableVlsd()) {
                vlsd = true;
            }
        }
        MdfSchemaDispatch.Kind kind = MdfSchemaDispatch.classify(schema);
        MdfCanExtractionPlan canPlan = kind == MdfSchemaDispatch.Kind.CAN_FRAME
                ? MdfCanExtractionPlan.compile(schema)
                : null;
        int complete = schema.completeRecordLength() > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) schema.completeRecordLength();
        return new MdfRecordDecodePlan(
                schema,
                kind,
                Math.max(0, schema.recordIdSize()),
                (int) Math.min(Integer.MAX_VALUE, Math.max(0L, schema.dataBytesPerRecord())),
                (int) Math.min(Integer.MAX_VALUE, Math.max(0L, schema.invalidationBytesPerRecord())),
                complete,
                schema.vlsdChannelGroup(),
                vlsd,
                fields,
                canPlan
        );
    }

    public MdfRecordSchema schema() {
        return schema;
    }

    public MdfSchemaDispatch.Kind kind() {
        return kind;
    }

    public int recordIdSize() {
        return recordIdSize;
    }

    public int dataBytes() {
        return dataBytes;
    }

    public int invalidationBytes() {
        return invalidationBytes;
    }

    public int dataRegionOffset() {
        return recordIdSize;
    }

    public int invalidationRegionOffset() {
        return recordIdSize + dataBytes;
    }

    public int completeRecordLength() {
        return completeRecordLength;
    }

    public boolean vlsdChannelGroup() {
        return vlsdChannelGroup;
    }

    public boolean hasResolvableVlsd() {
        return hasResolvableVlsd;
    }

    public MdfFieldDecodePlan[] fields() {
        return fields;
    }

    public MdfCanExtractionPlan canPlan() {
        return canPlan;
    }
}
