package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.block.MdfBlockHeader;
import com.stellar.platform.parser.mf4.block.MdfBlockHeaderReader;
import com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.spi.ParserException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-data-group context for VLSD enrichment: sibling runtime schemas, optional payload segments,
 * and a bounded offset index cache for CG-linked VLSD channels.
 * <p>
 * Indexes store only offset directories (not payloads) and are built once per VLSD CG address.
 * Immutable {@code cn_data} block headers are cached per address, and CG-linked payloads are read
 * through one small sequential window. The context lives for one data-group decode and must be
 * closed by its owner.
 */
public final class MdfVlsdEnrichmentContext implements AutoCloseable {

    /** VLSD payloads are at most a few hundred bytes and are read in stream order. */
    static final int PAYLOAD_WINDOW_BYTES = 64 * 1024;

    private final List<MdfRecordSchema> dgSchemas;
    private final List<MdfPayloadSegment> segments;
    private final Map<Long, MdfVlsdCgIndex> indexByVlsdCgAddress = new HashMap<>();
    private final Map<Long, MdfBlockHeader> headerByAddress = new HashMap<>();
    private MdfPayloadSegment payloadSegment;
    private MdfSegmentCursor payloadCursor;

    private MdfVlsdEnrichmentContext(List<MdfRecordSchema> dgSchemas, List<MdfPayloadSegment> segments) {
        this.dgSchemas = List.copyOf(Objects.requireNonNull(dgSchemas, "dgSchemas"));
        this.segments = segments == null ? null : List.copyOf(segments);
    }

    public static MdfVlsdEnrichmentContext of(List<MdfRecordSchema> dgSchemas) {
        return new MdfVlsdEnrichmentContext(dgSchemas, null);
    }

    public static MdfVlsdEnrichmentContext of(
            List<MdfRecordSchema> dgSchemas,
            List<MdfPayloadSegment> segments
    ) {
        return new MdfVlsdEnrichmentContext(dgSchemas, segments);
    }

    public List<MdfRecordSchema> dgSchemas() {
        return dgSchemas;
    }

    Optional<MdfRecordSchema> schemaForChannelGroup(long channelGroupAddress) {
        for (MdfRecordSchema schema : dgSchemas) {
            if (schema.channelGroupAddress() == channelGroupAddress) {
                return Optional.of(schema);
            }
        }
        return Optional.empty();
    }

    MdfBlockHeader blockHeader(MdfFileAccess access, long address) throws ParserException {
        MdfBlockHeader cached = headerByAddress.get(address);
        if (cached != null) {
            return cached;
        }
        MdfBlockHeader header = MdfBlockHeaderReader.read(access, address);
        headerByAddress.put(address, header);
        return header;
    }

    /** Copies {@code length} payload bytes; the window never retains more than one refill. */
    byte[] readPayload(MdfFileAccess access, MdfPayloadSegment segment, long absoluteOffset, int length)
            throws ParserException {
        if (payloadCursor == null || payloadSegment != segment) {
            closePayloadCursor();
            payloadCursor = new MdfSegmentCursor(access, segment, PAYLOAD_WINDOW_BYTES);
            payloadSegment = segment;
        }
        MdfSegmentCursor.RecordView view = payloadCursor.view(absoluteOffset, length, "vlsd-cg-payload");
        return Arrays.copyOfRange(view.buffer(), view.offset(), view.offset() + view.length());
    }

    MdfVlsdCgIndex indexForVlsdCg(
            MdfFileAccess access,
            MdfRecordSchema vlsdSchema
    ) throws ParserException {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(vlsdSchema, "vlsdSchema");
        Long key = vlsdSchema.channelGroupAddress();
        MdfVlsdCgIndex cached = indexByVlsdCgAddress.get(key);
        if (cached != null) {
            return cached;
        }
        List<MdfPayloadSegment> segs = segments;
        if (segs == null || segs.isEmpty()) {
            segs = MdfStreamingRecordReader.resolvePayloadSegments(
                    access,
                    vlsdSchema.dataSource(),
                    d -> {
                    }
            );
        }
        MdfVlsdCgIndex built = MdfVlsdCgIndex.build(access, dgSchemas, vlsdSchema, segs);
        indexByVlsdCgAddress.put(key, built);
        return built;
    }

    private void closePayloadCursor() {
        if (payloadCursor != null) {
            payloadCursor.close();
            payloadCursor = null;
            payloadSegment = null;
        }
    }

    @Override
    public void close() {
        closePayloadCursor();
        headerByAddress.clear();
        indexByVlsdCgAddress.clear();
    }
}
