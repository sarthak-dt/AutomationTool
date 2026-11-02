package com.stellar.platform.parser.asc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AscTimestampCodecTest {

    @Test
    void convertsExactMicrosecondFractionWithoutDoubleRounding() {
        assertEquals(123_456_000L, AscTimestampCodec.secondsTokenToNanos("0.123456"));
        assertEquals(100_000_000L, AscTimestampCodec.secondsTokenToNanos("0.100000"));
        assertEquals(1_500_000_000L, AscTimestampCodec.secondsTokenToNanos("1.5"));
        assertEquals(0L, AscTimestampCodec.secondsTokenToNanos("0"));
        assertEquals(2_000_000_000L, AscTimestampCodec.secondsTokenToNanos("2"));
        assertEquals(1L, AscTimestampCodec.secondsTokenToNanos("0.000000001"));
    }

    @Test
    void truncatesSubNanosecondFraction() {
        assertEquals(123_456_789L, AscTimestampCodec.secondsTokenToNanos("0.1234567891"));
    }

    @Test
    void rejectsInvalidTokens() {
        assertThrows(IllegalArgumentException.class, () -> AscTimestampCodec.secondsTokenToNanos(""));
        assertThrows(IllegalArgumentException.class, () -> AscTimestampCodec.secondsTokenToNanos("1.2.3"));
        assertThrows(IllegalArgumentException.class, () -> AscTimestampCodec.secondsTokenToNanos("1,234"));
        assertThrows(IllegalArgumentException.class, () -> AscTimestampCodec.secondsTokenToNanos("-1.0"));
        assertThrows(IllegalArgumentException.class, () -> AscTimestampCodec.secondsTokenToNanos("abc"));
    }
}
