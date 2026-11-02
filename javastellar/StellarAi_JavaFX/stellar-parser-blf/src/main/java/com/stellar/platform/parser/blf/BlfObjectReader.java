package com.stellar.platform.parser.blf;

import com.stellar.platform.parser.spi.ParserException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class BlfObjectReader {

    private BlfObjectReader() {
    }

    static BlfObject next(BlfInput in, BlfDiagnostics diagnostics) throws IOException, ParserException {
        if (!in.hasRemaining(BlfConstants.OBJECT_BASE_SIZE)) {
            if (in.remaining() == 0) {
                return null;
            }
            if (in instanceof BlfBufferInput) {
                throw new BlfContainerSplit(in.position());
            }
            throw new ParserException("Truncated BLF object header at offset " + in.position());
        }
        long fileOffset = in.position();
        byte[] base = new byte[BlfConstants.OBJECT_BASE_SIZE];
        in.readFully(base, 0, base.length);
        ByteBuffer buf = ByteBuffer.wrap(base).order(ByteOrder.LITTLE_ENDIAN);
        int signature = buf.getInt();
        if (signature != BlfConstants.OBJECT_SIGNATURE) {
            String window = in instanceof BlfBufferInput buffer
                    ? " window=" + buffer.windowHex(fileOffset, 8, 16)
                    : " bytes=" + hex(base);
            throw new ParserException("Invalid BLF object signature at offset " + fileOffset
                    + " (structural integrity lost)" + window);
        }
        int headerSize = Short.toUnsignedInt(buf.getShort());
        int headerVersion = Short.toUnsignedInt(buf.getShort());
        long objectSizeLong = Integer.toUnsignedLong(buf.getInt());
        int objectType = buf.getInt();
        if (headerSize < BlfConstants.OBJECT_BASE_SIZE || headerSize > BlfConstants.MAX_OBJECT_SIZE) {
            throw new ParserException("Impossible BLF object header size " + headerSize + " at offset " + fileOffset);
        }
        if (objectSizeLong < headerSize || objectSizeLong > BlfConstants.MAX_OBJECT_SIZE) {
            throw new ParserException("Impossible BLF object size " + objectSizeLong + " at offset " + fileOffset);
        }
        int objectSize = (int) objectSizeLong;
        int extraHeader = headerSize - BlfConstants.OBJECT_BASE_SIZE;
        byte[] extra = extraHeader == 0 ? new byte[0] : new byte[extraHeader];
        if (extraHeader > 0) {
            if (!in.hasRemaining(extraHeader)) {
                if (in instanceof BlfBufferInput && in.remaining() < extraHeader) {
                    throw new BlfContainerSplit(fileOffset);
                }
                throw new ParserException("Truncated BLF object header extension at offset " + fileOffset);
            }
            in.readFully(extra, 0, extraHeader);
        }
        int flags = 0;
        long timestamp = 0L;
        if (headerVersion == 2 && extra.length >= 24) {
            ByteBuffer eh = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN);
            flags = eh.getInt();
            eh.position(8);
            timestamp = eh.getLong();
        } else if (extra.length >= 16) {
            ByteBuffer eh = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN);
            flags = eh.getInt();
            eh.position(8);
            timestamp = eh.getLong();
        }
        int payloadLen = objectSize - headerSize;
        if (payloadLen < 0) {
            throw new ParserException("Negative BLF payload at offset " + fileOffset);
        }
        byte[] payload = payloadLen == 0 ? new byte[0] : new byte[payloadLen];
        if (payloadLen > 0) {
            if (!in.hasRemaining(payloadLen)) {
                if (in instanceof BlfBufferInput) {
                    throw new BlfContainerSplit(fileOffset);
                }
                throw new ParserException("Truncated BLF object payload at offset " + fileOffset
                        + " headerSize=" + headerSize
                        + " objectSize=" + objectSize
                        + " payloadLen=" + payloadLen
                        + " remaining=" + in.remaining()
                        + " type=" + objectType);
            }
            in.readFully(payload, 0, payloadLen);
        }
        return new BlfObject(
                new BlfObjectHeader(headerSize, headerVersion, objectSize, objectType, flags, timestamp, fileOffset),
                payload
        );
    }

    private static String hex(byte[] base) {
        StringBuilder sb = new StringBuilder(base.length * 2);
        for (byte b : base) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    record BlfObject(BlfObjectHeader header, byte[] payload) {
    }
}
