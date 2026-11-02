package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.parser.mf4.analysis.MdfIssueSeverity;
import com.stellar.platform.parser.mf4.dynamicschema.MdfCanRole;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Maps decoded channel fields to Canonical CAN constituents using a compiled extraction plan.
 */
public final class MdfCanFieldResolver {

    private static final MdfCanRole[] CAN_ROLES = MdfCanRole.values();

    private MdfCanFieldResolver() {
    }

    public record ResolvedCanFields(
            long timestampNanos,
            int channel,
            long canId,
            boolean extended,
            FrameDirection direction,
            int dlc,
            byte[] payload,
            boolean canFd,
            boolean complete,
            List<MdfDecodeDiagnostic> diagnostics
    ) {
        public ResolvedCanFields {
            // Payload extraction already returns an exclusive byte array for this decoded record.
            // CanonicalCanFrame performs the public-domain defensive copy at the module boundary,
            // so copying again in this transient resolver object only doubles hot-path allocation.
            payload = payload == null ? new byte[0] : payload;
            diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
        }
    }

    public static ResolvedCanFields resolve(MdfDecodedRecord record) {
        MdfCanExtractionPlan plan = MdfCanExtractionPlan.compile(record.schema());
        return resolve(record, plan);
    }

    public static ResolvedCanFields resolve(MdfDecodedRecord record, MdfCanExtractionPlan plan) {
        List<MdfDecodeDiagnostic> diagnostics = new ArrayList<>();
        List<MdfDecodedField> fields = record.fields();
        if (plan == null) {
            plan = MdfCanExtractionPlan.compile(record.schema());
        }

        for (MdfCanRole role : CAN_ROLES) {
            if (plan.compileTimeAmbiguous(role)) {
                diagnostics.add(diag(
                        record,
                        MdfDecodeDiagnosticCode.AMBIGUOUS_REQUIRED_CAN_FIELD,
                        MdfIssueSeverity.WARNING,
                        "Ambiguous CAN role: " + role,
                        null
                ));
            }
        }

        MdfDecodedField timestamp = pickRequired(fields, plan.timestampIdx(), MdfCanRole.TIMESTAMP, record, diagnostics);
        MdfDecodedField canIdField = pickRequired(fields, plan.canIdIdx(), MdfCanRole.CAN_ID, record, diagnostics);
        MdfDecodedField bus = pickRequired(fields, plan.busIdx(), MdfCanRole.BUS_CHANNEL, record, diagnostics);
        MdfDecodedField dir = pickRequired(fields, plan.directionIdx(), MdfCanRole.DIRECTION, record, diagnostics);
        MdfDecodedField dlcField = pickRequired(fields, plan.dlcIdx(), MdfCanRole.DLC, record, diagnostics);
        MdfDecodedField payloadField = pickRequired(fields, plan.payloadIdx(), MdfCanRole.PAYLOAD, record, diagnostics);

        long timestampNanos = 0L;
        if (timestamp != null && timestamp.valid()) {
            OptionalLongNanos ts = toTimestampNanos(timestamp);
            if (ts.ok()) {
                timestampNanos = ts.value();
            } else {
                diagnostics.add(diag(
                        record,
                        MdfDecodeDiagnosticCode.INVALID_TIMESTAMP,
                        MdfIssueSeverity.WARNING,
                        ts.error(),
                        timestamp.schema().channelAddress()
                ));
            }
        }

        if (canIdField == null || !canIdField.valid()) {
            diagnostics.add(diag(
                    record,
                    MdfDecodeDiagnosticCode.MISSING_REQUIRED_CAN_FIELD,
                    MdfIssueSeverity.WARNING,
                    "No usable CAN ID field for record",
                    null
            ));
            return new ResolvedCanFields(
                    timestampNanos, 0, 0L, false, FrameDirection.UNKNOWN, 0, new byte[0], false, false, diagnostics
            );
        }

        long canId;
        try {
            canId = toUnsignedLong(canIdField);
        } catch (RuntimeException e) {
            diagnostics.add(diag(
                    record,
                    MdfDecodeDiagnosticCode.INVALID_CAN_ID,
                    MdfIssueSeverity.WARNING,
                    "CAN ID extraction failed: " + e.getMessage(),
                    canIdField.schema().channelAddress()
            ));
            return new ResolvedCanFields(
                    timestampNanos, 0, 0L, false, FrameDirection.UNKNOWN, 0, new byte[0], false, false, diagnostics
            );
        }

        if (canId > 0x1FFF_FFFFL) {
            diagnostics.add(diag(
                    record,
                    MdfDecodeDiagnosticCode.INVALID_CAN_ID,
                    MdfIssueSeverity.WARNING,
                    "CAN ID out of range: " + canId,
                    canIdField.schema().channelAddress()
            ));
            return new ResolvedCanFields(
                    timestampNanos, 0, canId, false, FrameDirection.UNKNOWN, 0, new byte[0], false, false, diagnostics
            );
        }
        long idBits = canIdField.schema().bitLength();
        boolean extended;
        MdfDecodedField ideField = findIdeFlag(fields, plan.ideIdx());
        if (ideField != null && ideField.valid()) {
            extended = toUnsignedLong(ideField) != 0L;
            if (!extended) {
                canId &= 0x7FFL;
            }
        } else if (idBits == 11L) {
            extended = false;
        } else if (idBits == 29L) {
            extended = true;
        } else {
            extended = canId > 0x7FFL;
        }

        int channel = 0;
        if (bus != null && bus.valid()) {
            channel = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, toUnsignedLong(bus)));
        }

        FrameDirection direction = FrameDirection.UNKNOWN;
        if (dir != null && dir.valid()) {
            direction = resolveDirection(dir);
        }

        byte[] payload = new byte[0];
        if (payloadField != null && payloadField.valid()) {
            payload = toPayloadBytes(payloadField);
        } else {
            diagnostics.add(diag(
                    record,
                    MdfDecodeDiagnosticCode.MISSING_REQUIRED_CAN_FIELD,
                    MdfIssueSeverity.WARNING,
                    "No usable payload field for record",
                    null
            ));
            return new ResolvedCanFields(
                    timestampNanos, channel, canId, extended, direction, 0, new byte[0], false, false, diagnostics
            );
        }

        int dlc;
        if (dlcField != null && dlcField.valid()) {
            long rawDlc = toUnsignedLong(dlcField);
            if (rawDlc < 0 || rawDlc > CanonicalCanFrame.MAX_PAYLOAD_BYTES) {
                diagnostics.add(diag(
                        record,
                        MdfDecodeDiagnosticCode.INVALID_DLC,
                        MdfIssueSeverity.WARNING,
                        "DLC out of range: " + rawDlc,
                        dlcField.schema().channelAddress()
                ));
                return new ResolvedCanFields(
                        timestampNanos, channel, canId, extended, direction, 0, payload, false, false, diagnostics
                );
            }
            dlc = (int) rawDlc;
        } else {
            dlc = payload.length;
        }

        if (payload.length < dlc) {
            diagnostics.add(diag(
                    record,
                    MdfDecodeDiagnosticCode.INSUFFICIENT_PAYLOAD,
                    MdfIssueSeverity.WARNING,
                    "Payload length " + payload.length + " < DLC " + dlc,
                    payloadField.schema().channelAddress()
            ));
            return new ResolvedCanFields(
                    timestampNanos, channel, canId, extended, direction, dlc, payload, false, false, diagnostics
            );
        }
        if (payload.length > dlc) {
            payload = Arrays.copyOf(payload, dlc);
        }

        boolean canFd = resolveCanFdIndication(fields, plan, payload.length, dlc);

        return new ResolvedCanFields(
                timestampNanos, channel, canId, extended, direction, dlc, payload, canFd, true, diagnostics
        );
    }

    private static boolean resolveCanFdIndication(
            List<MdfDecodedField> fields,
            MdfCanExtractionPlan plan,
            int payloadLength,
            int dlc
    ) {
        if (payloadLength > 8 || dlc > 8) {
            return true;
        }
        int[] preferred = plan.canFdPreferredIdx();
        int[] all = plan.canFdAllIdx();
        int[] use = preferred;
        boolean anyPreferredValid = false;
        for (int idx : preferred) {
            if (idx >= 0 && idx < fields.size() && fields.get(idx).valid()) {
                anyPreferredValid = true;
                break;
            }
        }
        if (!anyPreferredValid) {
            use = all;
        }
        for (int idx : use) {
            if (idx < 0 || idx >= fields.size()) {
                continue;
            }
            MdfDecodedField field = fields.get(idx);
            if (!field.valid()) {
                continue;
            }
            try {
                if (toUnsignedLong(field) != 0L) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // ignore non-numeric FD candidates
            }
        }
        return false;
    }

    private static MdfDecodedField findIdeFlag(List<MdfDecodedField> fields, int[] ideIdx) {
        for (int idx : ideIdx) {
            if (idx < 0 || idx >= fields.size()) {
                continue;
            }
            MdfDecodedField field = fields.get(idx);
            if (field.valid()) {
                return field;
            }
        }
        return null;
    }

    private static MdfDecodedField pickRequired(
            List<MdfDecodedField> fields,
            int[] indices,
            MdfCanRole role,
            MdfDecodedRecord record,
            List<MdfDecodeDiagnostic> diagnostics
    ) {
        if (indices == null || indices.length == 0) {
            return null;
        }
        MdfDecodedField first = null;
        MdfDecodedField second = null;
        for (int idx : indices) {
            if (idx < 0 || idx >= fields.size()) {
                continue;
            }
            MdfDecodedField field = fields.get(idx);
            if (!field.valid()) {
                continue;
            }
            if (first == null) {
                first = field;
            } else {
                second = field;
                break;
            }
        }
        if (first == null) {
            return null;
        }
        if (second != null) {
            double c0 = first.schema().canRoleCandidate().confidence();
            double c1 = second.schema().canRoleCandidate().confidence();
            if (Math.abs(c0 - c1) < 0.05) {
                diagnostics.add(diag(
                        record,
                        MdfDecodeDiagnosticCode.AMBIGUOUS_REQUIRED_CAN_FIELD,
                        MdfIssueSeverity.WARNING,
                        "Multiple candidates for " + role,
                        first.schema().channelAddress()
                ));
                return null;
            }
        }
        return first;
    }

    private static OptionalLongNanos toTimestampNanos(MdfDecodedField field) {
        java.util.OptionalLong nanos = MdfMasterTimestampConverter.toTimestampNanos(field);
        if (nanos.isEmpty()) {
            return OptionalLongNanos.fail("timestamp conversion failed");
        }
        return OptionalLongNanos.ok(nanos.getAsLong());
    }

    private static long toUnsignedLong(MdfDecodedField field) {
        MdfBitFieldExtractor.ExtractedValue value = field.value();
        if (value instanceof MdfBitFieldExtractor.ExtractedValue.IntegerValue iv) {
            return iv.rawUnsigned();
        }
        if (value instanceof MdfBitFieldExtractor.ExtractedValue.FloatValue fv) {
            return (long) fv.value();
        }
        throw new IllegalArgumentException("not a numeric field");
    }

    private static byte[] toPayloadBytes(MdfDecodedField field) {
        MdfBitFieldExtractor.ExtractedValue value = field.value();
        if (value instanceof MdfBitFieldExtractor.ExtractedValue.BytesValue bv) {
            byte[] bytes = bv.bytes();
            if (bytes.length > CanonicalCanFrame.MAX_PAYLOAD_BYTES) {
                return Arrays.copyOf(bytes, CanonicalCanFrame.MAX_PAYLOAD_BYTES);
            }
            return bytes;
        }
        if (value instanceof MdfBitFieldExtractor.ExtractedValue.IntegerValue iv) {
            int bitLength = (int) Math.min(64, field.schema().bitLength());
            int byteCount = Math.max(1, (bitLength + 7) / 8);
            byteCount = Math.min(byteCount, CanonicalCanFrame.MAX_PAYLOAD_BYTES);
            byte[] out = new byte[byteCount];
            long raw = iv.rawUnsigned();
            for (int i = 0; i < byteCount; i++) {
                out[i] = (byte) ((raw >> (8 * i)) & 0xFF);
            }
            return out;
        }
        return new byte[0];
    }

    private static FrameDirection resolveDirection(MdfDecodedField field) {
        if (field.value() instanceof MdfBitFieldExtractor.ExtractedValue.IntegerValue iv) {
            long v = iv.rawUnsigned();
            if (v == 0) {
                return FrameDirection.RX;
            }
            if (v == 1) {
                return FrameDirection.TX;
            }
        }
        String name = field.schema().name().toLowerCase(Locale.ROOT);
        if (name.contains("tx") || name.contains("transmit")) {
            return FrameDirection.TX;
        }
        if (name.contains("rx") || name.contains("receive")) {
            return FrameDirection.RX;
        }
        return FrameDirection.UNKNOWN;
    }

    private static MdfDecodeDiagnostic diag(
            MdfDecodedRecord record,
            MdfDecodeDiagnosticCode code,
            MdfIssueSeverity severity,
            String reason,
            Long channelAddress
    ) {
        return new MdfDecodeDiagnostic(
                code,
                severity,
                reason,
                record.schema().dataGroupAddress(),
                record.schema().channelGroupAddress(),
                channelAddress,
                record.physicalOffset()
        );
    }

    private record OptionalLongNanos(boolean ok, long value, String error) {
        static OptionalLongNanos ok(long value) {
            return new OptionalLongNanos(true, value, null);
        }

        static OptionalLongNanos fail(String error) {
            return new OptionalLongNanos(false, 0L, error);
        }
    }
}