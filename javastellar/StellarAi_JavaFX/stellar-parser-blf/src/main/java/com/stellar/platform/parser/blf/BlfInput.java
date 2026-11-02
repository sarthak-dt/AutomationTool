package com.stellar.platform.parser.blf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

interface BlfInput {

    long position();

    long remaining();

    default boolean hasRemaining(int bytes) {
        return remaining() >= bytes;
    }

    void readFully(byte[] dest, int offset, int length) throws IOException;

    void skip(int bytes) throws IOException;

    byte[] peek(int length) throws IOException;

    default int readIntLe() throws IOException {
        byte[] b = new byte[4];
        readFully(b, 0, 4);
        return (b[0] & 0xFF)
                | ((b[1] & 0xFF) << 8)
                | ((b[2] & 0xFF) << 16)
                | ((b[3] & 0xFF) << 24);
    }

    default int readUnsignedShortLe() throws IOException {
        byte[] b = new byte[2];
        readFully(b, 0, 2);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8);
    }

    default long readLongLe() throws IOException {
        byte[] b = new byte[8];
        readFully(b, 0, 8);
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        return buf.getLong();
    }
}
