package com.stellar.platform.parser.mf4.io;

import com.stellar.platform.parser.spi.ParserException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Random-access MF4 reader. Never loads or memory-maps the entire file.
 * MDF 4.x multi-byte integers are little-endian.
 */
public final class MdfFileAccess implements AutoCloseable {

    public static final ByteOrder MDF4_BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

    private final Path path;
    private final FileChannel channel;
    private final long fileSize;
    private long physicalReads;
    private long bytesRead;

    private MdfFileAccess(Path path, FileChannel channel, long fileSize) {
        this.path = path;
        this.channel = channel;
        this.fileSize = fileSize;
    }

    public static MdfFileAccess open(Path path) throws ParserException {
        Objects.requireNonNull(path, "path");
        try {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
            long size = channel.size();
            if (size < 0) {
                channel.close();
                throw new ParserException("Unable to determine physical size of: " + path);
            }
            return new MdfFileAccess(path, channel, size);
        } catch (IOException e) {
            throw new ParserException("Failed to open MF4 file: " + path, e);
        }
    }

    public Path path() {
        return path;
    }

    public long fileSize() {
        return fileSize;
    }

    public long physicalReadCount() {
        return physicalReads;
    }

    public long bytesRead() {
        return bytesRead;
    }

    public void resetIoCounters() {
        physicalReads = 0L;
        bytesRead = 0L;
    }

    public byte[] readFully(long offset, int length, String context) throws ParserException {
        if (length < 0) {
            throw new ParserException(context + ": negative length " + length);
        }
        byte[] dest = new byte[length];
        if (length > 0) {
            readInto(offset, dest, 0, length, context);
        }
        return dest;
    }

    /**
     * Fills {@code dest[destOffset .. destOffset+length)} from the file.
     * Physical {@link FileChannel} reads are counted for performance diagnostics.
     */
    public int readInto(long offset, byte[] dest, int destOffset, int length, String context)
            throws ParserException {
        if (length < 0) {
            throw new ParserException(context + ": negative length " + length);
        }
        if (dest == null || destOffset < 0 || destOffset + length > dest.length) {
            throw new ParserException(context + ": destination buffer out of range");
        }
        try {
            MdfSafeMath.requireInRange(offset, length, fileSize, context);
        } catch (RuntimeException e) {
            throw new ParserException(e.getMessage(), e);
        }
        if (length == 0) {
            return 0;
        }
        ByteBuffer buffer = ByteBuffer.wrap(dest, destOffset, length);
        try {
            int total = 0;
            while (total < length) {
                int n = channel.read(buffer, offset + total);
                physicalReads++;
                if (n < 0) {
                    throw new ParserException(context + ": premature EOF at offset " + (offset + total));
                }
                total += n;
                bytesRead += n;
            }
            return total;
        } catch (IOException e) {
            throw new ParserException(context + ": I/O failure at offset " + offset, e);
        }
    }

    public ByteBuffer readOrdered(long offset, int length, String context) throws ParserException {
        byte[] bytes = readFully(offset, length, context);
        return ByteBuffer.wrap(bytes).order(MDF4_BYTE_ORDER);
    }

    public long readUInt64(long offset, String context) throws ParserException {
        return readOrdered(offset, 8, context).getLong();
    }

    public int readUInt16(long offset, String context) throws ParserException {
        return Short.toUnsignedInt(readOrdered(offset, 2, context).getShort());
    }

    public int readUInt8(long offset, String context) throws ParserException {
        return Byte.toUnsignedInt(readFully(offset, 1, context)[0]);
    }

    public String readAscii(long offset, int length, String context) throws ParserException {
        byte[] raw = readFully(offset, length, context);
        int end = length;
        while (end > 0 && (raw[end - 1] == 0 || raw[end - 1] == ' ')) {
            end--;
        }
        return new String(raw, 0, end, StandardCharsets.US_ASCII);
    }

    public String readUtf8(long offset, int length, String context) throws ParserException {
        byte[] raw = readFully(offset, length, context);
        int end = length;
        while (end > 0 && raw[end - 1] == 0) {
            end--;
        }
        return new String(raw, 0, end, StandardCharsets.UTF_8);
    }

    public boolean canRead(long offset, long length) {
        try {
            MdfSafeMath.requireInRange(offset, length, fileSize, "probe");
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void close() throws ParserException {
        try {
            channel.close();
        } catch (IOException e) {
            throw new ParserException("Failed to close MF4 file: " + path, e);
        }
    }
}
