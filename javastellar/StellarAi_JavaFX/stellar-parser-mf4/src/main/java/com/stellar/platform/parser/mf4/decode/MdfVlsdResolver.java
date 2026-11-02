package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.parser.mf4.analysis.MdfIssueSeverity;
import com.stellar.platform.parser.mf4.block.MdfBlockHeader;
import com.stellar.platform.parser.mf4.block.MdfBlockHeaderReader;
import com.stellar.platform.parser.mf4.block.MdfBlockType;
import com.stellar.platform.parser.mf4.dynamicschema.MdfChannelFieldSchema;
import com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.io.MdfSafeMath;
import com.stellar.platform.parser.spi.ParserException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves Variable Length Signal Data (CN channel type 1) offsets into logical field bytes.
 * <p>
 * ASAM layout at the referenced SD/DT offset: {@code uint32 LE length} + {@code length} payload bytes.
 * When {@code cn_data} links to a VLSD Channel Group, offsets address the contiguous logical stream of
 * those length-prefixed bodies (record IDs excluded) within the parent DG data source.
 * <p>
 * Memory is bounded to the individual referenced payload (capped at {@link #MAX_VLSD_PAYLOAD_BYTES}).
 * CG-linked resolution uses a directory index of offsets — not a full payload copy.
 */
public final class MdfVlsdResolver {

    /** ASAM cn_type: variable length data channel. */
    public static final int CHANNEL_TYPE_VLSD = 1;

    /** Upper bound for a single VLSD payload (CAN FD max plus headroom for non-CAN uses). */
    public static final int MAX_VLSD_PAYLOAD_BYTES = 256;

    private MdfVlsdResolver() {
    }

    public record ResolvedPayload(byte[] bytes, List<MdfDecodeDiagnostic> diagnostics) {
        public ResolvedPayload {
            bytes = bytes == null ? new byte[0] : Arrays.copyOf(bytes, bytes.length);
            diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
        }

        public boolean success() {
            return diagnostics.isEmpty() && bytes.length >= 0;
        }
    }

    public static boolean isVlsdChannel(MdfChannelFieldSchema field) {
        return field != null && field.channelType() == CHANNEL_TYPE_VLSD;
    }

    /**
     * Resolves a VLSD offset extracted from a fixed record into payload bytes from the linked SD/DT.
     * CG-linked VLSD requires {@link #resolve(MdfFileAccess, MdfChannelFieldSchema, long, Long, Long, long, MdfVlsdEnrichmentContext)}.
     */
    public static ResolvedPayload resolve(
            MdfFileAccess access,
            MdfChannelFieldSchema field,
            long vlsdOffset,
            Long dataGroupAddress,
            Long channelGroupAddress,
            long physicalRecordOffset
    ) throws ParserException {
        return resolve(access, field, vlsdOffset, dataGroupAddress, channelGroupAddress, physicalRecordOffset, null);
    }

    public static ResolvedPayload resolve(
            MdfFileAccess access,
            MdfChannelFieldSchema field,
            long vlsdOffset,
            Long dataGroupAddress,
            Long channelGroupAddress,
            long physicalRecordOffset,
            MdfVlsdEnrichmentContext context
    ) throws ParserException {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(field, "field");
        List<MdfDecodeDiagnostic> diags = new ArrayList<>();

        if (field.dataLink() == 0L) {
            diags.add(diag(MdfDecodeDiagnosticCode.VLSD_INVALID_REFERENCE, dataGroupAddress, channelGroupAddress,
                    field.channelAddress(), physicalRecordOffset,
                    "VLSD channel '" + field.name() + "' has no cn_data link"));
            return new ResolvedPayload(new byte[0], diags);
        }
        if (vlsdOffset < 0L) {
            diags.add(diag(MdfDecodeDiagnosticCode.VLSD_INVALID_REFERENCE, dataGroupAddress, channelGroupAddress,
                    field.channelAddress(), physicalRecordOffset,
                    "Negative VLSD offset " + vlsdOffset));
            return new ResolvedPayload(new byte[0], diags);
        }

        MdfBlockHeader header = context != null
                ? context.blockHeader(access, field.dataLink())
                : MdfBlockHeaderReader.read(access, field.dataLink());
        if (header.type() == MdfBlockType.CG) {
            return resolveFromVlsdChannelGroup(
                    access, field, vlsdOffset, dataGroupAddress, channelGroupAddress, physicalRecordOffset, context
            );
        }
        if (header.type() != MdfBlockType.SD && header.type() != MdfBlockType.DT) {
            diags.add(diag(MdfDecodeDiagnosticCode.VLSD_INVALID_REFERENCE, dataGroupAddress, channelGroupAddress,
                    field.channelAddress(), physicalRecordOffset,
                    "VLSD data link is " + header.type() + ", expected SD/DT/CG"));
            return new ResolvedPayload(new byte[0], diags);
        }

        long dataStart = header.dataSectionOffset();
        long dataEnd;
        try {
            dataEnd = MdfSafeMath.addExact(dataStart, header.declaredDataLength(), "vlsdDataEnd");
        } catch (ArithmeticException e) {
            diags.add(diag(MdfDecodeDiagnosticCode.VLSD_BOUNDARY_VIOLATION, dataGroupAddress, channelGroupAddress,
                    field.channelAddress(), physicalRecordOffset, "VLSD data region overflow"));
            return new ResolvedPayload(new byte[0], diags);
        }
        dataEnd = Math.min(dataEnd, access.fileSize());

        long entryOffset;
        try {
            entryOffset = MdfSafeMath.addExact(dataStart, vlsdOffset, "vlsdEntry");
        } catch (ArithmeticException e) {
            diags.add(diag(MdfDecodeDiagnosticCode.VLSD_INVALID_REFERENCE, dataGroupAddress, channelGroupAddress,
                    field.channelAddress(), physicalRecordOffset, "VLSD offset overflow"));
            return new ResolvedPayload(new byte[0], diags);
        }

        if (entryOffset + 4L > dataEnd) {
            diags.add(diag(MdfDecodeDiagnosticCode.VLSD_BOUNDARY_VIOLATION, dataGroupAddress, channelGroupAddress,
                    field.channelAddress(), physicalRecordOffset,
                    "VLSD length header outside SD bounds offset=" + vlsdOffset));
            return new ResolvedPayload(new byte[0], diags);
        }

        byte[] lenRaw = access.readFully(entryOffset, 4, "vlsd-len@" + entryOffset);
        long payloadLen = Byte.toUnsignedLong(lenRaw[0])
                | (Byte.toUnsignedLong(lenRaw[1]) << 8)
                | (Byte.toUnsignedLong(lenRaw[2]) << 16)
                | (Byte.toUnsignedLong(lenRaw[3]) << 24);

        if (payloadLen > MAX_VLSD_PAYLOAD_BYTES) {
            diags.add(diag(MdfDecodeDiagnosticCode.VLSD_BOUNDARY_VIOLATION, dataGroupAddress, channelGroupAddress,
                    field.channelAddress(), physicalRecordOffset,
                    "VLSD payload length " + payloadLen + " exceeds max " + MAX_VLSD_PAYLOAD_BYTES));
            return new ResolvedPayload(new byte[0], diags);
        }
        if (payloadLen > CanonicalCanFrame.MAX_PAYLOAD_BYTES
                && field.canRoleCandidate() != null
                && field.canRoleCandidate().role().name().equals("PAYLOAD")) {
            // Still allow up to MAX_VLSD; CAN mapper will truncate/validate separately if needed.
        }

        long payloadStart = entryOffset + 4L;
        long payloadEnd;
        try {
            payloadEnd = MdfSafeMath.addExact(payloadStart, payloadLen, "vlsdPayloadEnd");
        } catch (ArithmeticException e) {
            diags.add(diag(MdfDecodeDiagnosticCode.VLSD_BOUNDARY_VIOLATION, dataGroupAddress, channelGroupAddress,
                    field.channelAddress(), physicalRecordOffset, "VLSD payload end overflow"));
            return new ResolvedPayload(new byte[0], diags);
        }
        if (payloadEnd > dataEnd) {
            diags.add(diag(MdfDecodeDiagnosticCode.VLSD_TRUNCATED, dataGroupAddress, channelGroupAddress,
                    field.channelAddress(), physicalRecordOffset,
                    "Truncated VLSD payload need=" + payloadLen + " available=" + (dataEnd - payloadStart)));
            return new ResolvedPayload(new byte[0], diags);
        }

        byte[] payload = payloadLen == 0L
                ? new byte[0]
                : access.readFully(payloadStart, (int) payloadLen, "vlsd-payload@" + payloadStart);
        return new ResolvedPayload(payload, diags);
    }

    private static ResolvedPayload resolveFromVlsdChannelGroup(
            MdfFileAccess access,
            MdfChannelFieldSchema field,
            long vlsdOffset,
            Long dataGroupAddress,
            Long channelGroupAddress,
            long physicalRecordOffset,
            MdfVlsdEnrichmentContext context
    ) throws ParserException {
        List<MdfDecodeDiagnostic> diags = new ArrayList<>();
        if (context == null) {
            diags.add(diag(MdfDecodeDiagnosticCode.VLSD_INVALID_REFERENCE, dataGroupAddress, channelGroupAddress,
                    field.channelAddress(), physicalRecordOffset,
                    "VLSD cn_data links to CG @" + field.dataLink()
                            + "; enrichment context with DG schemas is required"));
            return new ResolvedPayload(new byte[0], diags);
        }
        Optional<MdfRecordSchema> vlsdSchema = context.schemaForChannelGroup(field.dataLink());
        if (vlsdSchema.isEmpty() || !vlsdSchema.get().vlsdChannelGroup()) {
            diags.add(diag(MdfDecodeDiagnosticCode.VLSD_INVALID_REFERENCE, dataGroupAddress, channelGroupAddress,
                    field.channelAddress(), physicalRecordOffset,
                    "VLSD cn_data CG @" + field.dataLink() + " is not a VLSD channel group in the DG schema"));
            return new ResolvedPayload(new byte[0], diags);
        }
        MdfVlsdCgIndex index = context.indexForVlsdCg(access, vlsdSchema.get());
        Optional<MdfVlsdCgIndex.ResolvedEntry> entry = index.lookup(vlsdOffset);
        if (entry.isEmpty()) {
            diags.add(diag(MdfDecodeDiagnosticCode.VLSD_INVALID_REFERENCE, dataGroupAddress, channelGroupAddress,
                    field.channelAddress(), physicalRecordOffset,
                    "VLSD CG offset " + vlsdOffset + " does not match an entry start in CG @"
                            + field.dataLink()));
            return new ResolvedPayload(new byte[0], diags);
        }
        MdfVlsdCgIndex.ResolvedEntry resolved = entry.get();
        int payloadLen = resolved.payloadLength();
        if (payloadLen > MAX_VLSD_PAYLOAD_BYTES) {
            diags.add(diag(MdfDecodeDiagnosticCode.VLSD_BOUNDARY_VIOLATION, dataGroupAddress, channelGroupAddress,
                    field.channelAddress(), physicalRecordOffset,
                    "VLSD payload length " + payloadLen + " exceeds max " + MAX_VLSD_PAYLOAD_BYTES));
            return new ResolvedPayload(new byte[0], diags);
        }
        if (payloadLen == 0) {
            return new ResolvedPayload(new byte[0], diags);
        }
        long payloadStart = resolved.lengthFieldOffset() + 4L;
        long payloadEnd = payloadStart + payloadLen;
        if (payloadEnd > resolved.segment().endExclusive()) {
            diags.add(diag(MdfDecodeDiagnosticCode.VLSD_TRUNCATED, dataGroupAddress, channelGroupAddress,
                    field.channelAddress(), physicalRecordOffset,
                    "Truncated VLSD CG payload need=" + payloadLen));
            return new ResolvedPayload(new byte[0], diags);
        }
        byte[] payload = context.readPayload(access, resolved.segment(), payloadStart, payloadLen);
        return new ResolvedPayload(payload, diags);
    }

    /**
     * Replaces VLSD offset fields with resolved byte payloads when possible.
     * Prefer {@link #enrich(MdfFileAccess, MdfDecodedRecord, MdfVlsdEnrichmentContext)} for CG-linked VLSD.
     */
    public static MdfDecodedRecord enrich(
            MdfFileAccess access,
            MdfDecodedRecord record
    ) throws ParserException {
        return enrich(access, record, null);
    }

    /**
     * Replaces VLSD offset fields with resolved byte payloads when possible.
     * <p>
     * When {@code cn_data} is present, in-record values (including BYTE_ARRAY / MIME samples that
     * store the offset) are always treated as VLSD offsets — never as the final payload bytes.
     */
    public static MdfDecodedRecord enrich(
            MdfFileAccess access,
            MdfDecodedRecord record,
            MdfVlsdEnrichmentContext context
    ) throws ParserException {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(record, "record");
        boolean anyResolvable = false;
        for (MdfDecodedField field : record.fields()) {
            if (isVlsdChannel(field.schema()) && field.schema().dataLink() != 0L) {
                anyResolvable = true;
                break;
            }
        }
        if (!anyResolvable) {
            return record;
        }
        List<MdfDecodedField> fields = new ArrayList<>(record.fields().size());
        List<MdfDecodeDiagnostic> extra = new ArrayList<>();
        boolean changed = false;

        for (MdfDecodedField field : record.fields()) {
            if (!isVlsdChannel(field.schema())) {
                fields.add(field);
                continue;
            }
            // VLSD without cn_data: keep the in-record extracted value (common for fixed bus-event
            // payloads that still declare cn_type=VLSD, or incomplete composition metadata).
            if (field.schema().dataLink() == 0L) {
                fields.add(field);
                continue;
            }
            // cn_data present: in-record geometry holds the offset (UINT or BYTE_ARRAY LE), not payload.
            changed = true;
            long offset = extractOffset(field.value());
            if (offset < 0L) {
                extra.add(diag(MdfDecodeDiagnosticCode.VLSD_INVALID_REFERENCE,
                        record.schema().dataGroupAddress(),
                        record.schema().channelGroupAddress(),
                        field.schema().channelAddress(),
                        record.physicalOffset(),
                        "VLSD offset not an integer for '" + field.schema().name() + "'"));
                fields.add(new MdfDecodedField(
                        field.schema(),
                        new MdfBitFieldExtractor.ExtractedValue.Unsupported("invalid VLSD offset"),
                        field.invalid(),
                        "invalid VLSD offset"
                ));
                continue;
            }
            ResolvedPayload resolved = resolve(
                    access,
                    field.schema(),
                    offset,
                    record.schema().dataGroupAddress(),
                    record.schema().channelGroupAddress(),
                    record.physicalOffset(),
                    context
            );
            extra.addAll(resolved.diagnostics());
            if (!resolved.diagnostics().isEmpty()) {
                fields.add(new MdfDecodedField(
                        field.schema(),
                        new MdfBitFieldExtractor.ExtractedValue.Unsupported(
                                resolved.diagnostics().getFirst().reason()),
                        field.invalid(),
                        resolved.diagnostics().getFirst().reason()
                ));
            } else {
                fields.add(new MdfDecodedField(
                        field.schema(),
                        new MdfBitFieldExtractor.ExtractedValue.BytesValue(resolved.bytes()),
                        field.invalid(),
                        field.diagnostic()
                ));
            }
        }

        if (!changed && extra.isEmpty()) {
            return record;
        }
        List<MdfDecodeDiagnostic> all = new ArrayList<>(record.diagnostics());
        all.addAll(extra);
        return new MdfDecodedRecord(
                record.schema(),
                record.physicalOffset(),
                record.recordId(),
                fields,
                all
        );
    }

    private static long extractOffset(MdfBitFieldExtractor.ExtractedValue value) {
        if (value instanceof MdfBitFieldExtractor.ExtractedValue.IntegerValue i) {
            return i.rawUnsigned();
        }
        if (value instanceof MdfBitFieldExtractor.ExtractedValue.BytesValue b && b.bytes().length > 0) {
            long v = 0L;
            int n = Math.min(8, b.bytes().length);
            for (int i = 0; i < n; i++) {
                v |= (Byte.toUnsignedLong(b.bytes()[i]) << (8 * i));
            }
            return v;
        }
        return -1L;
    }

    private static MdfDecodeDiagnostic diag(
            MdfDecodeDiagnosticCode code,
            Long dg,
            Long cg,
            Long cn,
            long offset,
            String message
    ) {
        return new MdfDecodeDiagnostic(code, MdfIssueSeverity.WARNING, message, dg, cg, cn, offset);
    }
}
