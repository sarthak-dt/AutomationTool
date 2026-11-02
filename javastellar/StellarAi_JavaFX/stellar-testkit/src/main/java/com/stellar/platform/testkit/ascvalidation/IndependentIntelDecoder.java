package com.stellar.platform.testkit.ascvalidation;

/**
 * Independent Intel (little-endian) bit extractor for ground-truth signal values.
 * This is not {@code SignalBitExtractor} and is not used by production decode.
 * <p>
 * Bit 0 = LSB of payload byte 0. {@code startBit} is the signal LSB.
 * Physical = raw * factor + offset.
 */
public final class IndependentIntelDecoder {

    private IndependentIntelDecoder() {
    }

    public static long extractUnsigned(byte[] payload, int startBit, int bitLength) {
        if (payload == null) {
            throw new IllegalArgumentException("payload");
        }
        if (bitLength <= 0 || bitLength > 64) {
            throw new IllegalArgumentException("bitLength");
        }
        long value = 0L;
        for (int i = 0; i < bitLength; i++) {
            int bitPos = startBit + i;
            int byteIndex = bitPos / 8;
            int bitIndex = bitPos % 8;
            if (byteIndex < 0 || byteIndex >= payload.length) {
                throw new IllegalArgumentException("signal exceeds payload at byte " + byteIndex);
            }
            int bit = (payload[byteIndex] >> bitIndex) & 1;
            value |= ((long) bit) << i;
        }
        return value;
    }

    public static long extractSigned(byte[] payload, int startBit, int bitLength) {
        long unsigned = extractUnsigned(payload, startBit, bitLength);
        if (bitLength == 64) {
            return unsigned;
        }
        long signBit = 1L << (bitLength - 1);
        if ((unsigned & signBit) == 0) {
            return unsigned;
        }
        long mask = (1L << bitLength) - 1L;
        return unsigned | ~mask;
    }

    public static double toPhysical(long raw, double factor, double offset) {
        return raw * factor + offset;
    }
}
