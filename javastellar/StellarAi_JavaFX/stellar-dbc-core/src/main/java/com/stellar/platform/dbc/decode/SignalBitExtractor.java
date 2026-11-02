package com.stellar.platform.dbc.decode;

import com.stellar.platform.dbc.model.DbcByteOrder;
import com.stellar.platform.dbc.model.DbcValueType;
import com.stellar.platform.dbc.parse.DbcDiagnostic;
import com.stellar.platform.dbc.parse.DbcDiagnosticCode;
import com.stellar.platform.domain.CanonicalCanFrame;

/**
 * Generic DBC bit extraction for Intel and Motorola layouts.
 *
 * <p><b>Bit numbering convention (Vector / CANdb++ DBC):</b>
 * Bits are numbered across the payload with bit {@code 0} = LSB of byte {@code 0},
 * bit {@code 7} = MSB of byte {@code 0}, bit {@code 8} = LSB of byte {@code 1}, etc.
 *
 * <p><b>Intel (little-endian, {@code @1}):</b> {@code startBit} is the LSB of the signal.
 * Subsequent bits occupy {@code startBit+1 .. startBit+length-1}.
 *
 * <p><b>Motorola (big-endian, {@code @0}):</b> {@code startBit} is the MSB of the signal.
 * Extraction walks toward decreasing bit indices within a byte; when bit 0 of a byte is
 * consumed, the next bit is bit 7 of the following byte ({@code bitPos + 15}).
 * This is <em>not</em> simple byte reversal of an Intel layout.
 */
public final class SignalBitExtractor {

    private SignalBitExtractor() {
    }

    /**
     * Zero-copy extraction directly from an immutable canonical frame.
     */
    public static ExtractResult extract(
            CanonicalCanFrame frame,
            int startBit,
            int bitLength,
            DbcByteOrder byteOrder,
            DbcValueType valueType
    ) {
        if (frame == null) {
            return ExtractResult.error(DbcDiagnostic.of(
                    DbcDiagnosticCode.MALFORMED_FRAME, "Frame is null"));
        }
        if (bitLength <= 0 || bitLength > 64) {
            return ExtractResult.error(DbcDiagnostic.of(
                    DbcDiagnosticCode.INVALID_SIGNAL_DEFINITION,
                    "bitLength must be in 1..64, got " + bitLength));
        }
        if (startBit < 0) {
            return ExtractResult.error(DbcDiagnostic.of(
                    DbcDiagnosticCode.IMPOSSIBLE_START_BIT_OR_LENGTH,
                    "startBit must be >= 0, got " + startBit));
        }

        try {
            long unsigned = switch (byteOrder) {
                case INTEL -> extractIntel(frame, startBit, bitLength);
                case MOTOROLA -> extractMotorola(frame, startBit, bitLength);
            };
            long raw = applySignedness(unsigned, bitLength, valueType);
            return ExtractResult.ok(raw);
        } catch (IndexOutOfBoundsException e) {
            return ExtractResult.error(DbcDiagnostic.of(
                    DbcDiagnosticCode.INSUFFICIENT_PAYLOAD,
                    "Signal bits exceed payload: startBit=" + startBit
                            + ", length=" + bitLength + ", payloadBytes=" + frame.payloadLength()));
        } catch (IllegalArgumentException e) {
            return ExtractResult.error(DbcDiagnostic.of(
                    DbcDiagnosticCode.IMPOSSIBLE_START_BIT_OR_LENGTH, e.getMessage()));
        }
    }

    public static ExtractResult extract(
            byte[] payload,
            int startBit,
            int bitLength,
            DbcByteOrder byteOrder,
            DbcValueType valueType
    ) {
        if (payload == null) {
            return ExtractResult.error(DbcDiagnostic.of(
                    DbcDiagnosticCode.MALFORMED_FRAME, "Payload is null"));
        }
        if (bitLength <= 0 || bitLength > 64) {
            return ExtractResult.error(DbcDiagnostic.of(
                    DbcDiagnosticCode.INVALID_SIGNAL_DEFINITION,
                    "bitLength must be in 1..64, got " + bitLength));
        }
        if (startBit < 0) {
            return ExtractResult.error(DbcDiagnostic.of(
                    DbcDiagnosticCode.IMPOSSIBLE_START_BIT_OR_LENGTH,
                    "startBit must be >= 0, got " + startBit));
        }

        try {
            long unsigned = switch (byteOrder) {
                case INTEL -> extractIntel(payload, startBit, bitLength);
                case MOTOROLA -> extractMotorola(payload, startBit, bitLength);
            };
            long raw = applySignedness(unsigned, bitLength, valueType);
            return ExtractResult.ok(raw);
        } catch (IndexOutOfBoundsException e) {
            return ExtractResult.error(DbcDiagnostic.of(
                    DbcDiagnosticCode.INSUFFICIENT_PAYLOAD,
                    "Signal bits exceed payload: startBit=" + startBit
                            + ", length=" + bitLength + ", payloadBytes=" + payload.length));
        } catch (IllegalArgumentException e) {
            return ExtractResult.error(DbcDiagnostic.of(
                    DbcDiagnosticCode.IMPOSSIBLE_START_BIT_OR_LENGTH, e.getMessage()));
        }
    }

    static long extractIntel(byte[] payload, int startBit, int bitLength) {
        long value = 0L;
        for (int i = 0; i < bitLength; i++) {
            int bitPos = startBit + i;
            int byteIndex = bitPos / 8;
            int bitIndex = bitPos % 8;
            requireByte(payload, byteIndex);
            int bit = (payload[byteIndex] >> bitIndex) & 0x1;
            value |= ((long) bit) << i;
        }
        return value;
    }

    /**
     * Vector Motorola walk starting at MSB {@code startBit}.
     */
    static long extractMotorola(byte[] payload, int startBit, int bitLength) {
        long value = 0L;
        int bitPos = startBit;
        for (int i = 0; i < bitLength; i++) {
            int byteIndex = bitPos / 8;
            int bitIndex = bitPos % 8;
            requireByte(payload, byteIndex);
            int bit = (payload[byteIndex] >> bitIndex) & 0x1;
            value = (value << 1) | bit;
            if (bitIndex == 0) {
                bitPos += 15; // bit7 of next byte
            } else {
                bitPos -= 1;
            }
        }
        return value;
    }

    static long extractIntel(CanonicalCanFrame frame, int startBit, int bitLength) {
        long value = 0L;
        for (int i = 0; i < bitLength; i++) {
            int bitPos = startBit + i;
            int byteIndex = bitPos / 8;
            int bitIndex = bitPos % 8;
            requireByte(frame, byteIndex);
            int bit = (frame.payloadByteAt(byteIndex) >> bitIndex) & 0x1;
            value |= ((long) bit) << i;
        }
        return value;
    }

    static long extractMotorola(CanonicalCanFrame frame, int startBit, int bitLength) {
        long value = 0L;
        int bitPos = startBit;
        for (int i = 0; i < bitLength; i++) {
            int byteIndex = bitPos / 8;
            int bitIndex = bitPos % 8;
            requireByte(frame, byteIndex);
            int bit = (frame.payloadByteAt(byteIndex) >> bitIndex) & 0x1;
            value = (value << 1) | bit;
            if (bitIndex == 0) {
                bitPos += 15;
            } else {
                bitPos -= 1;
            }
        }
        return value;
    }

    static long applySignedness(long unsignedRaw, int bitLength, DbcValueType valueType) {
        long mask = bitLength == 64 ? -1L : ((1L << bitLength) - 1L);
        long masked = unsignedRaw & mask;
        if (valueType != DbcValueType.SIGNED || bitLength == 64) {
            // For 64-bit signed, masked already holds two's complement pattern in long
            if (valueType == DbcValueType.SIGNED && bitLength == 64) {
                return masked;
            }
            return masked;
        }
        long signBit = 1L << (bitLength - 1);
        if ((masked & signBit) != 0) {
            // Sign-extend without casting before width mask
            return masked | (~mask);
        }
        return masked;
    }

    private static void requireByte(byte[] payload, int byteIndex) {
        if (byteIndex < 0 || byteIndex >= payload.length) {
            throw new IndexOutOfBoundsException("byteIndex=" + byteIndex);
        }
    }

    private static void requireByte(CanonicalCanFrame frame, int byteIndex) {
        if (byteIndex < 0 || byteIndex >= frame.payloadLength()) {
            throw new IndexOutOfBoundsException("byteIndex=" + byteIndex);
        }
    }

    public record ExtractResult(Long rawValue, DbcDiagnostic error) {
        public static ExtractResult ok(long raw) {
            return new ExtractResult(raw, null);
        }

        public static ExtractResult error(DbcDiagnostic diagnostic) {
            return new ExtractResult(null, diagnostic);
        }

        public boolean isOk() {
            return error == null && rawValue != null;
        }
    }
}
