package com.stellar.platform.parser.blf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;

/**
 * Deterministic Vector-style BLF writer for tests. Layout follows Toby Lorenz Vector BLF
 * (file header) and python-can/Vector object payloads.
 */
final class BlfTestFiles {

    static final int FILE_HEADER_SIZE = 144;
    static final int FLAG_TIME_ONE_NANS = 0x00000002;
    static final int FLAG_TIME_TEN_MICS = 0x00000001;
    static final int CAN_EXT = 0x80000000;

    private BlfTestFiles() {
    }

    static Path write(Path path, byte[] bodyAfterHeader, int objectCount) throws IOException {
        byte[] header = fileHeader(objectCount, FILE_HEADER_SIZE + bodyAfterHeader.length, 2026, 1, 1, 12, 0, 0, 0);
        byte[] all = new byte[header.length + bodyAfterHeader.length];
        System.arraycopy(header, 0, all, 0, header.length);
        System.arraycopy(bodyAfterHeader, 0, all, header.length, bodyAfterHeader.length);
        Files.createDirectories(path.getParent());
        Files.write(path, all);
        return path;
    }

    static byte[] fileHeader(
            int objectCount,
            long fileSize,
            int year,
            int month,
            int day,
            int hour,
            int minute,
            int second,
            int millis
    ) {
        ByteBuffer buf = ByteBuffer.allocate(FILE_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'L').put((byte) 'O').put((byte) 'G').put((byte) 'G');
        buf.putInt(FILE_HEADER_SIZE);
        buf.put((byte) 5);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 1);
        buf.put((byte) 3);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.putLong(fileSize);
        buf.putLong(fileSize);
        buf.putInt(objectCount);
        buf.putInt(0);
        putSystemTime(buf, year, month, day, hour, minute, second, millis);
        putSystemTime(buf, year, month, day, hour, minute, second, millis);
        return buf.array();
    }

    static byte[] object(int type, int headerVersion, int flags, long timestamp, byte[] payload) {
        int extra = headerVersion >= 2 ? 24 : 16;
        int headerSize = 16 + extra;
        int objectSize = headerSize + payload.length;
        int aligned = (objectSize + 3) & ~3;
        ByteBuffer buf = ByteBuffer.allocate(aligned).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'L').put((byte) 'O').put((byte) 'B').put((byte) 'J');
        buf.putShort((short) headerSize);
        buf.putShort((short) headerVersion);
        buf.putInt(objectSize);
        buf.putInt(type);
        buf.putInt(flags);
        if (headerVersion >= 2) {
            buf.put((byte) 0);
            buf.put((byte) 0);
            buf.putShort((short) 0);
        } else {
            buf.putShort((short) 0);
            buf.putShort((short) 0);
        }
        buf.putLong(timestamp);
        if (headerVersion >= 2) {
            buf.putLong(timestamp);
        }
        buf.put(payload);
        return buf.array();
    }

    static byte[] classicCan(int channel, int flags, int rawId, byte[] data) {
        ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) channel);
        buf.put((byte) flags);
        buf.put((byte) data.length);
        buf.putInt(rawId);
        byte[] padded = new byte[8];
        System.arraycopy(data, 0, padded, 0, Math.min(8, data.length));
        buf.put(padded);
        return buf.array();
    }

    static byte[] classicCan2(int channel, int flags, int rawId, byte[] data) {
        byte[] first = classicCan(channel, flags, rawId, data);
        ByteBuffer buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(first);
        buf.putInt(data.length);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        return buf.array();
    }

    static byte[] canFd100(int channel, int flags, int rawId, int codedDlc, byte[] data) {
        ByteBuffer buf = ByteBuffer.allocate(20 + 64).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) channel);
        buf.put((byte) flags);
        buf.put((byte) codedDlc);
        buf.putInt(rawId);
        buf.putInt(data.length);
        buf.put((byte) 0);
        buf.put((byte) 0x01); // EDL
        buf.put((byte) data.length);
        buf.put(new byte[5]);
        byte[] padded = new byte[64];
        System.arraycopy(data, 0, padded, 0, Math.min(64, data.length));
        buf.put(padded);
        return buf.array();
    }

    static byte[] canFd64(int channel, int dir, int rawId, int codedDlc, byte[] data) {
        ByteBuffer buf = ByteBuffer.allocate(40 + 64).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) channel);
        buf.put((byte) codedDlc);
        buf.put((byte) data.length);
        buf.put((byte) 0);
        buf.putInt(rawId);
        buf.putInt(data.length);
        buf.putInt(0x1000); // FD flag used by python-can
        buf.putInt(0);
        buf.putInt(0);
        buf.putInt(0);
        buf.putInt(0);
        buf.putShort((short) 0);
        buf.put((byte) dir);
        buf.put((byte) 0);
        buf.putInt(0);
        byte[] padded = new byte[64];
        System.arraycopy(data, 0, padded, 0, Math.min(64, data.length));
        buf.put(padded);
        return buf.array();
    }

    static byte[] logContainer(int method, byte[] inner) {
        byte[] stored = method == BlfConstants.COMPRESSION_ZLIB ? zlib(inner) : inner;
        ByteBuffer buf = ByteBuffer.allocate(16 + stored.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) method);
        buf.putShort((short) 0);
        buf.putInt(0);
        buf.putInt(inner.length);
        buf.putInt(0);
        buf.put(stored);
        return buf.array();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static byte[] zlib(byte[] input) {
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

    static Path goldenClassic(Path path) throws IOException {
        byte[] inner = concat(
                object(BlfConstants.TYPE_CAN_MESSAGE, 1, FLAG_TIME_ONE_NANS, 1_000_000L,
                        classicCan(1, 0, 0x123, new byte[]{0x11, 0x22})),
                object(BlfConstants.TYPE_CAN_MESSAGE, 1, FLAG_TIME_ONE_NANS, 2_000_000L,
                        classicCan(2, 0, 0x123, new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC})),
                object(BlfConstants.TYPE_CAN_MESSAGE2, 1, FLAG_TIME_ONE_NANS, 3_000_000L,
                        classicCan2(1, 0, CAN_EXT | 0x18DAF110, new byte[]{1, 2, 3, 4, 5, 6, 7, 8})),
                object(BlfConstants.TYPE_GPS_EVENT, 1, FLAG_TIME_ONE_NANS, 4_000_000L, new byte[16]),
                object(BlfConstants.TYPE_APP_TEXT, 1, FLAG_TIME_ONE_NANS, 5_000_000L, new byte[8])
        );
        byte[] fileObjects = object(BlfConstants.TYPE_LOG_CONTAINER, 1, 0, 0,
                logContainer(BlfConstants.COMPRESSION_ZLIB, inner));
        return write(path, fileObjects, 1);
    }

    static Path goldenEngine(Path path) throws IOException {
        return singleClassic(path, 0, 0x100, enginePayload(2500.0, 50, 1), 1_000_000_000L);
    }

    static Path muxPair(Path path) throws IOException {
        byte[] inner = concat(
                object(BlfConstants.TYPE_CAN_MESSAGE, 1, FLAG_TIME_ONE_NANS, 10_000_000L,
                        classicCan(1, 0, 0x600, new byte[]{0, 40})),
                object(BlfConstants.TYPE_CAN_MESSAGE, 1, FLAG_TIME_ONE_NANS, 20_000_000L,
                        classicCan(1, 0, 0x600, new byte[]{1, (byte) 0xC8}))
        );
        return write(path, object(BlfConstants.TYPE_LOG_CONTAINER, 1, 0, 0,
                logContainer(BlfConstants.COMPRESSION_NONE, inner)), 1);
    }

    static Path multiChannelSameId(Path path) throws IOException {
        byte[] inner = concat(
                object(BlfConstants.TYPE_CAN_MESSAGE, 1, FLAG_TIME_ONE_NANS, 100L,
                        classicCan(1, 0, 0x123, new byte[]{1})),
                object(BlfConstants.TYPE_CAN_MESSAGE, 1, FLAG_TIME_ONE_NANS, 200L,
                        classicCan(2, 0, 0x123, new byte[]{2}))
        );
        return write(path, object(BlfConstants.TYPE_LOG_CONTAINER, 1, 0, 0,
                logContainer(BlfConstants.COMPRESSION_ZLIB, inner)), 1);
    }

    static Path canFdMixed(Path path) throws IOException {
        byte[] fd16 = new byte[16];
        for (int i = 0; i < fd16.length; i++) {
            fd16[i] = (byte) (0xA0 + i);
        }
        byte[] fd64 = new byte[64];
        for (int i = 0; i < fd64.length; i++) {
            fd64[i] = (byte) i;
        }
        byte[] inner = concat(
                object(BlfConstants.TYPE_CAN_FD_MESSAGE, 1, FLAG_TIME_ONE_NANS, 1_000L,
                        canFd100(1, 0, 0x700, 10, fd16)),
                object(BlfConstants.TYPE_CAN_FD_MESSAGE_64, 2, FLAG_TIME_ONE_NANS, 2_000L,
                        canFd64(3, 0, CAN_EXT | 0x1ABCDEF, 15, fd64))
        );
        return write(path, object(BlfConstants.TYPE_LOG_CONTAINER, 1, 0, 0,
                logContainer(BlfConstants.COMPRESSION_ZLIB, inner)), 1);
    }

    static Path tenMicroTimestamps(Path path) throws IOException {
        byte[] inner = object(BlfConstants.TYPE_CAN_MESSAGE, 1, FLAG_TIME_TEN_MICS, 100L,
                classicCan(1, 0, 0x111, new byte[]{0x01}));
        return write(path, object(BlfConstants.TYPE_LOG_CONTAINER, 1, 0, 0,
                logContainer(BlfConstants.COMPRESSION_NONE, inner)), 1);
    }

    static Path rtrAndUnknown(Path path) throws IOException {
        byte[] inner = concat(
                object(BlfConstants.TYPE_CAN_MESSAGE, 1, FLAG_TIME_ONE_NANS, 1L,
                        classicCan(1, 0x80, 0x123, new byte[0])),
                object(999, 1, FLAG_TIME_ONE_NANS, 2L, new byte[8]),
                object(BlfConstants.TYPE_CAN_MESSAGE, 1, FLAG_TIME_ONE_NANS, 3L,
                        classicCan(1, 0, 0x124, new byte[]{0x09}))
        );
        return write(path, object(BlfConstants.TYPE_LOG_CONTAINER, 1, 0, 0,
                logContainer(BlfConstants.COMPRESSION_NONE, inner)), 1);
    }

    static Path manyClassic(Path path, int frames) throws IOException {
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        for (int i = 0; i < frames; i++) {
            inner.write(object(BlfConstants.TYPE_CAN_MESSAGE, 1, FLAG_TIME_ONE_NANS, i * 1000L,
                    classicCan(1, 0, 0x100 + (i % 16), new byte[]{(byte) i, 0, 0, 0})));
        }
        return write(path, object(BlfConstants.TYPE_LOG_CONTAINER, 1, 0, 0,
                logContainer(BlfConstants.COMPRESSION_ZLIB, inner.toByteArray())), 1);
    }

    static Path singleClassic(Path path, int channel, int canId, byte[] data, long timestampNanos)
            throws IOException {
        byte[] inner = object(BlfConstants.TYPE_CAN_MESSAGE, 1, FLAG_TIME_ONE_NANS, timestampNanos,
                classicCan(channel, 0, canId, data));
        return write(path, object(BlfConstants.TYPE_LOG_CONTAINER, 1, 0, 0,
                logContainer(BlfConstants.COMPRESSION_ZLIB, inner)), 1);
    }

    static byte[] enginePayload(double rpm, int tempC, int flags) {
        int speedRaw = (int) Math.round(rpm / 0.25);
        return new byte[]{
                (byte) (speedRaw & 0xFF),
                (byte) ((speedRaw >> 8) & 0xFF),
                (byte) ((tempC + 40) & 0xFF),
                (byte) (flags & 0xFF),
                0, 0, 0, 0
        };
    }

    private static void putSystemTime(
            ByteBuffer buf,
            int year,
            int month,
            int day,
            int hour,
            int minute,
            int second,
            int millis
    ) {
        buf.putShort((short) year);
        buf.putShort((short) month);
        buf.putShort((short) 4);
        buf.putShort((short) day);
        buf.putShort((short) hour);
        buf.putShort((short) minute);
        buf.putShort((short) second);
        buf.putShort((short) millis);
    }
}
