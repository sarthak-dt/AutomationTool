package com.stellar.platform.parser.mf4.recovery;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.parser.mf4.analysis.MdfIssueSeverity;
import com.stellar.platform.parser.mf4.block.MdfBlockType;
import com.stellar.platform.parser.mf4.decode.MdfCanExtractionPlan;
import com.stellar.platform.parser.mf4.decode.MdfCanFieldResolver;
import com.stellar.platform.parser.mf4.decode.MdfCanonicalCanFrameFactory;
import com.stellar.platform.parser.mf4.decode.MdfDecodeDiagnostic;
import com.stellar.platform.parser.mf4.decode.MdfDecodedField;
import com.stellar.platform.parser.mf4.decode.MdfDecodedRecord;
import com.stellar.platform.parser.mf4.decode.MdfRecordDecodePlan;
import com.stellar.platform.parser.mf4.decode.MdfSegmentCursor;
import com.stellar.platform.parser.mf4.decode.MdfDtBoundaryRequest;
import com.stellar.platform.parser.mf4.decode.MdfDtBoundaryResolution;
import com.stellar.platform.parser.mf4.decode.MdfDzDecompressor;
import com.stellar.platform.parser.mf4.decode.MdfNativeMeasurementResolver;
import com.stellar.platform.parser.mf4.decode.MdfPayloadSegment;
import com.stellar.platform.parser.mf4.decode.MdfRecordFieldDecoder;
import com.stellar.platform.parser.mf4.decode.MdfSchemaDispatch;
import com.stellar.platform.parser.mf4.decode.MdfStreamingRecordReader;
import com.stellar.platform.parser.mf4.decode.MdfVlsdEnrichmentContext;
import com.stellar.platform.parser.mf4.decode.MdfVlsdResolver;
import com.stellar.platform.parser.mf4.id.MdfIdentification;
import com.stellar.platform.parser.mf4.id.MdfIdentificationReader;
import com.stellar.platform.parser.mf4.dynamicschema.MdfCanRole;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDataSourceNode;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDataSourceSchema;
import com.stellar.platform.parser.mf4.dynamicschema.MdfNativeMeasurementSchema;
import com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema;
import com.stellar.platform.parser.mf4.dynamicschema.MdfSchemaStatus;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.io.MdfSafeMath;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.ParserException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Controlled recovery for unfinalized / inconsistent MF4 data groups.
 * <p>
 * Recovery owns physical boundaries, record ownership, and generic
 * {@link MdfDecodedRecord} production. Schema-specific CAN / native conversion
 * is performed by the caller via {@link RecoveredRecordConsumer}.
 */
public final class MdfRecoveryEngine {

    private final MdfRecoveryLimits limits;

    public MdfRecoveryEngine() {
        this(MdfRecoveryLimits.DEFAULT);
    }

    public MdfRecoveryEngine(MdfRecoveryLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public static boolean requiresRecovery(MdfRecordSchema schema) {
        if (schema == null || schema.status() == MdfSchemaStatus.UNUSABLE) {
            return false;
        }
        if (schema.vlsdChannelGroup()) {
            return false;
        }
        if (schema.status() == MdfSchemaStatus.RECOVERY_CANDIDATE && schema.declaredCycleCount() == 0L
                && schema.physicalDataExists() && schema.completeRecordLength() > 0L) {
            return true;
        }
        return schema.declaredCycleCount() == 0L
                && schema.physicalDataExists()
                && schema.completeRecordLength() > 0L
                && schema.usable();
    }

    public static boolean requiresRecovery(List<MdfRecordSchema> schemasForDg) {
        return schemasForDg != null && schemasForDg.stream().anyMatch(MdfRecoveryEngine::requiresRecovery);
    }

    /**
     * Legacy CAN-oriented entry: recovers records and converts CAN schemas to frames.
     * Prefer {@link #recoverDataGroup(MdfFileAccess, long, List, CancellationToken,
     * RecoveredRecordConsumer, Consumer)} for production decode.
     */
    public MdfRecoveryResult recoverDataGroup(
            MdfFileAccess access,
            long dataGroupAddress,
            List<MdfRecordSchema> schemasForDg,
            CancellationToken cancellation,
            long[] sequence,
            Consumer<CanonicalCanFrame> frameSink,
            Consumer<MdfRecoveryDiagnostic> diagnosticSink
    ) throws ParserException {
        Objects.requireNonNull(sequence, "sequence");
        Consumer<CanonicalCanFrame> frames = frameSink == null ? f -> {
        } : frameSink;
        List<CanonicalCanFrame> collected = new ArrayList<>();
        MdfRecoveryResult result = recoverDataGroup(
                access,
                dataGroupAddress,
                schemasForDg,
                cancellation,
                validated -> {
                    MdfDecodedRecord decoded = validated.decoded();
                    if (MdfSchemaDispatch.classify(decoded.schema()) != MdfSchemaDispatch.Kind.CAN_FRAME) {
                        return false;
                    }
                    MdfCanFieldResolver.ResolvedCanFields resolved = validated.canFields() != null
                            ? validated.canFields()
                            : MdfCanFieldResolver.resolve(decoded);
                    if (!resolved.complete() || !structurallySoundCan(decoded, resolved)) {
                        return false;
                    }
                    CanonicalCanFrame frame = MdfCanonicalCanFrameFactory.create(
                            sequence[0], resolved, decoded.physicalOffset(), FrameQuality.RECOVERED
                    );
                    sequence[0]++;
                    collected.add(frame);
                    frames.accept(frame);
                    return true;
                },
                diagnosticSink
        );
        return new MdfRecoveryResult(
                result.status(),
                collected,
                result.recordsRecovered(),
                result.recordsInspected(),
                result.recordsRejected(),
                result.bytesInspected(),
                result.effectiveDataStart(),
                result.effectiveDataEnd(),
                result.reasons(),
                result.diagnostics(),
                result.completedNormally()
        );
    }

    /**
     * Recovers physical records and delivers generic {@link MdfDecodedRecord} values.
     * Caller performs schema-specific CAN / native dispatch.
     */
    public MdfRecoveryResult recoverDataGroup(
            MdfFileAccess access,
            long dataGroupAddress,
            List<MdfRecordSchema> schemasForDg,
            CancellationToken cancellation,
            RecoveredRecordConsumer recordConsumer,
            Consumer<MdfRecoveryDiagnostic> diagnosticSink
    ) throws ParserException {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(schemasForDg, "schemasForDg");
        Objects.requireNonNull(recordConsumer, "recordConsumer");
        CancellationToken token = cancellation == null ? CancellationToken.NONE : cancellation;
        Consumer<MdfRecoveryDiagnostic> diags = diagnosticSink == null ? d -> {
        } : diagnosticSink;

        List<MdfRecordSchema> recoverable = schemasForDg.stream()
                .filter(MdfRecoveryEngine::requiresRecovery)
                .toList();
        if (recoverable.isEmpty()) {
            return MdfRecoveryResult.notRequired();
        }

        List<MdfRecoveryDiagnostic> diagnostics = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                MdfRecoveryDiagnosticCode.RECOVERY_REQUIRED,
                MdfIssueSeverity.RECOVERABLE,
                "Recovery required for DG (not silent zero)",
                dataGroupAddress,
                null,
                null,
                0L
        ));
        accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                MdfRecoveryDiagnosticCode.RECOVERY_STARTED,
                MdfIssueSeverity.INFO,
                "Recovery started for DG @" + dataGroupAddress,
                dataGroupAddress,
                null,
                null,
                0L
        ));

        for (MdfRecordSchema schema : recoverable) {
            if (schema.declaredCycleCount() == 0L && schema.physicalDataExists()) {
                reasons.add("ZERO_DECLARED_CYCLES_WITH_DATA");
                accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                        MdfRecoveryDiagnosticCode.ZERO_DECLARED_CYCLES_WITH_DATA,
                        MdfIssueSeverity.RECOVERABLE,
                        "declaredCycleCount=0 with physical data; schema usable",
                        schema.dataGroupAddress(),
                        schema.channelGroupAddress(),
                        schema.recordId(),
                        schema.dataSource().dataBlockRootAddress()
                ));
            }
        }

        MdfDataSourceSchema dataSource = recoverable.getFirst().dataSource();
        // DZ is supported via per-block decompression; only fail early when no DT/SD/DZ leaf exists.
        boolean hasPayloadLeaf = dataSource.orderedNodes().stream().anyMatch(n ->
                n.blockType() == MdfBlockType.DT
                        || n.blockType() == MdfBlockType.SD
                        || n.blockType() == MdfBlockType.DZ);
        if (!hasPayloadLeaf) {
            accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                    MdfRecoveryDiagnosticCode.UNSUPPORTED_RECOVERY_SOURCE,
                    MdfIssueSeverity.WARNING,
                    "No DT/SD/DZ payload leaf for recovery",
                    dataGroupAddress,
                    null,
                    null,
                    dataSource.dataBlockRootAddress()
            ));
            return new MdfRecoveryResult(
                    MdfRecoveryStatus.NOT_RECOVERABLE,
                    List.of(),
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    reasons,
                    diagnostics,
                    false
            );
        }

        int recordIdSize = recoverable.getFirst().recordIdSize();
        for (MdfRecordSchema schema : recoverable) {
            if (schema.recordIdSize() != recordIdSize) {
                accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                        MdfRecoveryDiagnosticCode.RESYNC_FAILED,
                        MdfIssueSeverity.FATAL,
                        "Inconsistent recordIdSize within recovery DG",
                        dataGroupAddress,
                        schema.channelGroupAddress(),
                        null,
                        0L
                ));
                return new MdfRecoveryResult(
                        MdfRecoveryStatus.FATAL,
                        List.of(),
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        reasons,
                        diagnostics,
                        false
                );
            }
        }

        // All usable schemas for this DG participate in record-ID lookup (same layout family).
        Map<Long, MdfRecordSchema> byRecordId = new HashMap<>();
        for (MdfRecordSchema schema : schemasForDg) {
            if (schema.usable() && schema.completeRecordLength() > 0L) {
                byRecordId.put(schema.recordId(), schema);
            }
        }
        MdfRecordSchema soleSchema = recordIdSize == 0 && byRecordId.size() == 1
                ? byRecordId.values().iterator().next()
                : (recordIdSize == 0 && recoverable.size() == 1 ? recoverable.getFirst() : null);

        long minRecordLen = Long.MAX_VALUE;
        long maxRecordLen = 0L;
        for (MdfRecordSchema schema : byRecordId.values()) {
            minRecordLen = Math.min(minRecordLen, schema.completeRecordLength());
            maxRecordLen = Math.max(maxRecordLen, schema.completeRecordLength());
        }
        if (minRecordLen == Long.MAX_VALUE) {
            minRecordLen = recoverable.getFirst().completeRecordLength();
            maxRecordLen = minRecordLen;
        }

        List<MdfPayloadSegment> segments = resolveRecoverySegments(
                access, dataSource, cancellation, diagnostics, diags);
        MdfVlsdEnrichmentContext vlsdContext = MdfVlsdEnrichmentContext.of(schemasForDg, segments);
        if (segments.isEmpty()) {
            accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                    MdfRecoveryDiagnosticCode.NO_VALID_RECORDS_IN_REGION,
                    MdfIssueSeverity.WARNING,
                    "No recoverable payload segments for DG",
                    dataGroupAddress,
                    null,
                    null,
                    dataSource.dataBlockRootAddress()
            ));
            return new MdfRecoveryResult(
                    MdfRecoveryStatus.NOT_RECOVERABLE,
                    List.of(),
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    reasons,
                    diagnostics,
                    false
            );
        }

        long effectiveStart = segments.getFirst().offset();
        long effectiveEnd = segments.getLast().endExclusive();
        long recordsInspected = 0L;
        long recordsRejected = 0L;
        long bytesInspected = 0L;
        long recoveredCount = 0L;
        boolean truncated = false;
        boolean stoppedUnsafe = false;
        boolean cancelled = false;
        Map<MdfRecordSchema, MdfRecordDecodePlan> plans = new IdentityHashMap<>();
        Set<Long> vlsdSkipReported = new HashSet<>();

        try {
        for (MdfPayloadSegment segment : segments) {
            if (token.isCancellationRequested()) {
                cancelled = true;
                accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                        MdfRecoveryDiagnosticCode.CANCELLED,
                        MdfIssueSeverity.INFO,
                        "Cancellation during recovery",
                        dataGroupAddress,
                        null,
                        null,
                        segment.offset()
                ));
                break;
            }

            long cursor = segment.offset();
            long end = segment.endExclusive();
            effectiveStart = Math.min(effectiveStart, cursor);
            effectiveEnd = Math.max(effectiveEnd, end);
            int consecutiveInvalid = 0;
            int resyncAttempts = 0;
            try (MdfSegmentCursor segCursor = new MdfSegmentCursor(access, segment)) {

            while (cursor < end) {
                if (token.isCancellationRequested()) {
                    cancelled = true;
                    break;
                }
                long remaining = end - cursor;
                if (remaining < Math.min(minRecordLen, recordIdSize == 0 ? minRecordLen : recordIdSize)) {
                    if (remaining > 0) {
                        truncated = true;
                        accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                                MdfRecoveryDiagnosticCode.TRUNCATED_FINAL_RECORD,
                                MdfIssueSeverity.RECOVERABLE,
                                "Truncated final record region remaining=" + remaining,
                                dataGroupAddress,
                                null,
                                null,
                                cursor
                        ));
                    }
                    bytesInspected += remaining;
                    break;
                }

                long recordId;
                MdfRecordSchema schema;
                if (recordIdSize == 0) {
                    if (soleSchema == null) {
                        stoppedUnsafe = true;
                        accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                                MdfRecoveryDiagnosticCode.RESYNC_FAILED,
                                MdfIssueSeverity.FATAL,
                                "recordIdSize=0 with multiple schemas; unsafe to recover",
                                dataGroupAddress,
                                null,
                                null,
                                cursor
                        ));
                        break;
                    }
                    recordId = soleSchema.recordId();
                    schema = soleSchema;
                } else {
                    recordId = segCursor.readLittleEndianUnsigned(cursor, recordIdSize, "recoverId");
                    schema = byRecordId.get(recordId);
                    if (schema == null) {
                        recordsInspected++;
                        recordsRejected++;
                        acceptAggregatedUnknown(diags, diagnostics, dataGroupAddress, recordId, cursor);
                        // Strong-evidence resync only: try exact known record lengths from this offset
                        // (never byte-scan for plausible CAN IDs).
                        OptionalLong next = tryResyncOnKnownLengths(
                                access, cursor, end, recordIdSize, byRecordId, resyncAttempts
                        );
                        if (next.isPresent()) {
                            resyncAttempts++;
                            bytesInspected += next.getAsLong() - cursor;
                            cursor = next.getAsLong();
                            continue;
                        }
                        stoppedUnsafe = true;
                        accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                                MdfRecoveryDiagnosticCode.RESYNC_FAILED,
                                MdfIssueSeverity.WARNING,
                                "Unknown record ID " + recordId + "; stopping recovery for data source",
                                dataGroupAddress,
                                null,
                                recordId,
                                cursor
                        ));
                        break;
                    }
                }

                if (schema.vlsdChannelGroup()) {
                    OptionalLong skipped = skipVlsdRecord(segCursor, cursor, end, recordIdSize);
                    if (!skipped.isPresent()) {
                        truncated = true;
                        accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                                MdfRecoveryDiagnosticCode.TRUNCATED_FINAL_RECORD,
                                MdfIssueSeverity.RECOVERABLE,
                                "Truncated VLSD record",
                                dataGroupAddress,
                                schema.channelGroupAddress(),
                                recordId,
                                cursor
                        ));
                        break;
                    }
                    long total = skipped.getAsLong();
                    recordsInspected++;
                    bytesInspected += total;
                    if (vlsdSkipReported.add(schema.channelGroupAddress())) {
                        acceptAggregatedVlsd(
                                diags, diagnostics, dataGroupAddress, schema.channelGroupAddress(), recordId, cursor);
                    }
                    cursor = MdfSafeMath.addExact(cursor, total, "vlsdCursor");
                    consecutiveInvalid = 0;
                    continue;
                }

                if (!requiresRecovery(schema)) {
                    // Sibling CGs owned by the normal decoder (CAN or native): advance only.
                    long completeLen = schema.completeRecordLength();
                    if (completeLen <= 0 || remaining < completeLen) {
                        truncated = true;
                        break;
                    }
                    recordsInspected++;
                    bytesInspected += completeLen;
                    cursor = MdfSafeMath.addExact(cursor, completeLen, "normalOwnedCursor");
                    consecutiveInvalid = 0;
                    continue;
                }

                MdfSchemaDispatch.Kind kind = MdfSchemaDispatch.classify(schema);
                if (kind == MdfSchemaDispatch.Kind.SKIP_NON_MEASUREMENT) {
                    long completeLen = schema.completeRecordLength();
                    if (completeLen <= 0 || remaining < completeLen) {
                        truncated = true;
                        break;
                    }
                    recordsInspected++;
                    bytesInspected += completeLen;
                    accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                            MdfRecoveryDiagnosticCode.NON_CAN_GROUP_SKIPPED,
                            MdfIssueSeverity.INFO,
                            "Skipped non-measurement Channel Group busKind=" + schema.busKind()
                                    + " during recovery (LIN/error/unsupported — not a recovery failure)",
                            dataGroupAddress,
                            schema.channelGroupAddress(),
                            recordId,
                            cursor
                    ));
                    cursor = MdfSafeMath.addExact(cursor, completeLen, "nonMeasurementCursor");
                    consecutiveInvalid = 0;
                    continue;
                }

                long completeLen = schema.completeRecordLength();
                if (completeLen <= 0 || completeLen > Integer.MAX_VALUE) {
                    stoppedUnsafe = true;
                    break;
                }
                if (remaining < completeLen) {
                    truncated = true;
                    accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                            MdfRecoveryDiagnosticCode.TRUNCATED_FINAL_RECORD,
                            MdfIssueSeverity.RECOVERABLE,
                            "Incomplete final record need=" + completeLen + " remaining=" + remaining,
                            dataGroupAddress,
                            schema.channelGroupAddress(),
                            recordId,
                            cursor
                    ));
                    bytesInspected += remaining;
                    break;
                }

                MdfRecordDecodePlan plan = plans.computeIfAbsent(schema, MdfRecordDecodePlan::compile);
                MdfSegmentCursor.RecordView view = segCursor.view(cursor, (int) completeLen, "recover");
                recordsInspected++;
                bytesInspected += completeLen;

                MdfDecodedRecord decoded = MdfRecordFieldDecoder.decode(
                        plan, view.buffer(), view.offset(), view.length(), cursor, recordId);
                if (plan.hasResolvableVlsd()) {
                    decoded = MdfVlsdResolver.enrich(access, decoded, vlsdContext);
                }

                RecoveryValidatedRecord validated = validateRecovered(decoded, plan);
                if (!validated.sound()) {
                    recordsRejected++;
                    consecutiveInvalid++;
                    if (recordsRejected == 1L) {
                        accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                                MdfRecoveryDiagnosticCode.INVALID_RECOVERY_CANDIDATE,
                                MdfIssueSeverity.WARNING,
                                "Candidate at " + cursor + " failed schema-appropriate structural validation",
                                dataGroupAddress,
                                schema.channelGroupAddress(),
                                recordId,
                                cursor
                        ));
                    }
                    if (recoveredCount > 0 && recordIdSize == 0) {
                        stoppedUnsafe = true;
                        break;
                    }
                    if (consecutiveInvalid >= limits.maxConsecutiveInvalidRecords()) {
                        stoppedUnsafe = true;
                        accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                                MdfRecoveryDiagnosticCode.RESYNC_FAILED,
                                MdfIssueSeverity.WARNING,
                                "Too many consecutive invalid recovery candidates",
                                dataGroupAddress,
                                schema.channelGroupAddress(),
                                recordId,
                                cursor
                        ));
                        break;
                    }
                    cursor = MdfSafeMath.addExact(cursor, completeLen, "recoverCursor");
                    continue;
                }

                boolean accepted = recordConsumer.onRecord(validated);
                if (!accepted) {
                    recordsRejected++;
                    consecutiveInvalid++;
                    if (recordsRejected == 1L) {
                        accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                                MdfRecoveryDiagnosticCode.INVALID_RECOVERY_CANDIDATE,
                                MdfIssueSeverity.WARNING,
                                "Recovered record at " + cursor + " was not accepted by dispatch",
                                dataGroupAddress,
                                schema.channelGroupAddress(),
                                recordId,
                                cursor
                        ));
                    }
                    if (recoveredCount > 0 && recordIdSize == 0) {
                        stoppedUnsafe = true;
                        break;
                    }
                    if (consecutiveInvalid >= limits.maxConsecutiveInvalidRecords()) {
                        stoppedUnsafe = true;
                        accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                                MdfRecoveryDiagnosticCode.RESYNC_FAILED,
                                MdfIssueSeverity.WARNING,
                                "Too many consecutive invalid recovery candidates",
                                dataGroupAddress,
                                schema.channelGroupAddress(),
                                recordId,
                                cursor
                        ));
                        break;
                    }
                    cursor = MdfSafeMath.addExact(cursor, completeLen, "recoverCursor");
                    continue;
                }

                consecutiveInvalid = 0;
                recoveredCount++;
                cursor = MdfSafeMath.addExact(cursor, completeLen, "recoverCursor");
            }
            }
            if (cancelled || stoppedUnsafe) {
                break;
            }
        }
        } finally {
            vlsdContext.close();
            for (MdfPayloadSegment segment : segments) {
                segment.discardSpill();
            }
        }

        // Aggregate recovered-record summary (avoid one warning per record)
        if (recoveredCount > 0) {
            accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                    MdfRecoveryDiagnosticCode.RECOVERED_RECORD,
                    MdfIssueSeverity.INFO,
                    "Recovered " + recoveredCount + " record(s) for DG @" + dataGroupAddress,
                    dataGroupAddress,
                    null,
                    null,
                    effectiveStart
            ));
        }

        MdfRecoveryStatus status;
        boolean completedNormally;
        if (cancelled && recoveredCount == 0) {
            status = MdfRecoveryStatus.NOT_RECOVERABLE;
            completedNormally = false;
        } else if (recoveredCount == 0) {
            status = MdfRecoveryStatus.NOT_RECOVERABLE;
            completedNormally = false;
            accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                    MdfRecoveryDiagnosticCode.NO_VALID_RECORDS_IN_REGION,
                    MdfIssueSeverity.INFO,
                    "Physical region present but no structurally valid CAN or native measurement "
                            + "records recovered for this DG",
                    dataGroupAddress,
                    null,
                    null,
                    effectiveStart
            ));
        } else if (stoppedUnsafe || recordsRejected > 0 || cancelled
                || (truncated && recoveredCount == 0)) {
            status = MdfRecoveryStatus.PARTIALLY_RECOVERED;
            completedNormally = false;
            accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                    MdfRecoveryDiagnosticCode.PARTIAL_RECOVERY,
                    MdfIssueSeverity.RECOVERABLE,
                    "Partial recovery: recovered=" + recoveredCount
                            + " rejected=" + recordsRejected
                            + (truncated ? " truncated" : "")
                            + (stoppedUnsafe ? " alignment-stopped" : ""),
                    dataGroupAddress,
                    null,
                    null,
                    effectiveEnd
            ));
        } else {
            // Successful recovery (CAN and/or native). Trailing truncated bytes and intentionally
            // skipped non-measurement / VLSD groups do not by themselves imply PARTIALLY_RECOVERED.
            status = MdfRecoveryStatus.RECOVERED;
            completedNormally = true;
            if (truncated) {
                accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                        MdfRecoveryDiagnosticCode.TRUNCATED_FINAL_RECORD,
                        MdfIssueSeverity.INFO,
                        "Trailing truncated bytes after recovering " + recoveredCount
                                + " record(s); recovery treated as complete",
                        dataGroupAddress,
                        null,
                        null,
                        effectiveEnd
                ));
            }
            accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                    MdfRecoveryDiagnosticCode.RECOVERY_COMPLETED,
                    MdfIssueSeverity.INFO,
                    "Recovery completed for DG @" + dataGroupAddress,
                    dataGroupAddress,
                    null,
                    null,
                    effectiveEnd
            ));
        }

        return new MdfRecoveryResult(
                status,
                List.of(),
                recoveredCount,
                recordsInspected,
                recordsRejected,
                bytesInspected,
                effectiveStart,
                effectiveEnd,
                reasons,
                diagnostics,
                completedNormally
        );
    }

    private List<MdfPayloadSegment> resolveRecoverySegments(
            MdfFileAccess access,
            MdfDataSourceSchema dataSource,
            CancellationToken cancellation,
            List<MdfRecoveryDiagnostic> diagnostics,
            Consumer<MdfRecoveryDiagnostic> diags
    ) throws ParserException {
        List<MdfPayloadSegment> segments = new ArrayList<>();
        List<MdfDecodeDiagnostic> decodeDiags = new ArrayList<>();
        MdfIdentification identification = MdfIdentificationReader.read(access);
        boolean allowEofFallback = identification.hasUnfinalizedFlags();
        MdfDtBoundaryRequest boundaryRequest =
                MdfDtBoundaryRequest.forRecovery(allowEofFallback, cancellation);

        for (MdfDataSourceNode node : dataSource.orderedNodes()) {
            if (node.blockType() == MdfBlockType.DZ) {
                MdfDzDecompressor.DzDecodeResult dz = MdfDzDecompressor.decompress(
                        access, node, dataSource.dataGroupAddress()
                );
                if (!dz.success()) {
                    accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                            MdfRecoveryDiagnosticCode.UNSUPPORTED_RECOVERY_SOURCE,
                            MdfIssueSeverity.WARNING,
                            "DZ decompression failed during recovery: " + dz.diagnostic().reason(),
                            dataSource.dataGroupAddress(),
                            null,
                            null,
                            node.address()
                    ));
                    continue;
                }
                segments.add(dz.toSegment(node.address()));
                continue;
            }
            if (node.blockType() == MdfBlockType.DL || node.blockType() == MdfBlockType.HL) {
                continue;
            }
            if (node.blockType() != MdfBlockType.DT && node.blockType() != MdfBlockType.SD) {
                continue;
            }
            Optional<MdfPayloadSegment> declared =
                    MdfStreamingRecordReader.dtSegment(access, node, false);
            MdfDtBoundaryResolution[] resolutionHolder = new MdfDtBoundaryResolution[1];
            Optional<MdfPayloadSegment> extended = MdfStreamingRecordReader.dtSegment(
                    access,
                    node,
                    true,
                    boundaryRequest,
                    r -> resolutionHolder[0] = r
            );
            MdfDtBoundaryResolution resolution = resolutionHolder[0];
            if (resolution != null && resolution.declaredLengthUnusable()) {
                accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                        MdfRecoveryDiagnosticCode.UNFINALIZED_DT_LENGTH_UNUSABLE,
                        MdfIssueSeverity.WARNING,
                        "Declared DT payload length unusable; recovering physical region from block end",
                        dataSource.dataGroupAddress(),
                        null,
                        null,
                        node.address()
                ));
            }
            if (resolution != null && resolution.falseMarkerCandidates() > 0L) {
                accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                        MdfRecoveryDiagnosticCode.FALSE_MDF_BLOCK_MARKER_IGNORED,
                        MdfIssueSeverity.INFO,
                        "FALSE_MDF_BLOCK_MARKER_IGNORED count=" + resolution.falseMarkerCandidates()
                                + " (raw ## rejected by structural MDF validation)",
                        dataSource.dataGroupAddress(),
                        null,
                        null,
                        node.address()
                ));
            }
            if (extended.isPresent()) {
                MdfPayloadSegment ext = extended.get();
                if (declared.isEmpty() || ext.endExclusive() > declared.get().endExclusive()
                        || ext.offset() != declared.get().offset()) {
                    accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                            MdfRecoveryDiagnosticCode.EFFECTIVE_BOUNDARY_EXTENDED,
                            MdfIssueSeverity.INFO,
                            "Effective recovery boundary extended to [" + ext.offset()
                                    + "," + ext.endExclusive() + ")",
                            dataSource.dataGroupAddress(),
                            null,
                            null,
                            ext.offset()
                    ));
                    accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                            MdfRecoveryDiagnosticCode.EXTENDED_UNFINALIZED_DT_RECOVERY,
                            MdfIssueSeverity.INFO,
                            "EXTENDED_UNFINALIZED_DT_RECOVERY region=[" + ext.offset()
                                    + "," + ext.endExclusive() + ")"
                                    + (resolution != null && resolution.usedEofFallback()
                                    ? " eofFallback=true" : "")
                                    + (resolution != null && resolution.acceptedBoundaryOffset() >= 0L
                                    ? " nextValidBlock@" + resolution.acceptedBoundaryOffset() : ""),
                            dataSource.dataGroupAddress(),
                            null,
                            null,
                            ext.offset()
                    ));
                }
                segments.add(ext);
            } else if (declared.isPresent()) {
                segments.add(declared.get());
            }
        }
        if (segments.isEmpty()) {
            segments.addAll(MdfStreamingRecordReader.resolvePayloadSegments(
                    access, dataSource, decodeDiags::add
            ));
            for (MdfDecodeDiagnostic d : decodeDiags) {
                accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                        MdfRecoveryDiagnosticCode.UNSUPPORTED_RECOVERY_SOURCE,
                        d.severity(),
                        d.reason(),
                        d.dataGroupAddress(),
                        d.channelGroupAddress(),
                        d.channelAddress(),
                        d.physicalOffset()
                ));
            }
        }
        return segments;
    }

    private static boolean structurallySoundRecovered(MdfDecodedRecord decoded) {
        return validateRecovered(decoded, MdfRecordDecodePlan.compile(decoded.schema())).sound();
    }

    private static RecoveryValidatedRecord validateRecovered(
            MdfDecodedRecord decoded,
            MdfRecordDecodePlan plan
    ) {
        MdfSchemaDispatch.Kind kind = plan != null ? plan.kind() : MdfSchemaDispatch.classify(decoded.schema());
        return switch (kind) {
            case NATIVE_MEASUREMENT -> new RecoveryValidatedRecord(
                    decoded, null, structurallySoundNative(decoded));
            case CAN_FRAME -> {
                MdfCanExtractionPlan canPlan = plan == null ? null : plan.canPlan();
                MdfCanFieldResolver.ResolvedCanFields resolved = canPlan == null
                        ? MdfCanFieldResolver.resolve(decoded)
                        : MdfCanFieldResolver.resolve(decoded, canPlan);
                yield new RecoveryValidatedRecord(decoded, resolved, structurallySoundCan(decoded, resolved));
            }
            case SKIP_NON_MEASUREMENT -> new RecoveryValidatedRecord(decoded, null, false);
        };
    }

    /**
     * Native fixed-length measurement: record length already matched; require valid
     * master/time extraction and at least one valid measurement field.
     */
    private static boolean structurallySoundNative(MdfDecodedRecord decoded) {
        Optional<MdfNativeMeasurementSchema> nativeSchema =
                MdfNativeMeasurementResolver.schemaOf(decoded.schema());
        if (nativeSchema.isEmpty()) {
            return false;
        }
        MdfNativeMeasurementSchema ns = nativeSchema.get();
        boolean masterOk = false;
        for (MdfDecodedField field : decoded.fields()) {
            if (field.schema().channelAddress() == ns.masterTimeChannel().channelAddress()
                    && field.valid()) {
                masterOk = true;
                break;
            }
        }
        if (!masterOk) {
            return false;
        }
        for (var fs : ns.measurementChannels()) {
            for (MdfDecodedField field : decoded.fields()) {
                if (field.schema().channelAddress() == fs.channelAddress() && field.valid()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean structurallySoundCan(
            MdfDecodedRecord decoded,
            MdfCanFieldResolver.ResolvedCanFields resolved
    ) {
        if (!resolved.complete()) {
            return false;
        }
        if (decoded.fields().stream().anyMatch(f ->
                f.invalid()
                        && (f.schema().canRoleCandidate().role() == MdfCanRole.CAN_ID
                        || f.schema().canRoleCandidate().role() == MdfCanRole.PAYLOAD))) {
            return false;
        }
        // Classic CAN: DLC must be 0..8 and must not exceed available payload bytes.
        // CAN FD may use larger DLC codes; require a non-negative DLC within FD bounds.
        int dlc = resolved.dlc();
        int payloadLen = resolved.payload() == null ? 0 : resolved.payload().length;
        if (!resolved.canFd()) {
            if (dlc < 0 || dlc > 8) {
                return false;
            }
            if (payloadLen < dlc) {
                return false;
            }
        } else if (dlc < 0 || dlc > 64) {
            return false;
        }
        return true;
    }

    private static long readRecordId(
            MdfPayloadSegment segment,
            MdfFileAccess access,
            long offset,
            int recordIdSize
    ) throws ParserException {
        byte[] raw = segment.read(access, offset, recordIdSize, "recoverId@" + offset);
        long id = 0L;
        for (int i = 0; i < recordIdSize; i++) {
            id |= (Byte.toUnsignedLong(raw[i]) << (8 * i));
        }
        return id;
    }

    /**
     * Limited resync: from an unknown-ID offset, try skipping exactly one known record length
     * and verify the next record ID maps to a schema with schema-appropriate structure.
     */
    private OptionalLong tryResyncOnKnownLengths(
            MdfFileAccess access,
            long unknownAt,
            long end,
            int recordIdSize,
            Map<Long, MdfRecordSchema> byRecordId,
            int resyncAttempts
    ) throws ParserException {
        if (resyncAttempts >= limits.maxResyncAttempts() || recordIdSize <= 0) {
            return OptionalLong.empty();
        }
        for (MdfRecordSchema skipSchema : byRecordId.values()) {
            if (skipSchema.vlsdChannelGroup()) {
                continue;
            }
            MdfSchemaDispatch.Kind kind = MdfSchemaDispatch.classify(skipSchema);
            if (kind == MdfSchemaDispatch.Kind.SKIP_NON_MEASUREMENT) {
                continue;
            }
            long at = unknownAt + skipSchema.completeRecordLength();
            if (at >= end || end - at < recordIdSize) {
                continue;
            }
            // File-backed probe only for resync (memory segments use offset 0-based ranges).
            byte[] idRaw = access.readFully(at, recordIdSize, "resyncId@" + at);
            long id = 0L;
            for (int i = 0; i < recordIdSize; i++) {
                id |= (Byte.toUnsignedLong(idRaw[i]) << (8 * i));
            }
            MdfRecordSchema schema = byRecordId.get(id);
            if (schema == null || end - at < schema.completeRecordLength()) {
                continue;
            }
            byte[] probe = access.readFully(at, (int) schema.completeRecordLength(), "resyncProbe@" + at);
            MdfDecodedRecord decoded = MdfRecordFieldDecoder.decode(schema, probe, at, id);
            if (structurallySoundRecovered(decoded)) {
                return OptionalLong.of(at);
            }
        }
        return OptionalLong.empty();
    }

    private static OptionalLong skipVlsdRecord(
            MdfSegmentCursor segCursor,
            long cursor,
            long end,
            int recordIdSize
    ) throws ParserException {
        // ASAM VLSD CG record: recordId + uint32 LE length + length bytes
        long needHeader = recordIdSize + 4L;
        if (end - cursor < needHeader) {
            return OptionalLong.empty();
        }
        long payloadLen = segCursor.readLittleEndianUnsigned(cursor + recordIdSize, 4, "recoverVlsdLen");
        long total = needHeader + payloadLen;
        if (end - cursor < total) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(total);
    }

    private void acceptAggregatedVlsd(
            Consumer<MdfRecoveryDiagnostic> diags,
            List<MdfRecoveryDiagnostic> diagnostics,
            long dg,
            long cg,
            long recordId,
            long offset
    ) {
        boolean already = diagnostics.stream()
                .anyMatch(d -> d.code() == MdfRecoveryDiagnosticCode.VLSD_RECORD_SKIPPED
                        && Objects.equals(d.channelGroupAddress(), cg));
        if (!already) {
            accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                    MdfRecoveryDiagnosticCode.VLSD_RECORD_SKIPPED,
                    MdfIssueSeverity.INFO,
                    "VLSD channel-group records skipped for stream positioning (not CAN frames)",
                    dg,
                    cg,
                    recordId,
                    offset
            ));
        }
    }

    private void acceptAggregatedUnknown(
            Consumer<MdfRecoveryDiagnostic> diags,
            List<MdfRecoveryDiagnostic> diagnostics,
            long dg,
            long recordId,
            long offset
    ) {
        boolean already = diagnostics.stream()
                .anyMatch(d -> d.code() == MdfRecoveryDiagnosticCode.UNKNOWN_RECORD_ID
                        && Objects.equals(d.recordId(), recordId));
        if (!already) {
            accept(diags, diagnostics, new MdfRecoveryDiagnostic(
                    MdfRecoveryDiagnosticCode.UNKNOWN_RECORD_ID,
                    MdfIssueSeverity.WARNING,
                    "Unknown record ID " + recordId,
                    dg,
                    null,
                    recordId,
                    offset
            ));
        }
    }

    private static void accept(
            Consumer<MdfRecoveryDiagnostic> sink,
            List<MdfRecoveryDiagnostic> list,
            MdfRecoveryDiagnostic diagnostic
    ) {
        list.add(diagnostic);
        sink.accept(diagnostic);
    }

    private record OptionalLong(boolean present, long value) {
        static OptionalLong empty() {
            return new OptionalLong(false, 0L);
        }

        static OptionalLong of(long value) {
            return new OptionalLong(true, value);
        }

        boolean isPresent() {
            return present;
        }

        long getAsLong() {
            return value;
        }
    }
}
