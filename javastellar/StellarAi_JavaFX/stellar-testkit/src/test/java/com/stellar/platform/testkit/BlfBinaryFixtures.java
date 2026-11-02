package com.stellar.platform.testkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.Deflater;

/** Minimal Vector-style BLF writer used by pipeline tests. */
final class BlfBinaryFixtures {

    private static final int HEADER = 144;
    private static final int NANS = 0x00000002;
    private static final int CAN_MESSAGE = 1;
    private static final int LOG_CONTAINER = 10;

    private BlfBinaryFixtures() {
    }

    static Path classic(Path path, int channel, int canId, byte[] data, long timestampNanos) throws IOException {
        byte[] inner = object(CAN_MESSAGE, 1, NANS, timestampNanos, classicPayload(channel, canId, data));
        return write(path, object(LOG_CONTAINER, 1, 0, 0, container(2, inner)));
    }

    static Path classics(Path path, List<ClassicFrame> frames) throws IOException {
        byte[][] parts = new byte[frames.size()][];
        for (int i = 0; i < frames.size(); i++) {
            ClassicFrame frame = frames.get(i);
            parts[i] = object(
                    CAN_MESSAGE, 1, NANS, frame.timestampNanos(),
                    classicPayload(frame.channel(), frame.canId(), frame.data()));
        }
        return write(path, object(LOG_CONTAINER, 1, 0, 0, container(2, concat(parts))));
    }

    record ClassicFrame(int channel, int canId, byte[] data, long timestampNanos) {
    }

    static Path twoClassic(
            Path path,
            int channelA,
            int idA,
            byte[] dataA,
            long tsA,
            int channelB,
            int idB,
            byte[] dataB,
            long tsB
    ) throws IOException {
        byte[] inner = concat(
                object(CAN_MESSAGE, 1, NANS, tsA, classicPayload(channelA, idA, dataA)),
                object(CAN_MESSAGE, 1, NANS, tsB, classicPayload(channelB, idB, dataB)));
        return write(path, object(LOG_CONTAINER, 1, 0, 0, container(2, inner)));
    }

    static byte[] enginePayload(double rpm, int tempC, int flags) {
        int speedRaw = (int) Math.round(rpm / 0.25);
        return new byte[]{
                (byte) (speedRaw & 0xFF),
                (byte) ((speedRaw >> 8) & 0xFF),
                (byte) ((tempC + 40) & 0xFF),
                (byte) flags,
                0, 0, 0, 0
        };
    }

    private static Path write(Path path, byte[] body) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'L', 'O', 'G', 'G'});
        header.putInt(HEADER);
        header.putInt(0);
        header.put(new byte[]{5, 0, 0, 0, 1, 3, 0, 0});
        header.putLong(HEADER + body.length);
        header.putLong(HEADER + body.length);
        header.putInt(1);
        header.putInt(0);
        header.putShort((short) 2026);
        header.putShort((short) 1);
        header.putShort((short) 4);
        header.putShort((short) 1);
        header.putShort((short) 12);
        header.putShort((short) 0);
        header.putShort((short) 0);
        header.putShort((short) 0);
        byte[] all = new byte[HEADER + body.length];
        System.arraycopy(header.array(), 0, all, 0, HEADER);
        System.arraycopy(body, 0, all, HEADER, body.length);
        Files.createDirectories(path.getParent());
        Files.write(path, all);
        return path;
    }

    private static byte[] object(int type, int headerVersion, int flags, long timestamp, byte[] payload) {
        int extra = headerVersion >= 2 ? 24 : 16;
        int headerSize = 16 + extra;
        int objectSize = headerSize + payload.length;
        int aligned = (objectSize + 3) & ~3;
        ByteBuffer buf = ByteBuffer.allocate(aligned).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(new byte[]{'L', 'O', 'B', 'J'});
        buf.putShort((short) headerSize);
        buf.putShort((short) headerVersion);
        buf.putInt(objectSize);
        buf.putInt(type);
        buf.putInt(flags);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putLong(timestamp);
        if (headerVersion >= 2) {
            buf.putLong(timestamp);
        }
        buf.put(payload);
        return buf.array();
    }

    private static byte[] classicPayload(int channel, int canId, byte[] data) {
        ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) channel);
        buf.put((byte) 0);
        buf.put((byte) data.length);
        buf.putInt(canId);
        byte[] padded = new byte[8];
        System.arraycopy(data, 0, padded, 0, Math.min(8, data.length));
        buf.put(padded);
        return buf.array();
    }

    private static byte[] container(int method, byte[] inner) {
        byte[] stored = zlib(inner);
        ByteBuffer buf = ByteBuffer.allocate(16 + stored.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) method);
        buf.putShort((short) 0);
        buf.putInt(0);
        buf.putInt(inner.length);
        buf.putInt(0);
        buf.put(stored);
        return buf.array();
    }

    private static byte[] zlib(byte[] input) {
        Deflater deflater = new Deflater();
        deflater.setInput(input);
        deflater.finish();
        byte[] chunk = new byte[Math.max(64, input.length + 64)];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (!deflater.finished()) {
            int n = deflater.deflate(chunk);
            out.write(chunk, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
