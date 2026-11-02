package com.stellar.platform.parser.mf4.support;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Programmatic non-proprietary MDF4 fixture builder for tests.
 */
public final class SyntheticMdf4Builder implements AutoCloseable {

    private static final ByteOrder ORDER = ByteOrder.LITTLE_ENDIAN;

    private final Path path;
    private final FileChannel channel;
    private long nextOffset;

    private SyntheticMdf4Builder(Path path, FileChannel channel) {
        this.path = path;
        this.channel = channel;
        this.nextOffset = 0L;
    }

    public static SyntheticMdf4Builder create(Path path) throws IOException {
        Files.createDirectories(path.getParent() == null ? Path.of(".") : path.getParent());
        FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        );
        return new SyntheticMdf4Builder(path, channel);
    }

    public Path path() {
        return path;
    }

    public long size() throws IOException {
        return channel.size();
    }

    public SyntheticMdf4Builder writeIdBlock(String versionText, int versionCode, int unfinFlags) throws IOException {
        return writeIdBlock("MDF     ", versionText, versionCode, unfinFlags);
    }

    /**
     * @param signature8 exactly 8 ASCII bytes for IDBLOCK file id (e.g. {@code MDF     } or {@code UnFinMF })
     */
    public SyntheticMdf4Builder writeIdBlock(
            String signature8,
            String versionText,
            int versionCode,
            int unfinFlags
    ) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(64).order(ORDER);
        putAscii(buf, padRight(signature8, 8), 8);
        putAscii(buf, padRight(versionText, 8), 8);
        putAscii(buf, "stellar ", 8);
        buf.putInt(0);
        buf.putShort((short) versionCode);
        buf.position(60);
        buf.putShort((short) unfinFlags);
        buf.putShort((short) 0);
        writeAt(0, buf.array());
        nextOffset = Math.max(nextOffset, 64L);
        return this;
    }

    public long writeBlock(String id, long[] links, byte[] data) throws IOException {
        long address = align8(nextOffset);
        if (address < 64 && !"ID".equals(id)) {
            address = 64;
        }
        byte[] payload = data == null ? new byte[0] : data;
        long linkCount = links == null ? 0 : links.length;
        long length = 24L + linkCount * 8L + payload.length;

        ByteBuffer buf = ByteBuffer.allocate((int) length).order(ORDER);
        putAscii(buf, id, 4);
        buf.putInt(0);
        buf.putLong(length);
        buf.putLong(linkCount);
        if (links != null) {
            for (long link : links) {
                buf.putLong(link);
            }
        }
        buf.put(payload);
        writeAt(address, buf.array());
        // ASAM MDF 4: subsequent blocks start at 8-byte aligned addresses.
        nextOffset = align8(address + length);
        return address;
    }

    private static long align8(long offset) {
        long rem = offset & 7L;
        return rem == 0L ? offset : offset + (8L - rem);
    }

    public long writeHd(long dgFirst, long fhFirst, long mdComment) throws IOException {
        return writeHd(dgFirst, fhFirst, mdComment, 0L, (short) 0, (short) 0, 0);
    }

    /**
     * HD time fields follow ASAM MDF 4. {@code startTimeNs} is the header's uint64
     * nanosecond count. {@code timeFlags} bit 0 is the local-time flag and bit 1 marks
     * the minute offsets as valid. The remaining 16 data bytes stay zero.
     */
    public long writeHd(
            long dgFirst,
            long fhFirst,
            long mdComment,
            long startTimeNs,
            short tzOffsetMin,
            short dstOffsetMin,
            int timeFlags
    ) throws IOException {
        nextOffset = 64L;
        ByteBuffer data = ByteBuffer.allocate(32).order(ORDER);
        data.putLong(startTimeNs);
        data.putShort(tzOffsetMin);
        data.putShort(dstOffsetMin);
        data.put((byte) timeFlags);
        return writeBlock("##HD", new long[]{dgFirst, fhFirst, 0, 0, 0, mdComment}, data.array());
    }

    public long writeDg(long nextDg, long firstCg, long dataLink, int recIdSize) throws IOException {
        byte[] data = new byte[8];
        data[0] = (byte) recIdSize;
        return writeBlock("##DG", new long[]{nextDg, firstCg, dataLink, 0}, data);
    }

    public long writeCg(
            long nextCg,
            long firstCn,
            long recordId,
            long cycleCount,
            int flags,
            int dataBytes,
            int invalBytes
    ) throws IOException {
        return writeCg(nextCg, firstCn, 0L, 0L, recordId, cycleCount, flags, dataBytes, invalBytes);
    }

    public long writeCg(
            long nextCg,
            long firstCn,
            long acqNameTx,
            long acqSourceSi,
            long recordId,
            long cycleCount,
            int flags,
            int dataBytes,
            int invalBytes
    ) throws IOException {
        ByteBuffer data = ByteBuffer.allocate(32).order(ORDER);
        data.putLong(recordId);
        data.putLong(cycleCount);
        data.putShort((short) flags);
        data.putShort((short) 0);
        data.putInt(0);
        data.putInt(dataBytes);
        data.putInt(invalBytes);
        return writeBlock("##CG", new long[]{nextCg, firstCn, acqNameTx, acqSourceSi, 0, 0}, data.array());
    }

    public long writeSi(long nameTx, long pathTx, int sourceType, int busType) throws IOException {
        ByteBuffer data = ByteBuffer.allocate(4).order(ORDER);
        data.put((byte) sourceType);
        data.put((byte) busType);
        data.put((byte) 0);
        data.put((byte) 0);
        return writeBlock("##SI", new long[]{nameTx, pathTx, 0}, data.array());
    }

    public long writeSd(byte[] payload) throws IOException {
        return writeBlock("##SD", new long[0], payload == null ? new byte[0] : payload);
    }

    public long writeCa(
            long compositionLink,
            int type,
            int storage,
            int ndim,
            int flags,
            long byteOffsetBase,
            long[] dimensions
    ) throws IOException {
        int dimCount = dimensions == null ? 0 : dimensions.length;
        ByteBuffer data = ByteBuffer.allocate(16 + dimCount * 8).order(ORDER);
        data.put((byte) type);
        data.put((byte) storage);
        data.putShort((short) ndim);
        data.putInt(flags);
        data.putInt((int) byteOffsetBase);
        data.putInt(0);
        if (dimensions != null) {
            for (long d : dimensions) {
                data.putLong(d);
            }
        }
        return writeBlock("##CA", new long[]{compositionLink, 0}, data.array());
    }

    public long writeCn(
            long nextCn,
            long nameTx,
            long unitMd,
            int channelType,
            int dataType,
            int byteOffset,
            int bitCount
    ) throws IOException {
        return writeCn(nextCn, nameTx, unitMd, channelType, 0, dataType, byteOffset, 0, bitCount, 0);
    }

    public long writeCn(
            long nextCn,
            long nameTx,
            long unitMd,
            int channelType,
            int syncType,
            int dataType,
            int byteOffset,
            int bitOffset,
            int bitCount,
            int invalidationBitPos
    ) throws IOException {
        return writeCn(
                nextCn, nameTx, unitMd, channelType, syncType, dataType,
                byteOffset, bitOffset, bitCount, invalidationBitPos, 0
        );
    }

    public long writeCn(
            long nextCn,
            long nameTx,
            long unitMd,
            int channelType,
            int syncType,
            int dataType,
            int byteOffset,
            int bitOffset,
            int bitCount,
            int invalidationBitPos,
            int flags
    ) throws IOException {
        return writeCn(
                nextCn, 0L, nameTx, unitMd, channelType, syncType, dataType,
                byteOffset, bitOffset, bitCount, invalidationBitPos, flags
        );
    }

    /**
     * Linear CC: physical = factor * raw + offset (ASAM cc_val[0]=offset, cc_val[1]=factor).
     */
    public long writeCcLinear(double offset, double factor) throws IOException {
        return writeCcLinear(offset, factor, 0L);
    }

    /**
     * Linear CC with optional unit TX/MD link (ASAM cc_md_unit at link index 1).
     */
    public long writeCcLinear(double offset, double factor, long unitTx) throws IOException {
        ByteBuffer data = ByteBuffer.allocate(40).order(ORDER);
        data.put((byte) 1); // linear
        data.put((byte) 0); // precision
        data.putShort((short) 0); // flags
        data.putShort((short) 0); // ref_count
        data.putShort((short) 2); // val_count
        data.putDouble(0.0); // phy min
        data.putDouble(0.0); // phy max
        data.putDouble(offset);
        data.putDouble(factor);
        return writeBlock("##CC", new long[]{0, unitTx, 0, 0}, data.array());
    }

    /**
     * ASAM CC block. {@code refLinks} are {@code cc_ref} entries and follow the four standard links.
     * Algebraic formulas use {@code refCount} 1 and the formula TX at {@code refLinks[0]}.
     */
    public long writeCc(int type, int refCount, long[] refLinks, double[] values) throws IOException {
        long[] refs = refLinks == null ? new long[0] : refLinks;
        long[] links = new long[4 + refs.length];
        System.arraycopy(refs, 0, links, 4, refs.length);
        double[] vals = values == null ? new double[0] : values;
        ByteBuffer data = ByteBuffer.allocate(24 + vals.length * 8).order(ORDER);
        data.put((byte) type);
        data.put((byte) 0);
        data.putShort((short) 0);
        data.putShort((short) refCount);
        data.putShort((short) vals.length);
        data.putDouble(0.0);
        data.putDouble(0.0);
        for (double value : vals) {
            data.putDouble(value);
        }
        return writeBlock("##CC", links, data.array());
    }

    public long writeCn(
            long nextCn,
            long compositionLink,
            long nameTx,
            long unitMd,
            long dataLink,
            long conversionLink,
            int channelType,
            int syncType,
            int dataType,
            int byteOffset,
            int bitOffset,
            int bitCount,
            int invalidationBitPos,
            int flags
    ) throws IOException {
        ByteBuffer data = ByteBuffer.allocate(32).order(ORDER);
        data.put((byte) channelType);
        data.put((byte) syncType);
        data.put((byte) dataType);
        data.put((byte) bitOffset);
        data.putInt(byteOffset);
        data.putInt(bitCount);
        data.putInt(flags);
        data.putInt(invalidationBitPos);
        data.put((byte) 0);
        data.put((byte) 0);
        data.put((byte) 0);
        data.put((byte) 0);
        data.putInt(0);
        return writeBlock(
                "##CN",
                new long[]{nextCn, compositionLink, nameTx, 0, conversionLink, dataLink, unitMd, 0},
                data.array()
        );
    }

    /**
     * Writes a CN with optional {@code cn_composition} and {@code cn_data} links.
     */
    public long writeCn(
            long nextCn,
            long compositionLink,
            long nameTx,
            long unitMd,
            long dataLink,
            int channelType,
            int syncType,
            int dataType,
            int byteOffset,
            int bitOffset,
            int bitCount,
            int invalidationBitPos,
            int flags
    ) throws IOException {
        return writeCn(
                nextCn, compositionLink, nameTx, unitMd, dataLink, 0L,
                channelType, syncType, dataType, byteOffset, bitOffset, bitCount, invalidationBitPos, flags
        );
    }

    public long writeCn(
            long nextCn,
            long compositionLink,
            long nameTx,
            long unitMd,
            int channelType,
            int syncType,
            int dataType,
            int byteOffset,
            int bitOffset,
            int bitCount,
            int invalidationBitPos,
            int flags
    ) throws IOException {
        return writeCn(
                nextCn, compositionLink, nameTx, unitMd, 0L,
                channelType, syncType, dataType, byteOffset, bitOffset, bitCount, invalidationBitPos, flags
        );
    }

    public long writeTx(String text) throws IOException {
        byte[] raw = (text + "\0").getBytes(StandardCharsets.UTF_8);
        return writeBlock("##TX", new long[0], raw);
    }

    public long writeDt(byte[] payload) throws IOException {
        return writeBlock("##DT", new long[0], payload == null ? new byte[0] : payload);
    }

    /**
     * Writes a {@code ##DT} block whose payload is streamed. Used by NFT generators so
     * multi-hundred-MB fixtures are not held as a single {@code byte[]}.
     */
    public long writeDtStreaming(long payloadBytes, DtPayloadWriter writer) throws IOException {
        if (payloadBytes < 0L) {
            throw new IllegalArgumentException("payloadBytes");
        }
        long address = align8(Math.max(nextOffset, 64L));
        long length = 24L + payloadBytes;
        ByteBuffer header = ByteBuffer.allocate(24).order(ORDER);
        putAscii(header, "##DT", 4);
        header.putInt(0);
        header.putLong(length);
        header.putLong(0);
        writeAt(address, header.array());
        writer.write(channel, address + 24L, payloadBytes);
        nextOffset = align8(address + length);
        return address;
    }

    @FunctionalInterface
    public interface DtPayloadWriter {
        void write(FileChannel channel, long payloadStart, long payloadBytes) throws IOException;
    }

    public long writeDz(String orgType, int zipType, long orgLen, byte[] compressed) throws IOException {
        return writeDz(orgType, zipType, 0, orgLen, compressed);
    }

    public long writeDz(String orgType, int zipType, int zipParameter, long orgLen, byte[] compressed)
            throws IOException {
        ByteBuffer meta = ByteBuffer.allocate(24 + (compressed == null ? 0 : compressed.length)).order(ORDER);
        putAscii(meta, padRight(orgType, 2), 2);
        meta.put((byte) zipType);
        meta.put((byte) 0);
        meta.putInt(zipParameter);
        meta.putLong(orgLen);
        meta.putLong(compressed == null ? 0 : compressed.length);
        if (compressed != null) {
            meta.put(compressed);
        }
        return writeBlock("##DZ", new long[0], meta.array());
    }

    public long writeDl(long nextDl, long[] dataLinks, int flags) throws IOException {
        List<Long> links = new ArrayList<>();
        links.add(nextDl);
        if (dataLinks != null) {
            for (long l : dataLinks) {
                links.add(l);
            }
        }
        ByteBuffer data = ByteBuffer.allocate(8).order(ORDER);
        data.put((byte) flags);
        data.put((byte) 0);
        data.putShort((short) 0);
        data.putInt(dataLinks == null ? 0 : dataLinks.length);
        long[] arr = links.stream().mapToLong(Long::longValue).toArray();
        return writeBlock("##DL", arr, data.array());
    }

    public long writeHl(long firstDl, int zipType) throws IOException {
        ByteBuffer data = ByteBuffer.allocate(4).order(ORDER);
        data.putShort((short) 0);
        data.put((byte) zipType);
        data.put((byte) 0);
        return writeBlock("##HL", new long[]{firstDl}, data.array());
    }

    public long writeUnknown(String id4, byte[] data) throws IOException {
        return writeBlock(id4, new long[0], data == null ? new byte[0] : data);
    }

    public void patchLong(long absoluteOffset, long value) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(8).order(ORDER);
        buf.putLong(value);
        writeAt(absoluteOffset, buf.array());
    }

    public void appendRaw(byte[] bytes) throws IOException {
        long at = channel.size();
        writeAt(at, bytes);
        nextOffset = Math.max(nextOffset, at + bytes.length);
    }

    public void truncateTo(long size) throws IOException {
        channel.truncate(size);
        nextOffset = Math.min(nextOffset, size);
    }

    /**
     * Rewrites a previously written block's declared length field (offset+8) without rewriting payload.
     */
    public void patchDeclaredLength(long blockAddress, long newLength) throws IOException {
        patchLong(blockAddress + 8, newLength);
    }

    public void patchLink(long blockAddress, int linkIndex, long value) throws IOException {
        patchLong(blockAddress + 24L + (long) linkIndex * 8L, value);
    }

    private void writeAt(long offset, byte[] bytes) throws IOException {
        channel.write(ByteBuffer.wrap(bytes), offset);
    }

    private static void putAscii(ByteBuffer buf, String text, int len) {
        byte[] raw = text.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < len; i++) {
            buf.put(i < raw.length ? raw[i] : (byte) ' ');
        }
    }

    private static String padRight(String text, int len) {
        if (text.length() >= len) {
            return text.substring(0, len);
        }
        return text + " ".repeat(len - text.length());
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
