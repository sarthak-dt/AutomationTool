package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.domain.CanonicalMeasurementSample;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion;
import com.stellar.platform.parser.mf4.dynamicschema.MdfChannelFieldSchema;
import com.stellar.platform.parser.mf4.dynamicschema.MdfNativeMeasurementSchema;
import com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Decodes non-bus MF4 measurement Channel Groups into {@link CanonicalMeasurementSample} values.
 * <p>
 * Does not synthesize CAN frames. Time is taken from the CG master/time channel metadata
 * (sync type / channel type), not from hard-coded names or offsets.
 * <p>
 * Native samples do not require CAN ID match, DBC message match, or multiplex activation.
 */
public final class MdfNativeMeasurementResolver {

    /** Provenance message tag applied when mapping samples to {@code CanonicalSignal}. */
    public static final String MESSAGE_NAME = "MF4_MEASUREMENT";

    private MdfNativeMeasurementResolver() {
    }

    /**
     * True when the schema represents fixed-length native scalar measurements
     * rather than CAN/LIN/error bus event frames.
     */
    public static boolean emitsNativeMeasurements(MdfRecordSchema schema) {
        return MdfNativeMeasurementSchema.tryFrom(schema).isPresent();
    }

    public static Optional<MdfNativeMeasurementSchema> schemaOf(MdfRecordSchema schema) {
        return MdfNativeMeasurementSchema.tryFrom(schema);
    }

    public static List<CanonicalMeasurementSample> resolve(
            MdfDecodedRecord record,
            long sequenceNumber
    ) {
        Optional<MdfNativeMeasurementSchema> nativeSchema = schemaOf(record.schema());
        if (nativeSchema.isEmpty()) {
            return List.of();
        }
        MdfNativeMeasurementSchema ns = nativeSchema.get();
        long timestampNanos = 0L;
        for (MdfDecodedField field : record.fields()) {
            if (field.schema().channelAddress() == ns.masterTimeChannel().channelAddress() && field.valid()) {
                OptionalLong ts = MdfMasterTimestampConverter.toTimestampNanos(field);
                if (ts.isPresent()) {
                    timestampNanos = ts.getAsLong();
                }
                break;
            }
        }

        List<CanonicalMeasurementSample> out = new ArrayList<>();
        for (MdfChannelFieldSchema fs : ns.measurementChannels()) {
            MdfDecodedField field = findField(record, fs.channelAddress());
            if (field == null || !field.valid()) {
                continue;
            }
            Optional<Physical> physical = toPhysical(field);
            if (physical.isEmpty()) {
                continue;
            }
            Physical p = physical.get();
            out.add(new CanonicalMeasurementSample(
                    fs.leafName().isBlank() ? fs.name() : fs.leafName(),
                    timestampNanos,
                    (int) Math.min(Integer.MAX_VALUE, Math.max(0L, record.schema().recordId())),
                    p.physical,
                    p.raw,
                    fs.unit(),
                    p.status,
                    new SourceReference(sequenceNumber + 1, record.physicalOffset()),
                    p.validity
            ));
        }
        return List.copyOf(out);
    }

    private static MdfDecodedField findField(MdfDecodedRecord record, long channelAddress) {
        for (MdfDecodedField field : record.fields()) {
            if (field.schema().channelAddress() == channelAddress) {
                return field;
            }
        }
        return null;
    }

    private static Optional<Physical> toPhysical(MdfDecodedField field) {
        MdfChannelConversion conversion = field.schema().conversion();
        MdfBitFieldExtractor.ExtractedValue value = field.value();
        if (value instanceof MdfBitFieldExtractor.ExtractedValue.FloatValue fv) {
            double raw = fv.value();
            if (!Double.isFinite(raw)) {
                return Optional.of(new Physical(
                        Double.NaN, null, "non-finite raw value", SignalValidity.NON_FINITE_VALUE));
            }
            if (conversion.isLinear()) {
                return Optional.of(classified(conversion.toPhysical(raw).doubleValue(), null, null));
            }
            return Optional.of(apply(conversion, raw, null));
        }
        if (value instanceof MdfBitFieldExtractor.ExtractedValue.IntegerValue iv) {
            long raw = iv.signedOrUnsigned();
            if (conversion.isIdentity() || conversion.isLinear()) {
                double physical = conversion.isLinear()
                        ? conversion.toPhysical(raw).doubleValue()
                        : raw;
                return Optional.of(classified(physical, raw, null));
            }
            return Optional.of(apply(conversion, raw, raw));
        }
        return Optional.empty();
    }

    /**
     * Unapplied and failed conversions keep the raw value and a status string.
     * The physical slot is NaN so the raw number is not presented as engineering data.
     * One channel does not suppress the rest of the record.
     */
    private static Physical apply(MdfChannelConversion conversion, double numericRaw, Long storedRaw) {
        MdfChannelConversion.ConversionResult result = conversion.convert(numericRaw);
        if (!result.applied()) {
            String status = result.statusDetail() == null || result.statusDetail().isBlank()
                    ? "unsupported conversion cc_type=" + conversion.type()
                    : result.statusDetail();
            return new Physical(Double.NaN, storedRaw, status, SignalValidity.CONVERSION_FAILED);
        }
        if (!result.text().isBlank()) {
            // Source label, not a numeric physical value. Same outcome as an MDF3 text result.
            return new Physical(Double.NaN, storedRaw, result.text(), SignalValidity.VALID);
        }
        return classified(result.physical(), storedRaw, null);
    }

    private static Physical classified(double physical, Long raw, String status) {
        if (!Double.isFinite(physical)) {
            return new Physical(physical, raw, status, SignalValidity.NON_FINITE_VALUE);
        }
        return new Physical(physical, raw, status, SignalValidity.VALID);
    }

    private record Physical(double physical, Long raw, String status, SignalValidity validity) {
    }
}
