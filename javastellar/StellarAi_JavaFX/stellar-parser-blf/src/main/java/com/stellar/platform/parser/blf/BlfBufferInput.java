package com.stellar.platform.parser.blf;

import java.io.EOFException;
import java.io.IOException;

final class BlfBufferInput implements BlfInput {

    private final byte[] data;
    private final int limit;
    private int pos;

    BlfBufferInput(byte[] data, int length) {
        this.data = data;
        this.limit = length;
        this.pos = 0;
    }

    @Override
    public long position() {
        return pos;
    }

    @Override
    public long remaining() {
        return limit - pos;
    }

    @Override
    public void readFully(byte[] dest, int offset, int length) throws IOException {
        if (length < 0 || remaining() < length) {
            throw new EOFException("truncated BLF buffer");
        }
        System.arraycopy(data, pos, dest, offset, length);
        pos += length;
    }

    @Override
    public void skip(int bytes) throws IOException {
        if (bytes < 0 || remaining() < bytes) {
            throw new EOFException("truncated BLF buffer skip");
        }
        pos += bytes;
    }

    @Override
    public byte[] peek(int length) {
        int n = (int) Math.min(Math.max(0, length), remaining());
        byte[] out = new byte[n];
        System.arraycopy(data, pos, out, 0, n);
        return out;
    }

    String windowHex(long center, int before, int after) {
        int start = (int) Math.max(0, center - before);
        int end = (int) Math.min(limit, center + after);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (i == center) {
                sb.append('|');
            }
            int v = data[i] & 0xFF;
            sb.append(Character.forDigit(v >> 4, 16));
            sb.append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }
}
