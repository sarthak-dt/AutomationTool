package com.stellar.platform.parser.blf;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

final class BlfChannelInput implements BlfInput {

    private final FileChannel channel;
    private final long size;

    BlfChannelInput(FileChannel channel) throws IOException {
        this.channel = channel;
        this.size = channel.size();
    }

    @Override
    public long position() {
        try {
            return channel.position();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public long remaining() {
        return size - position();
    }

    @Override
    public void readFully(byte[] dest, int offset, int length) throws IOException {
        if (length < 0 || offset < 0 || offset + length > dest.length) {
            throw new IOException("invalid read range");
        }
        if (remaining() < length) {
            throw new EOFException("truncated BLF read");
        }
        ByteBuffer buf = ByteBuffer.wrap(dest, offset, length);
        while (buf.hasRemaining()) {
            int n = channel.read(buf);
            if (n < 0) {
                throw new EOFException("unexpected EOF");
            }
        }
    }

    @Override
    public void skip(int bytes) throws IOException {
        if (bytes < 0) {
            throw new IOException("negative skip");
        }
        if (remaining() < bytes) {
            throw new EOFException("truncated BLF skip");
        }
        channel.position(channel.position() + bytes);
    }

    @Override
    public byte[] peek(int length) throws IOException {
        int n = (int) Math.min(Math.max(0, length), remaining());
        byte[] out = new byte[n];
        if (n == 0) {
            return out;
        }
        long pos = channel.position();
        readFully(out, 0, n);
        channel.position(pos);
        return out;
    }
}
