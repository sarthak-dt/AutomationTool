package com.stellar.platform.parser.blf;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FileMetadata;
import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.CanFrameConsumer;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.WarningConsumer;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Streaming Vector BLF reader. Walks objects and containers without loading the file.
 */
public final class BlfFileReader implements AutomotiveFileReader {

    static final String FORMAT_KEY = "blf";

    private static final System.Logger LOG = System.getLogger(BlfFileReader.class.getName());

    @Override
    public String formatKey() {
        return FORMAT_KEY;
    }

    @Override
    public boolean supports(Path path) {
        return BlfContentProbe.parserCanRead(path);
    }

    @Override
    public FileMetadata readMetadata(Path path) throws ParserException {
        Path normalized = requireSupportedPath(path);
        return new FileMetadata(FORMAT_KEY, normalized.getFileName().toString(), normalized.toAbsolutePath().toString());
    }

    @Override
    public void readFrames(
            Path path,
            ReaderContext context,
            CanFrameConsumer frameConsumer,
            WarningConsumer warningConsumer
    ) throws ParserException {
        readFramesInternal(path, context, frameConsumer, warningConsumer);
    }

    BlfParseSummary readFramesInternal(
            Path path,
            ReaderContext context,
            CanFrameConsumer frameConsumer,
            WarningConsumer warningConsumer
    ) throws ParserException {
        Path normalized = requireSupportedPath(path);
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(frameConsumer, "frameConsumer");
        BlfDiagnostics diagnostics = new BlfDiagnostics(warningConsumer);
        List<CanonicalCanFrame> batch = new ArrayList<>(context.batchSize());
        WalkState state = new WalkState();
        BlfFileHeader header;
        try (FileChannel channel = FileChannel.open(normalized, StandardOpenOption.READ)) {
            BlfChannelInput in = new BlfChannelInput(channel);
            header = BlfHeaderParser.read(in, channel.size());
            walk(in, 0, context, batch, frameConsumer, diagnostics, state);
        } catch (ParserException e) {
            throw e;
        } catch (IOException e) {
            throw new ParserException("BLF I/O failure: " + e.getMessage(), e);
        }
        if (state.carry.length > 0) {
            diagnostics.malformed(
                    "BLF object is truncated at the end of the file (" + state.carry.length + " bytes)",
                    0L);
        }
        flush(batch, frameConsumer);
        BlfParseSummary summary = new BlfParseSummary(
                normalized.getFileName().toString(),
                header,
                diagnostics,
                state.channels,
                state.identities.size(),
                state.firstTs,
                state.lastTs
        );
        LOG.log(System.Logger.Level.INFO, summary::formatLogLine);
        return summary;
    }

    private void walk(
            BlfInput in,
            int depth,
            ReaderContext context,
            List<CanonicalCanFrame> batch,
            CanFrameConsumer frameConsumer,
            BlfDiagnostics diagnostics,
            WalkState state
    ) throws IOException, ParserException {
        while (in.remaining() > 0) {
            if (context.cancellationToken().isCancellationRequested()) {
                break;
            }
            skipZeroPadding(in);
            if (in.remaining() == 0) {
                break;
            }
            if (in.remaining() < BlfConstants.OBJECT_BASE_SIZE) {
                if (in.remaining() == 0) {
                    break;
                }
                if (in instanceof BlfBufferInput) {
                    throw new BlfContainerSplit(in.position());
                }
                if (in.remaining() >= 4) {
                    throw new ParserException("Truncated BLF object header at offset " + in.position());
                }
                diagnostics.malformed("Trailing bytes smaller than an object header", in.position());
                break;
            }
            BlfObjectReader.BlfObject object = BlfObjectReader.next(in, diagnostics);
            if (object == null) {
                break;
            }
            diagnostics.noteObject(object.header().objectType());
            BlfObjectClass kind = BlfObjectTypes.classify(object.header().objectType());
            switch (kind) {
                case CONTAINER -> {
                    if (depth >= BlfConstants.MAX_CONTAINER_DEPTH) {
                        diagnostics.containerWarning("Nested LOG_CONTAINER depth exceeded", object.header().fileOffset());
                        break;
                    }
                    byte[] inflated = BlfContainerDecoder.decompress(
                            object.payload(), diagnostics, object.header().fileOffset());
                    if (inflated.length == 0 && state.carry.length == 0) {
                        break;
                    }
                    byte[] stream = state.carry.length == 0
                            ? inflated
                            : concat(state.carry, inflated);
                    state.carry = EMPTY_CARRY;
                    if (stream.length == 0) {
                        break;
                    }
                    try {
                        walk(new BlfBufferInput(stream, stream.length), depth + 1, context, batch,
                                frameConsumer, diagnostics, state);
                    } catch (BlfContainerSplit split) {
                        int start = (int) split.offset();
                        if (start < 0 || start > stream.length) {
                            throw new ParserException(
                                    "BLF container split at invalid offset " + split.offset(), split);
                        }
                        int pending = stream.length - start;
                        if (pending > BlfConstants.MAX_OBJECT_SIZE) {
                            throw new ParserException(
                                    "BLF object continues for more than " + BlfConstants.MAX_OBJECT_SIZE
                                            + " bytes");
                        }
                        state.carry = pending == 0
                                ? EMPTY_CARRY
                                : Arrays.copyOfRange(stream, start, stream.length);
                    }
                }
                case CAN_DATA, CAN_FD_DATA -> {
                    CanonicalCanFrame frame = BlfCanDecoder.decode(object, state.sequence, diagnostics);
                    if (frame == null) {
                        diagnostics.noteNonData(object.header().objectType());
                        break;
                    }
                    if (kind == BlfObjectClass.CAN_FD_DATA) {
                        diagnostics.noteCanFd();
                    } else {
                        diagnostics.noteCan();
                    }
                    diagnostics.noteFrame();
                    state.channels.add(frame.channel());
                    state.identities.add((frame.extended() ? 1L << 40 : 0L)
                            | (frame.canId() << 8)
                            | (frame.channel() & 0xFFL));
                    if (state.firstTs == Long.MIN_VALUE) {
                        state.firstTs = frame.timestampNanos();
                    }
                    state.lastTs = frame.timestampNanos();
                    state.sequence++;
                    batch.add(frame);
                    if (batch.size() >= context.batchSize()) {
                        flush(batch, frameConsumer);
                    }
                }
                case SKIP_NON_DATA -> diagnostics.noteNonData(object.header().objectType());
                case UNKNOWN -> diagnostics.noteUnknown(object.header().objectType());
            }
        }
    }

    private static void skipZeroPadding(BlfInput in) throws IOException {
        if (in.remaining() == 0) {
            return;
        }
        int want = (int) Math.min(7L, in.remaining());
        byte[] ahead = in.peek(want);
        if (ahead.length >= 4 && isLobj(ahead, 0)) {
            return;
        }
        int zeros = 0;
        while (zeros < ahead.length && zeros < 3 && ahead[zeros] == 0) {
            zeros++;
        }
        if (zeros == 0) {
            return;
        }
        boolean followedByObject = zeros + 4 <= ahead.length && isLobj(ahead, zeros);
        boolean endOfBuffer = zeros == ahead.length;
        if (followedByObject || endOfBuffer) {
            in.skip(zeros);
        }
    }

    private static boolean isLobj(byte[] data, int offset) {
        return data[offset] == 'L'
                && data[offset + 1] == 'O'
                && data[offset + 2] == 'B'
                && data[offset + 3] == 'J';
    }

    private static void flush(List<CanonicalCanFrame> batch, CanFrameConsumer consumer) throws ParserException {
        if (batch.isEmpty()) {
            return;
        }
        try {
            consumer.onBatch(List.copyOf(batch));
        } catch (ParserException e) {
            throw e;
        } catch (Exception e) {
            throw new ParserException("BLF frame consumer failed: " + e.getMessage(), e);
        }
        batch.clear();
    }

    private Path requireSupportedPath(Path path) throws ParserException {
        if (path == null) {
            throw new ParserException("BLF path must not be null");
        }
        if (!supports(path) && !hasBlfExtension(path)) {
            throw new ParserException("Not a BLF path: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new ParserException("BLF file could not be parsed: not a readable file: " + path);
        }
        return path;
    }

    private static boolean hasBlfExtension(Path path) {
        Path name = path.getFileName();
        return name != null && name.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".blf");
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] out = new byte[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }

    private static final byte[] EMPTY_CARRY = new byte[0];

    private static final class WalkState {
        long sequence;
        long firstTs = Long.MIN_VALUE;
        long lastTs = Long.MIN_VALUE;
        byte[] carry = EMPTY_CARRY;
        final Set<Integer> channels = new HashSet<>();
        final Set<Long> identities = new HashSet<>();
    }
}
