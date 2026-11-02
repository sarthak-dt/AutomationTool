package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.dynamicschema.MdfCanRoleCandidate;
import com.stellar.platform.parser.mf4.dynamicschema.MdfChannelDataTypeKind;
import com.stellar.platform.parser.mf4.dynamicschema.MdfChannelFieldSchema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MdfBitFieldExtractorTest {

    @Test
    void littleEndianInteger() {
        byte[] data = new byte[]{(byte) 0x78, (byte) 0x56, (byte) 0x34, (byte) 0x12};
        var value = MdfBitFieldExtractor.extract(data, field("le", 0, 0, 0, 32));
        var iv = assertInstanceOf(MdfBitFieldExtractor.ExtractedValue.IntegerValue.class, value);
        assertEquals(0x12345678L, iv.rawUnsigned());
    }

    @Test
    void bigEndianInteger() {
        byte[] data = new byte[]{(byte) 0x12, (byte) 0x34, (byte) 0x56, (byte) 0x78};
        var value = MdfBitFieldExtractor.extract(data, field("be", 1, 0, 0, 32));
        var iv = assertInstanceOf(MdfBitFieldExtractor.ExtractedValue.IntegerValue.class, value);
        assertEquals(0x12345678L, iv.rawUnsigned());
    }

    @Test
    void signedInteger() {
        byte[] data = new byte[]{(byte) 0xFF}; // -1 as int8 LE
        var value = MdfBitFieldExtractor.extract(data, field("s", 2, 0, 0, 8));
        var iv = assertInstanceOf(MdfBitFieldExtractor.ExtractedValue.IntegerValue.class, value);
        assertEquals(-1L, iv.signedOrUnsigned());
        assertTrue(iv.signed());
    }

    @Test
    void floatLe() {
        int bits = Float.floatToIntBits(1.5f);
        byte[] data = new byte[]{
                (byte) bits, (byte) (bits >>> 8), (byte) (bits >>> 16), (byte) (bits >>> 24)
        };
        var value = MdfBitFieldExtractor.extract(data, field("f", 4, 0, 0, 32));
        var fv = assertInstanceOf(MdfBitFieldExtractor.ExtractedValue.FloatValue.class, value);
        assertEquals(1.5d, fv.value(), 1e-6);
    }

    @Test
    void bitOffsetAndCrossingByteBoundary() {
        // 12 bits starting at bit 3 of byte 0: pack value 0xABC (2748) LE
        int value = 0xABC;
        byte[] data = new byte[2];
        for (int i = 0; i < 12; i++) {
            if (((value >> i) & 1) != 0) {
                int bit = 3 + i;
                data[bit / 8] |= (byte) (1 << (bit % 8));
            }
        }
        var extracted = MdfBitFieldExtractor.extract(data, field("bits", 0, 0, 3, 12));
        var iv = assertInstanceOf(MdfBitFieldExtractor.ExtractedValue.IntegerValue.class, extracted);
        assertEquals(0xABCL, iv.rawUnsigned());
    }

    private static MdfChannelFieldSchema field(
            String name, int dataType, long byteOffset, int bitOffset, long bitLength
    ) {
        MdfChannelDataTypeKind kind = MdfChannelDataTypeKind.fromCode(dataType);
        return new MdfChannelFieldSchema(
                1L, name, 0, 0, dataType, kind, true, byteOffset, bitOffset, bitLength,
                kind.byteOrder(), kind.signedness(), 0L, 0, 0L, 0L, 0L, "",
                MdfCanRoleCandidate.none(), 0L, false, false
        );
    }
}
