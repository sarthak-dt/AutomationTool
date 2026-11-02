package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.io.MdfReadWindow;
import com.stellar.platform.parser.spi.ParserException;

/**
 * Sequential cursor over a {@link MdfPayloadSegment}.
 * File-backed DT/SD uses a reusable read window; memory-backed DZ uses the inflated array in place;
 * spill-backed oversized DZ uses a temporary file opened for the cursor lifetime.
 */
public final class MdfSegmentCursor implements AutoCloseable {

    private final MdfFileAccess access;
    private final MdfPayloadSegment segment;
    private final MdfReadWindow window;
    private final boolean closeAccess;
    private byte[] scratch;

    public MdfSegmentCursor(MdfFileAccess access, MdfPayloadSegment segment) throws ParserException {
        this.segment = segment;
        if (segment.spillBacked()) {
            this.access = MdfFileAccess.open(segment.spilledPath());
            this.closeAccess = true;
            this.window = new MdfReadWindow(this.access);
        } else {
            this.access = access;
            this.closeAccess = false;
            this.window = segment.memoryBacked() ? null : new MdfReadWindow(access);
        }
    }

    public MdfSegmentCursor(MdfFileAccess access, MdfPayloadSegment segment, int windowBytes)
            throws ParserException {
        this.segment = segment;
        if (segment.spillBacked()) {
            this.access = MdfFileAccess.open(segment.spilledPath());
            this.closeAccess = true;
            this.window = new MdfReadWindow(this.access, windowBytes);
        } else {
            this.access = access;
            this.closeAccess = false;
            this.window = segment.memoryBacked() ? null : new MdfReadWindow(access, windowBytes);
        }
    }

    public long offset() {
        return segment.offset();
    }

    public long endExclusive() {
        return segment.endExclusive();
    }

    public RecordView view(long absoluteOffset, int length, String context) throws ParserException {
        if (length < 0) {
            throw new ParserException(context + ": negative length");
        }
        if (absoluteOffset < segment.offset()
                || absoluteOffset + (long) length > segment.endExclusive()) {
            throw new ParserException(context + "@" + absoluteOffset + ": read outside payload segment ["
                    + segment.offset() + "," + segment.endExclusive() + ")");
        }
        if (length == 0) {
            return new RecordView(RecordView.EMPTY, 0, 0);
        }
        if (segment.memoryBacked()) {
            int from = (int) (absoluteOffset - segment.offset());
            return new RecordView(segment.memoryBytes(), from, length);
        }
        if (length > window.capacity()) {
            return viewViaScratch(absoluteOffset, length, context);
        }
        int index = window.ensure(absoluteOffset, length, segment.endExclusive(), "dt-window");
        return new RecordView(window.array(), index, length);
    }

    public long readLittleEndianUnsigned(long absoluteOffset, int size, String context)
            throws ParserException {
        RecordView v = view(absoluteOffset, size, context);
        long id = 0L;
        for (int i = 0; i < size; i++) {
            id |= (Byte.toUnsignedLong(v.buffer()[v.offset() + i]) << (8 * i));
        }
        return id;
    }

    private RecordView viewViaScratch(long absoluteOffset, int length, String context)
            throws ParserException {
        if (scratch == null || scratch.length < length) {
            scratch = new byte[length];
        }
        access.readInto(absoluteOffset, scratch, 0, length, context);
        return new RecordView(scratch, 0, length);
    }

    @Override
    public void close() {
        if (closeAccess) {
            try {
                access.close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    public record RecordView(byte[] buffer, int offset, int length) {
        static final byte[] EMPTY = new byte[0];
    }
}
