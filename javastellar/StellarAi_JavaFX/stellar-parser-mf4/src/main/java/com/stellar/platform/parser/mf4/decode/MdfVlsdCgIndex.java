package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.io.MdfSafeMath;
import com.stellar.platform.parser.spi.ParserException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Directory of CG-linked VLSD entries: logical offset to length-field location.
 * <p>
 * ASAM VLSD CG records contribute uint32 LE length plus payload to a contiguous logical
 * byte stream (record IDs are excluded from the logical offsets stored in referencing channels).
 * This index stores only directory entries, never full payloads or complete frame lists.
 */
final class MdfVlsdCgIndex {

    private final long[] logicalOffsets;
    private final long[] lengthFieldOffsets;
    private final int[] payloadLengths;
    private final MdfPayloadSegment[] segments;

    private MdfVlsdCgIndex(
            long[] logicalOffsets,
            long[] lengthFieldOffsets,
            int[] payloadLengths,
            MdfPayloadSegment[] segments
    ) {
        this.logicalOffsets = logicalOffsets;
        this.lengthFieldOffsets = lengthFieldOffsets;
        this.payloadLengths = payloadLengths;
        this.segments = segments;
    }

    static MdfVlsdCgIndex build(
            MdfFileAccess access,
            List<MdfRecordSchema> dgSchemas,
            MdfRecordSchema vlsdSchema,
            List<MdfPayloadSegment> segments
    ) throws ParserException {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(vlsdSchema, "vlsdSchema");
        if (!vlsdSchema.vlsdChannelGroup()) {
            return new MdfVlsdCgIndex(new long[0], new long[0], new int[0], new MdfPayloadSegment[0]);
        }
        int recordIdSize = vlsdSchema.recordIdSize();
        Map<Long, MdfRecordSchema> byRecordId = new HashMap<>();
        for (MdfRecordSchema schema : dgSchemas) {
            if (schema.dataGroupAddress() == vlsdSchema.dataGroupAddress()) {
                byRecordId.put(schema.recordId(), schema);
            }
        }
        long vlsdRecordId = vlsdSchema.recordId();

        List<Long> logicals = new ArrayList<>();
        List<Long> lengthOffsets = new ArrayList<>();
        List<Integer> lengths = new ArrayList<>();
        List<MdfPayloadSegment> entrySegments = new ArrayList<>();

        long logical = 0L;
        for (MdfPayloadSegment segment : segments) {
            long cursor = segment.offset();
            long end = segment.endExclusive();
            try (MdfSegmentCursor reader = new MdfSegmentCursor(access, segment)) {
            while (cursor < end) {
                long remaining = end - cursor;
                if (recordIdSize > 0 && remaining < recordIdSize) {
                    break;
                }
                long recordId;
                if (recordIdSize == 0) {
                    if (byRecordId.size() != 1) {
                        break;
                    }
                    recordId = byRecordId.keySet().iterator().next();
                } else {
                    recordId = reader.readLittleEndianUnsigned(cursor, recordIdSize, "vlsdIdxId");
                }
                MdfRecordSchema schema = byRecordId.get(recordId);
                if (schema == null) {
                    break;
                }
                if (schema.vlsdChannelGroup() && recordId == vlsdRecordId) {
                    if (remaining < recordIdSize + 4L) {
                        break;
                    }
                    long lengthFieldAbs = MdfSafeMath.addExact(cursor, recordIdSize, "vlsdLenAbs");
                    long payloadLen = reader.readLittleEndianUnsigned(lengthFieldAbs, 4, "vlsdIdxLen");
                    if (payloadLen > MdfVlsdResolver.MAX_VLSD_PAYLOAD_BYTES) {
                        break;
                    }
                    long complete = recordIdSize + 4L + payloadLen;
                    if (remaining < complete) {
                        break;
                    }
                    logicals.add(logical);
                    lengthOffsets.add(lengthFieldAbs);
                    lengths.add((int) payloadLen);
                    entrySegments.add(segment);
                    logical = MdfSafeMath.addExact(logical, 4L + payloadLen, "vlsdLogical");
                    cursor = MdfSafeMath.addExact(cursor, complete, "vlsdIdxCursor");
                } else if (schema.vlsdChannelGroup()) {
                    if (remaining < recordIdSize + 4L) {
                        break;
                    }
                    long payloadLen = reader.readLittleEndianUnsigned(cursor + recordIdSize, 4, "vlsdOtherLen");
                    long complete = recordIdSize + 4L + payloadLen;
                    if (remaining < complete || payloadLen > MdfVlsdResolver.MAX_VLSD_PAYLOAD_BYTES) {
                        break;
                    }
                    cursor = MdfSafeMath.addExact(cursor, complete, "vlsdOtherCursor");
                } else {
                    long complete = schema.completeRecordLength();
                    if (complete <= 0L || remaining < complete) {
                        break;
                    }
                    cursor = MdfSafeMath.addExact(cursor, complete, "fixedIdxCursor");
                }
            }
            }
        }

        int n = logicals.size();
        long[] lo = new long[n];
        long[] lf = new long[n];
        int[] pl = new int[n];
        MdfPayloadSegment[] segs = new MdfPayloadSegment[n];
        for (int i = 0; i < n; i++) {
            lo[i] = logicals.get(i);
            lf[i] = lengthOffsets.get(i);
            pl[i] = lengths.get(i);
            segs[i] = entrySegments.get(i);
        }
        return new MdfVlsdCgIndex(lo, lf, pl, segs);
    }

    Optional<ResolvedEntry> lookup(long logicalOffset) {
        int idx = java.util.Arrays.binarySearch(logicalOffsets, logicalOffset);
        if (idx < 0) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedEntry(segments[idx], lengthFieldOffsets[idx], payloadLengths[idx]));
    }

    record ResolvedEntry(MdfPayloadSegment segment, long lengthFieldOffset, int payloadLength) {
    }
}