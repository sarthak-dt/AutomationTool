package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.dynamicschema.MdfByteOrder;
import com.stellar.platform.parser.mf4.dynamicschema.MdfChannelDataTypeKind;
import com.stellar.platform.parser.mf4.dynamicschema.MdfChannelFieldSchema;
import com.stellar.platform.parser.mf4.dynamicschema.MdfSignedness;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Metadata-driven binary field extraction from a record data region (no hard-coded offsets).
 */
public final class MdfBitFieldExtractor {

    private MdfBitFieldExtractor() {
    }

    public sealed interface ExtractedValue {
        record IntegerValue(long rawUnsigned, long signedOrUnsigned, boolean signed) implements ExtractedValue {
        }

        record FloatValue(double value) implements ExtractedValue {
        }

        record BytesValue(byte[] bytes) implements ExtractedValue {
            public BytesValue {
                bytes = bytes == null ? new byte[0] : bytes;
            }
        }

        record StringValue(String value) implements ExtractedValue {
        }

        record Unsupported(String reason) implements ExtractedValue {
        }
    }

    public static ExtractedValue extract(byte[] dataRegion, MdfChannelFieldSchema field) {
        if (dataRegion == null || field == null) {
            return new ExtractedValue.Unsupported("null data or field");
        }
        return extract(dataRegion, 0, dataRegion.length, MdfFieldDecodePlan.compile(field, -1));
    }

    public static ExtractedValue extract(
            byte[] buffer,
            int dataOffset,
            int dataLength,
            MdfFieldDecodePlan plan
    ) {
        if (buffer == null || plan == null) {
            return new ExtractedValue.Unsupported("null data or field");
        }
        if (!plan.supported()) {
            return new ExtractedValue.Unsupported(
                    "unsupported channel data type code=" + plan.field().dataTypeCode());
        }
        if (plan.bitLength() <= 0 || plan.bitOffset() < 0 || plan.bitOffset() > 7 || plan.byteOffset() < 0) {
            return new ExtractedValue.Unsupported("invalid field geometry");
        }
        if (plan.startBit() == Integer.MAX_VALUE) {
            return new ExtractedValue.Unsupported("field bit-range overflow");
        }
        long endBit = (long) plan.startBit() + (long) plan.bitLength();
        long dataBits = (long) dataLength * 8L;
        if (endBit > dataBits) {
            return new ExtractedValue.Unsupported("field exceeds data region");
        }

        MdfChannelDataTypeKind kind = plan.kind();
        return switch (kind) {
            case UNSIGNED_INTEGER_LE, UNSIGNED_INTEGER_BE, SIGNED_INTEGER_LE, SIGNED_INTEGER_BE ->
                    extractInteger(buffer, dataOffset, plan, kind);
            case FLOAT_LE, FLOAT_BE -> extractFloat(buffer, dataOffset, plan, kind);
            case BYTE_ARRAY -> extractBytes(buffer, dataOffset, dataLength, plan);
            case STRING_LATIN1, STRING_UTF8, STRING_UTF16_LE -> extractString(buffer, dataOffset, dataLength, plan, kind);
            default -> new ExtractedValue.Unsupported("data type not decodable: " + kind);
        };
    }

    public static boolean isInvalidationBitSet(
            byte[] invalidationRegion,
            long invalidationBitPosition
    ) {
        if (invalidationRegion == null || invalidationRegion.length == 0 || invalidationBitPosition < 0) {
            return false;
        }
        long bitCount = (long) invalidationRegion.length * 8L;
        if (invalidationBitPosition >= bitCount) {
            return false;
        }
        int byteIndex = (int) (invalidationBitPosition / 8L);
        int bitInByte = (int) (invalidationBitPosition % 8L);
        return ((invalidationRegion[byteIndex] >> bitInByte) & 1) != 0;
    }

    public static boolean isInvalidationBitSet(
            byte[] buffer,
            int invalOffset,
            int invalLength,
            MdfFieldDecodePlan plan
    ) {
        if (buffer == null || plan == null || !plan.invalidationBitValid()) {
            return false;
        }
        int byteIndex = plan.invalidationByteIndex();
        if (byteIndex < 0 || byteIndex >= invalLength) {
            return false;
        }
        return (buffer[invalOffset + byteIndex] & plan.invalidationMask()) != 0;
    }

    private static ExtractedValue extractInteger(
            byte[] data,
            int dataOffset,
            MdfFieldDecodePlan plan,
            MdfChannelDataTypeKind kind
    ) {
        int bitLength = plan.bitLength();
        if (bitLength > 64) {
            return new ExtractedValue.Unsupported("integer bitLength > 64");
        }
        long raw;
        if (plan.bitOffset() == 0 && bitLength % 8 == 0) {
            raw = readByteAlignedInteger(data, dataOffset + plan.byteOffset(), bitLength / 8, kind.byteOrder());
        } else if (kind.byteOrder() == MdfByteOrder.BIG_ENDIAN) {
            raw = readBitsMsbFirst(data, dataOffset * 8 + plan.startBit(), bitLength);
        } else {
            raw = readBitsLsbFirst(data, dataOffset * 8 + plan.startBit(), bitLength);
        }
        boolean signed = kind.signedness() == MdfSignedness.SIGNED;
        long value = signed ? signExtend(raw, bitLength) : raw;
        return new ExtractedValue.IntegerValue(raw, value, signed);
    }

    private static ExtractedValue extractFloat(
            byte[] data,
            int dataOffset,
            MdfFieldDecodePlan plan,
            MdfChannelDataTypeKind kind
    ) {
        int bitLength = plan.bitLength();
        if (bitLength != 32 && bitLength != 64) {
            return new ExtractedValue.Unsupported("float bitLength must be 32 or 64");
        }
        long bits;
        if (plan.bitOffset() == 0 && bitLength % 8 == 0) {
            bits = readByteAlignedInteger(data, dataOffset + plan.byteOffset(), bitLength / 8, kind.byteOrder());
        } else if (kind.byteOrder() == MdfByteOrder.BIG_ENDIAN) {
            bits = readBitsMsbFirst(data, dataOffset * 8 + plan.startBit(), bitLength);
        } else {
            bits = readBitsLsbFirst(data, dataOffset * 8 + plan.startBit(), bitLength);
        }
        if (bitLength == 32) {
            return new ExtractedValue.FloatValue(Float.intBitsToFloat((int) bits));
        }
        return new ExtractedValue.FloatValue(Double.longBitsToDouble(bits));
    }

    private static long readByteAlignedInteger(
            byte[] data, int byteOffset, int byteCount, MdfByteOrder order
    ) {
        long value = 0L;
        if (order == MdfByteOrder.BIG_ENDIAN) {
            for (int i = 0; i < byteCount; i++) {
                value = (value << 8) | Byte.toUnsignedLong(data[byteOffset + i]);
            }
        } else {
            for (int i = 0; i < byteCount; i++) {
                value |= Byte.toUnsignedLong(data[byteOffset + i]) << (8 * i);
            }
        }
        return value;
    }

    private static ExtractedValue extractBytes(byte[] data, int dataOffset, int dataLength, MdfFieldDecodePlan plan) {
        int byteCount = plan.byteCount();
        int from = dataOffset + plan.byteOffset();
        int regionEnd = dataOffset + dataLength;
        if (plan.byteOffset() < 0 || from > regionEnd || from + byteCount > regionEnd) {
            return new ExtractedValue.Unsupported("byte array exceeds data region");
        }
        return new ExtractedValue.BytesValue(Arrays.copyOfRange(data, from, from + byteCount));
    }

    private static ExtractedValue extractString(
            byte[] data, int dataOffset, int dataLength, MdfFieldDecodePlan plan, MdfChannelDataTypeKind kind
    ) {
        ExtractedValue bytesVal = extractBytes(data, dataOffset, dataLength, plan);
        if (!(bytesVal instanceof ExtractedValue.BytesValue bytes)) {
            return bytesVal;
        }
        String text = switch (kind) {
            case STRING_UTF8 -> new String(bytes.bytes(), StandardCharsets.UTF_8);
            case STRING_UTF16_LE -> new String(bytes.bytes(), StandardCharsets.UTF_16LE);
            default -> new String(bytes.bytes(), StandardCharsets.ISO_8859_1);
        };
        int nul = text.indexOf('\0');
        if (nul >= 0) {
            text = text.substring(0, nul);
        }
        return new ExtractedValue.StringValue(text);
    }

    /** Little-endian bit packing: bit 0 of value is at startBit. */
    static long readBitsLsbFirst(byte[] data, int startBit, int bitLength) {
        long value = 0L;
        for (int i = 0; i < bitLength; i++) {
            int bit = startBit + i;
            int byteIndex = bit / 8;
            int bitInByte = bit % 8;
            if (((data[byteIndex] >> bitInByte) & 1) != 0) {
                value |= 1L << i;
            }
        }
        return value;
    }

    /** Big-endian bit packing: first bit is MSB of the value. */
    static long readBitsMsbFirst(byte[] data, int startBit, int bitLength) {
        long value = 0L;
        for (int i = 0; i < bitLength; i++) {
            int bit = startBit + i;
            int byteIndex = bit / 8;
            int bitInByte = bit % 8;
            value <<= 1;
            if (((data[byteIndex] >> bitInByte) & 1) != 0) {
                value |= 1L;
            }
        }
        return value;
    }

    static long signExtend(long raw, int bitLength) {
        if (bitLength <= 0 || bitLength >= 64) {
            return raw;
        }
        long signBit = 1L << (bitLength - 1);
        if ((raw & signBit) == 0) {
            return raw;
        }
        long mask = -1L << bitLength;
        return raw | mask;
    }
}