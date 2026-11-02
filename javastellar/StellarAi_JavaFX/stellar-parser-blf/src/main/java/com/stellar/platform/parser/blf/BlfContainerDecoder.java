package com.stellar.platform.parser.blf;

import com.stellar.platform.parser.spi.ParserException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

final class BlfContainerDecoder {

    private BlfContainerDecoder() {
    }

    static byte[] decompress(byte[] payload, BlfDiagnostics diagnostics, long offset) throws ParserException {
        if (payload == null || payload.length < 16) {
            diagnostics.malformed("LOG_CONTAINER payload shorter than 16 bytes", offset);
            return new byte[0];
        }
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int method = Short.toUnsignedInt(buf.getShort());
        buf.getShort();
        buf.getInt();
        long uncompressedLong = Integer.toUnsignedLong(buf.getInt());
        buf.getInt();
        int dataOffset = 16;
        int compressedLen = payload.length - dataOffset;
        if (compressedLen < 0) {
            diagnostics.malformed("LOG_CONTAINER has no compressed payload", offset);
            return new byte[0];
        }
        if (uncompressedLong > BlfConstants.MAX_CONTAINER_UNCOMPRESSED) {
            throw new ParserException("BLF container uncompressed size exceeds " + BlfConstants.MAX_CONTAINER_UNCOMPRESSED
                    + " at offset " + offset);
        }
        int uncompressed = (int) uncompressedLong;
        if (method == BlfConstants.COMPRESSION_NONE) {
            int copy = Math.min(compressedLen, uncompressed == 0 ? compressedLen : uncompressed);
            byte[] out = new byte[copy];
            System.arraycopy(payload, dataOffset, out, 0, copy);
            diagnostics.noteContainer(compressedLen, copy);
            return out;
        }
        if (method != BlfConstants.COMPRESSION_ZLIB) {
            diagnostics.containerWarning("Unsupported BLF compression method " + method, offset);
            return new byte[0];
        }
        if (uncompressed <= 0) {
            diagnostics.malformed("LOG_CONTAINER zlib size is zero", offset);
            return new byte[0];
        }
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(payload, dataOffset, compressedLen);
            byte[] out = new byte[Math.max(uncompressed, 1)];
            int n = 0;
            while (!inflater.finished()) {
                if (n == out.length) {
                    if (out.length >= BlfConstants.MAX_CONTAINER_UNCOMPRESSED) {
                        break;
                    }
                    int grown = (int) Math.min(
                            (long) out.length * 2,
                            BlfConstants.MAX_CONTAINER_UNCOMPRESSED);
                    out = java.util.Arrays.copyOf(out, Math.max(grown, n + 1));
                }
                int got = inflater.inflate(out, n, out.length - n);
                if (got <= 0) {
                    break;
                }
                n += got;
            }
            if (n <= 0) {
                diagnostics.malformed("LOG_CONTAINER zlib produced no bytes", offset);
                return new byte[0];
            }
            diagnostics.noteContainer(compressedLen, n);
            if (n == out.length) {
                return out;
            }
            return java.util.Arrays.copyOf(out, n);
        } catch (DataFormatException ex) {
            diagnostics.malformed("Corrupt zlib LOG_CONTAINER: " + ex.getMessage(), offset);
            return new byte[0];
        } finally {
            inflater.end();
        }
    }
}
