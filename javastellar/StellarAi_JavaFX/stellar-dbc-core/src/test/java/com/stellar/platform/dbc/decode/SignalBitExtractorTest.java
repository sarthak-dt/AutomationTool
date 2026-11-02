package com.stellar.platform.dbc.decode;

import com.stellar.platform.dbc.model.DbcByteOrder;
import com.stellar.platform.dbc.model.DbcValueType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalBitExtractorTest {

    @ParameterizedTest(name = "Intel start={0} len={1} raw={2}")
    @CsvSource({
            "3, 12, 2748",
            "0, 8, 255",
            "0, 16, 4660",
            "4, 4, 10",
            "8, 16, 1"
    })
    void extractsIntelUnsigned(int startBit, int length, long expected) {
        byte[] payload = new byte[8];
        writeIntel(payload, startBit, length, expected);
        var result = SignalBitExtractor.extract(payload, startBit, length, DbcByteOrder.INTEL, DbcValueType.UNSIGNED);
        assertTrue(result.isOk());
        assertEquals(expected, result.rawValue());
    }

    @ParameterizedTest(name = "Intel signed {0}-bit rawBits={1} expected={2}")
    @CsvSource({
            "8, 0xFF, -1",
            "8, 0x80, -128",
            "12, 0xFFF, -1",
            "12, 0x800, -2048",
            "16, 0xFFFF, -1",
            "16, 0x8000, -32768",
            "24, 0xFFFFFF, -1",
            "24, 0x800000, -8388608",
            "32, 0xFFFFFFFF, -1",
            "32, 0x80000000, -2147483648"
    })
    void extractsIntelSignedTwosComplement(int length, String rawHex, long expected) {
        long pattern = Long.decode(rawHex);
        byte[] payload = new byte[8];
        writeIntel(payload, 0, length, pattern);
        var result = SignalBitExtractor.extract(payload, 0, length, DbcByteOrder.INTEL, DbcValueType.SIGNED);
        assertTrue(result.isOk());
        assertEquals(expected, result.rawValue());
    }

    @ParameterizedTest(name = "Motorola start={0} len={1} raw={2}")
    @CsvSource({
            "7, 16, 4660",
            "7, 8, 171",
            "15, 12, 2748",
            "23, 8, 127"
    })
    void extractsMotorolaUnsignedGolden(int startBit, int length, long expected) {
        byte[] payload = new byte[8];
        writeMotorola(payload, startBit, length, expected);
        var result = SignalBitExtractor.extract(
                payload, startBit, length, DbcByteOrder.MOTOROLA, DbcValueType.UNSIGNED);
        assertTrue(result.isOk(), () -> String.valueOf(result.error()));
        assertEquals(expected, result.rawValue());
    }

    @ParameterizedTest(name = "Motorola signed len={0} pattern={1} expected={2}")
    @CsvSource({
            "8, 0xFF, -1",
            "8, 0x80, -128",
            "16, 0xFFFF, -1",
            "16, 0x8000, -32768"
    })
    void extractsMotorolaSigned(int length, String rawHex, long expected) {
        long pattern = Long.decode(rawHex) & ((1L << length) - 1);
        byte[] payload = new byte[8];
        int startBit = 7; // MSB in first byte
        writeMotorola(payload, startBit, length, pattern);
        var result = SignalBitExtractor.extract(
                payload, startBit, length, DbcByteOrder.MOTOROLA, DbcValueType.SIGNED);
        assertTrue(result.isOk());
        assertEquals(expected, result.rawValue());
    }

    static void writeIntel(byte[] data, int startBit, int length, long value) {
        for (int i = 0; i < length; i++) {
            if (((value >> i) & 1L) != 0) {
                int bitPos = startBit + i;
                data[bitPos / 8] |= (byte) (1 << (bitPos % 8));
            }
        }
    }

    static void writeMotorola(byte[] data, int startBit, int length, long value) {
        int bitPos = startBit;
        for (int i = length - 1; i >= 0; i--) {
            if (((value >> i) & 1L) != 0) {
                int byteIndex = bitPos / 8;
                int bitIndex = bitPos % 8;
                data[byteIndex] |= (byte) (1 << bitIndex);
            }
            if (bitPos % 8 == 0) {
                bitPos += 15;
            } else {
                bitPos -= 1;
            }
        }
    }
}
