package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.analysis.MdfIssueSeverity;
import com.stellar.platform.parser.mf4.block.MdfBlockHeader;
import com.stellar.platform.parser.mf4.block.MdfBlockHeaderReader;
import com.stellar.platform.parser.mf4.block.MdfBlockType;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDataSourceNode;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDataSourceSchema;
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
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Streams raw records from resolved DT / DL / HL / DZ data sources without loading the whole file.
 * DZ blocks are inflated one logical block at a time via {@link MdfDzDecompressor}.
 */
public final class MdfStreamingRecordReader {

    @FunctionalInterface
    public interface RawRecordHandler {
        void onRecord(
                MdfRecordSchema schema,
                MdfRecordDecodePlan plan,
                byte[] buffer,
                int offset,
                int length,
                long physicalOffset,
                long recordId
        ) throws ParserException;
    }

    private MdfStreamingRecordReader() {
    }

    /** Live decode position after the last fully consumed record. */
    public static final class StreamState {
        public int segmentIndex;
        public long nextByteOffset;
        public long recordsVisited;
        public final Map<Long, Long> remainingCycles = new HashMap<>();
    }

    public record StreamResume(int startSegmentIndex, long startByteOffset, Map<Long, Long> remainingCycles) {
        public StreamResume {
            startSegmentIndex = Math.max(0, startSegmentIndex);
            startByteOffset = Math.max(0L, startByteOffset);
            remainingCycles = remainingCycles == null ? Map.of() : Map.copyOf(remainingCycles);
        }
    }

    public static void streamDataGroup(
            MdfFileAccess access,
            long dataGroupAddress,
            List<MdfRecordSchema> schemasForDg,
            CancellationToken cancellation,
            RawRecordHandler handler,
            Consumer<MdfDecodeDiagnostic> diagnostics
    ) throws ParserException {
        streamDataGroup(access, dataGroupAddress, schemasForDg, cancellation, handler, diagnostics, null, null, null);
    }

    public static void streamDataGroup(
            MdfFileAccess access,
            long dataGroupAddress,
            List<MdfRecordSchema> schemasForDg,
            CancellationToken cancellation,
            RawRecordHandler handler,
            Consumer<MdfDecodeDiagnostic> diagnostics,
            StreamResume resume,
            StreamState state,
            Runnable afterEachRecord
    ) throws ParserException {
        if (schemasForDg.isEmpty()) {
            return;
        }

        Map<MdfRecordSchema, MdfRecordDecodePlan> plans = new IdentityHashMap<>();
        for (MdfRecordSchema schema : schemasForDg) {
            plans.put(schema, MdfRecordDecodePlan.compile(schema));
        }

        List<MdfRecordSchema> recoverable = new ArrayList<>();
        List<MdfRecordSchema> active = new ArrayList<>();
        for (MdfRecordSchema schema : schemasForDg) {
            if (schema.status() == MdfSchemaStatus.RECOVERY_CANDIDATE && schema.declaredCycleCount() == 0L) {
                recoverable.add(schema);
            } else if (schema.status() != MdfSchemaStatus.UNUSABLE && schema.completeRecordLength() > 0) {
                active.add(schema);
            }
        }

        for (MdfRecordSchema schema : recoverable) {
            diagnostics.accept(new MdfDecodeDiagnostic(
                    MdfDecodeDiagnosticCode.RECOVERY_REQUIRED,
                    MdfIssueSeverity.RECOVERABLE,
                    "Schema is RECOVERY_CANDIDATE; deferred to Recovery Engine (not silent zero)",
                    schema.dataGroupAddress(),
                    schema.channelGroupAddress(),
                    null,
                    schema.dataSource().dataBlockRootAddress()
            ));
        }

        MdfDataSourceSchema dataSource = !active.isEmpty()
                ? active.getFirst().dataSource()
                : schemasForDg.getFirst().dataSource();

        if (active.isEmpty()) {
            resolvePayloadSegments(access, dataSource, diagnostics);
            return;
        }
        int recordIdSize = active.getFirst().recordIdSize();
        for (MdfRecordSchema schema : active) {
            if (schema.recordIdSize() != recordIdSize) {
                diagnostics.accept(new MdfDecodeDiagnostic(
                        MdfDecodeDiagnosticCode.UNKNOWN_RECORD_ID,
                        MdfIssueSeverity.FATAL,
                        "Inconsistent recordIdSize within DG",
                        dataGroupAddress,
                        schema.channelGroupAddress(),
                        null,
                        0L
                ));
                return;
            }
        }

        Map<Long, MdfRecordSchema> byRecordId = new HashMap<>();
        Map<Long, Long> remainingCycles = new HashMap<>();
        int openCount = 0;
        for (MdfRecordSchema schema : active) {
            byRecordId.put(schema.recordId(), schema);
            remainingCycles.put(schema.channelGroupAddress(), schema.declaredCycleCount());
            if (schema.declaredCycleCount() > 0L) {
                openCount++;
            }
        }
        if (resume != null && !resume.remainingCycles().isEmpty()) {
            remainingCycles.putAll(resume.remainingCycles());
            openCount = 0;
            for (Long left : remainingCycles.values()) {
                if (left != null && left > 0L) {
                    openCount++;
                }
            }
        }
        if (state != null) {
            state.remainingCycles.clear();
            state.remainingCycles.putAll(remainingCycles);
        }
        Map<Long, Long> skipLengthsByRecordId = new HashMap<>();
        for (MdfRecordSchema schema : recoverable) {
            if (schema.completeRecordLength() > 0L) {
                skipLengthsByRecordId.put(schema.recordId(), schema.completeRecordLength());
            }
        }

        MdfRecordSchema soleSchema = recordIdSize == 0 && active.size() == 1 ? active.getFirst() : null;

        List<MdfPayloadSegment> segments = resolvePayloadSegments(access, dataSource, diagnostics);
        int[] open = {openCount};
        try {
            for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
                MdfPayloadSegment segment = segments.get(segmentIndex);
                if (resume != null && segmentIndex < resume.startSegmentIndex()) {
                    continue;
                }
                if (cancellation.isCancellationRequested()) {
                    diagnostics.accept(new MdfDecodeDiagnostic(
                            MdfDecodeDiagnosticCode.CANCELLED,
                            MdfIssueSeverity.INFO,
                            "Cancellation requested",
                            dataGroupAddress,
                            null,
                            null,
                            segment.offset()
                    ));
                    return;
                }
                long startCursor = segment.offset();
                if (resume != null && segmentIndex == resume.startSegmentIndex()) {
                    startCursor = Math.max(segment.offset(), resume.startByteOffset());
                }
                streamSegment(
                        access,
                        segment,
                        recordIdSize,
                        soleSchema,
                        byRecordId,
                        skipLengthsByRecordId,
                        remainingCycles,
                        open,
                        plans,
                        cancellation,
                        handler,
                        diagnostics,
                        dataGroupAddress,
                        startCursor,
                        segmentIndex,
                        state,
                        afterEachRecord
                );
                if (open[0] == 0 && !remainingCycles.isEmpty()) {
                    return;
                }
            }
        } finally {
            for (MdfPayloadSegment segment : segments) {
                segment.discardSpill();
            }
        }
    }

    private static void streamSegment(
            MdfFileAccess access,
            MdfPayloadSegment segment,
            int recordIdSize,
            MdfRecordSchema soleSchema,
            Map<Long, MdfRecordSchema> byRecordId,
            Map<Long, Long> skipLengthsByRecordId,
            Map<Long, Long> remainingCycles,
            int[] open,
            Map<MdfRecordSchema, MdfRecordDecodePlan> plans,
            CancellationToken cancellation,
            RawRecordHandler handler,
            Consumer<MdfDecodeDiagnostic> diagnostics,
            long dataGroupAddress,
            long startCursor,
            int segmentIndex,
            StreamState state,
            Runnable afterEachRecord
    ) throws ParserException {
        long cursor = startCursor > 0L ? startCursor : segment.offset();
        if (cursor < segment.offset()) {
            cursor = segment.offset();
        }
        long end = segment.endExclusive();
        Set<Long> unknownIds = new HashSet<>();
        try (MdfSegmentCursor window = new MdfSegmentCursor(access, segment)) {
        while (cursor < end) {
            if (cancellation.isCancellationRequested()) {
                diagnostics.accept(new MdfDecodeDiagnostic(
                        MdfDecodeDiagnosticCode.CANCELLED,
                        MdfIssueSeverity.INFO,
                        "Cancellation requested",
                        dataGroupAddress,
                        null,
                        null,
                        cursor
                ));
                return;
            }
            if (open[0] == 0 && !remainingCycles.isEmpty()) {
                boolean anyLeft = false;
                for (Long left : remainingCycles.values()) {
                    if (left == null || left > 0L) {
                        anyLeft = true;
                        break;
                    }
                }
                if (!anyLeft) {
                    return;
                }
            }

            long remaining = end - cursor;
            if (recordIdSize > 0 && remaining < recordIdSize) {
                diagnostics.accept(new MdfDecodeDiagnostic(
                        MdfDecodeDiagnosticCode.TRUNCATED_RECORD,
                        MdfIssueSeverity.RECOVERABLE,
                        "Truncated record-id region at end of data source",
                        dataGroupAddress,
                        null,
                        null,
                        cursor
                ));
                return;
            }

            long recordId;
            MdfRecordSchema schema;
            if (recordIdSize == 0) {
                if (soleSchema == null) {
                    diagnostics.accept(new MdfDecodeDiagnostic(
                            MdfDecodeDiagnosticCode.UNKNOWN_RECORD_ID,
                            MdfIssueSeverity.FATAL,
                            "recordIdSize=0 but multiple active CGs; cannot disambiguate",
                            dataGroupAddress,
                            null,
                            null,
                            cursor
                    ));
                    return;
                }
                recordId = soleSchema.recordId();
                schema = soleSchema;
            } else {
                recordId = window.readLittleEndianUnsigned(cursor, recordIdSize, "recordId");
                schema = byRecordId.get(recordId);
                if (schema == null) {
                    Long skipLen = skipLengthsByRecordId.get(recordId);
                    if (skipLen != null && skipLen > 0L && remaining >= skipLen) {
                        try {
                            cursor = MdfSafeMath.addExact(cursor, skipLen, "skipDeferredRecord");
                        } catch (ArithmeticException e) {
                            diagnostics.accept(new MdfDecodeDiagnostic(
                                    MdfDecodeDiagnosticCode.RECORD_LENGTH_OVERFLOW,
                                    MdfIssueSeverity.FATAL,
                                    "Cursor overflow while skipping deferred recordId=" + recordId,
                                    dataGroupAddress,
                                    null,
                                    null,
                                    cursor
                            ));
                            return;
                        }
                        continue;
                    }
                    if (unknownIds.add(recordId)) {
                        diagnostics.accept(new MdfDecodeDiagnostic(
                                MdfDecodeDiagnosticCode.UNKNOWN_RECORD_ID,
                                MdfIssueSeverity.WARNING,
                                "Unknown record ID " + recordId + "; stopping DG stream safely",
                                dataGroupAddress,
                                null,
                                null,
                                cursor
                        ));
                    }
                    return;
                }
            }

            Long left = remainingCycles.get(schema.channelGroupAddress());
            if (left != null && left <= 0L) {
                if (soleSchema != null || byRecordId.size() == 1) {
                    return;
                }
            }

            MdfRecordDecodePlan plan = plans.computeIfAbsent(schema, MdfRecordDecodePlan::compile);
            long completeLen;
            if (plan.vlsdChannelGroup()) {
                if (remaining < recordIdSize + 4L) {
                    diagnostics.accept(new MdfDecodeDiagnostic(
                            MdfDecodeDiagnosticCode.TRUNCATED_RECORD,
                            MdfIssueSeverity.RECOVERABLE,
                            "Truncated VLSD length header",
                            dataGroupAddress,
                            schema.channelGroupAddress(),
                            null,
                            cursor
                    ));
                    return;
                }
                long payloadLen = window.readLittleEndianUnsigned(cursor + recordIdSize, 4, "vlsdLen");
                completeLen = recordIdSize + 4L + payloadLen;
            } else {
                completeLen = plan.completeRecordLength();
            }
            if (completeLen <= 0) {
                diagnostics.accept(new MdfDecodeDiagnostic(
                        MdfDecodeDiagnosticCode.RECORD_LENGTH_OVERFLOW,
                        MdfIssueSeverity.FATAL,
                        "Non-positive complete record length",
                        dataGroupAddress,
                        schema.channelGroupAddress(),
                        null,
                        cursor
                ));
                return;
            }
            if (remaining < completeLen) {
                diagnostics.accept(new MdfDecodeDiagnostic(
                        MdfDecodeDiagnosticCode.TRUNCATED_RECORD,
                        MdfIssueSeverity.RECOVERABLE,
                        "Incomplete final record: remaining=" + remaining + " need=" + completeLen,
                        dataGroupAddress,
                        schema.channelGroupAddress(),
                        null,
                        cursor
                ));
                return;
            }
            if (completeLen > Integer.MAX_VALUE) {
                diagnostics.accept(new MdfDecodeDiagnostic(
                        MdfDecodeDiagnosticCode.RECORD_LENGTH_OVERFLOW,
                        MdfIssueSeverity.FATAL,
                        "Record length exceeds readable buffer capacity",
                        dataGroupAddress,
                        schema.channelGroupAddress(),
                        null,
                        cursor
                ));
                return;
            }

            MdfSchemaDispatch.Kind kind = plan.kind();
            boolean canEmit = !plan.vlsdChannelGroup()
                    && kind == MdfSchemaDispatch.Kind.CAN_FRAME
                    && (left == null || left > 0L);
            boolean nativeEmit = !plan.vlsdChannelGroup()
                    && kind == MdfSchemaDispatch.Kind.NATIVE_MEASUREMENT
                    && (left == null || left > 0L);
            if (canEmit || nativeEmit) {
                MdfSegmentCursor.RecordView view = window.view(cursor, (int) completeLen, "record");
                handler.onRecord(schema, plan, view.buffer(), view.offset(), view.length(), cursor, recordId);
                if (state != null) {
                    state.recordsVisited++;
                }
                if (left != null && left > 0L) {
                    long next = left - 1L;
                    remainingCycles.put(schema.channelGroupAddress(), next);
                    if (next == 0L && open[0] > 0) {
                        open[0]--;
                    }
                }
            } else if (plan.vlsdChannelGroup()) {
                diagnostics.accept(new MdfDecodeDiagnostic(
                        MdfDecodeDiagnosticCode.UNSUPPORTED_DATA_SOURCE,
                        MdfIssueSeverity.INFO,
                        "VLSD record skipped for positioning (recordId=" + recordId + ")",
                        dataGroupAddress,
                        schema.channelGroupAddress(),
                        null,
                        cursor
                ));
                if (left != null && left > 0L) {
                    long next = left - 1L;
                    remainingCycles.put(schema.channelGroupAddress(), next);
                    if (next == 0L && open[0] > 0) {
                        open[0]--;
                    }
                }
            } else if (kind == MdfSchemaDispatch.Kind.SKIP_NON_MEASUREMENT) {
                diagnostics.accept(new MdfDecodeDiagnostic(
                        MdfDecodeDiagnosticCode.NON_CAN_CHANNEL_GROUP_SKIPPED,
                        MdfIssueSeverity.INFO,
                        "Skipped non-measurement Channel Group busKind=" + schema.busKind()
                                + " (recordId=" + recordId + ")",
                        dataGroupAddress,
                        schema.channelGroupAddress(),
                        null,
                        cursor
                ));
                if (left != null && left > 0L) {
                    long next = left - 1L;
                    remainingCycles.put(schema.channelGroupAddress(), next);
                    if (next == 0L && open[0] > 0) {
                        open[0]--;
                    }
                }
            }
            try {
                cursor = MdfSafeMath.addExact(cursor, completeLen, "recordCursor");
            } catch (ArithmeticException e) {
                diagnostics.accept(new MdfDecodeDiagnostic(
                        MdfDecodeDiagnosticCode.RECORD_LENGTH_OVERFLOW,
                        MdfIssueSeverity.FATAL,
                        "Cursor overflow",
                        dataGroupAddress,
                        schema.channelGroupAddress(),
                        null,
                        cursor
                ));
                return;
            }
            if (state != null) {
                state.segmentIndex = segmentIndex;
                state.nextByteOffset = cursor;
                state.remainingCycles.clear();
                state.remainingCycles.putAll(remainingCycles);
            }
            if (afterEachRecord != null) {
                afterEachRecord.run();
            }
        }
        }
    }

    /**
     * Resolves ordered readable DT/SD/DZ payload segments for a DG data source (shared with Recovery Engine).
     */
    public static List<MdfPayloadSegment> resolvePayloadSegments(
            MdfFileAccess access,
            MdfDataSourceSchema dataSource,
            Consumer<MdfDecodeDiagnostic> diagnostics
    ) throws ParserException {
        List<MdfPayloadSegment> segments = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        for (MdfDataSourceNode node : dataSource.orderedNodes()) {
            if (!visited.add(node.address())) {
                diagnostics.accept(new MdfDecodeDiagnostic(
                        MdfDecodeDiagnosticCode.UNSUPPORTED_DATA_SOURCE,
                        MdfIssueSeverity.FATAL,
                        "Cyclic data-source node at " + node.address(),
                        dataSource.dataGroupAddress(),
                        null,
                        null,
                        node.address()
                ));
                break;
            }
            if (node.blockType() == MdfBlockType.DZ) {
                MdfDzDecompressor.DzDecodeResult dz = MdfDzDecompressor.decompress(
                        access, node, dataSource.dataGroupAddress()
                );
                if (!dz.success()) {
                    diagnostics.accept(dz.diagnostic());
                    continue;
                }
                segments.add(dz.toSegment(node.address()));
                continue;
            }
            if (node.blockType() == MdfBlockType.DL || node.blockType() == MdfBlockType.HL) {
                continue;
            }
            if (node.blockType() != MdfBlockType.DT && node.blockType() != MdfBlockType.SD) {
                diagnostics.accept(new MdfDecodeDiagnostic(
                        MdfDecodeDiagnosticCode.UNSUPPORTED_DATA_SOURCE,
                        MdfIssueSeverity.WARNING,
                        "Unsupported data-source block type " + node.blockType(),
                        dataSource.dataGroupAddress(),
                        null,
                        null,
                        node.address()
                ));
                continue;
            }
            Optional<MdfPayloadSegment> segment = dtSegment(access, node, false);
            if (segment.isPresent()) {
                segments.add(segment.get());
            }
        }
        return segments;
    }

    public static Optional<MdfPayloadSegment> dtSegment(
            MdfFileAccess access,
            MdfDataSourceNode node,
            boolean includeTrailingAfterBlock
    ) throws ParserException {
        return dtSegment(access, node, includeTrailingAfterBlock, MdfDtBoundaryRequest.defaults(), null);
    }

    public static Optional<MdfPayloadSegment> dtSegment(
            MdfFileAccess access,
            MdfDataSourceNode node,
            boolean includeTrailingAfterBlock,
            MdfDtBoundaryRequest boundaryRequest,
            Consumer<MdfDtBoundaryResolution> boundarySink
    ) throws ParserException {
        MdfBlockHeader header = MdfBlockHeaderReader.read(access, node.address());
        long start = header.dataSectionOffset();
        long payloadLen = Math.min(header.declaredDataLength(), node.physicallyReadableLength());
        if (payloadLen <= 0L) {
            payloadLen = Math.max(0L, header.declaredDataLength());
        }
        long end = start;
        if (payloadLen > 0L) {
            try {
                end = MdfSafeMath.addExact(start, payloadLen, "dtPayloadEnd");
            } catch (ArithmeticException e) {
                throw new ParserException("DT payload end overflow at " + node.address(), e);
            }
        }
        boolean declaredUnusable = payloadLen <= 0L;
        if (includeTrailingAfterBlock) {
            long orphanStart = header.blockEnd();
            MdfDtBoundaryRequest request = boundaryRequest == null
                    ? MdfDtBoundaryRequest.defaults()
                    : boundaryRequest;
            if (declaredUnusable && !request.allowEofFallback()) {
                request = MdfDtBoundaryRequest.of(true, request.cancellation());
            }
            long searchFrom = orphanStart;
            MdfDtBoundaryResolution resolution = MdfUnfinalizedDtBoundaryResolver.resolveUnfinalizedDtEnd(
                    access,
                    searchFrom,
                    declaredUnusable,
                    request
            );
            if (boundarySink != null) {
                boundarySink.accept(resolution);
            }
            long orphanEnd = resolution.endExclusive();
            if (declaredUnusable && orphanEnd > orphanStart) {
                start = orphanStart;
                end = orphanEnd;
            } else if (orphanEnd > end) {
                end = orphanEnd;
            }
        } else if (boundarySink != null) {
            boundarySink.accept(MdfDtBoundaryResolution.ofDeclared(end, declaredUnusable));
        }
        if (end > access.fileSize()) {
            end = access.fileSize();
        }
        if (end < start) {
            end = start;
        }
        if (end <= start) {
            return Optional.empty();
        }
        return Optional.of(MdfPayloadSegment.file(start, end));
    }

    public static long findNextMdfBlockAddress(MdfFileAccess access, long from) throws ParserException {
        return findNextMdfBlockAddress(access, from, CancellationToken.NONE);
    }

    public static long findNextMdfBlockAddress(
            MdfFileAccess access,
            long from,
            CancellationToken cancellation
    ) throws ParserException {
        Optional<MdfBlockHeader> next = MdfUnfinalizedDtBoundaryResolver.findNextValidBlock(
                access, from, cancellation);
        return next.map(MdfBlockHeader::address).orElse(access.fileSize());
    }
}