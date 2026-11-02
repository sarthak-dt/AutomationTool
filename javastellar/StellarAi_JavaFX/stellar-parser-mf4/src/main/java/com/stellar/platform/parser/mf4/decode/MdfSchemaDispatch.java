package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema;

import java.util.Objects;

/**
 * Classifies runtime Channel Group schemas for NORMAL and RECOVERED decode paths.
 */
public final class MdfSchemaDispatch {

    public enum Kind {
        /** Bus-event CG that emits CanonicalCanFrame. */
        CAN_FRAME,
        /** Supported native scalar measurement CG. */
        NATIVE_MEASUREMENT,
        /** LIN / error / other non-measurement CGs - skip with diagnostic. */
        SKIP_NON_MEASUREMENT
    }

    private MdfSchemaDispatch() {
    }

    public static Kind classify(MdfRecordSchema schema) {
        Objects.requireNonNull(schema, "schema");
        if (MdfNativeMeasurementResolver.emitsNativeMeasurements(schema)) {
            return Kind.NATIVE_MEASUREMENT;
        }
        if (schema.emitsCanonicalCanFrames()) {
            return Kind.CAN_FRAME;
        }
        return Kind.SKIP_NON_MEASUREMENT;
    }
}