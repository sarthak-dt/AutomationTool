package com.stellar.platform.desktop.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UiFormatTimestampTest {

    @Test
    void secondsFromNanos_usesIntegerArithmeticWithoutBinaryNoise() {
        // 500.91908 s exactly as nanos
        long nanos = 500_919_080_000L;
        assertEquals("500.919080", UiFormat.secondsFromNanos(nanos));
        assertFalse(UiFormat.secondsFromNanos(nanos).contains("99999"));

        long near = 500_818_890_000L;
        assertEquals("500.818890", UiFormat.secondsFromNanos(near));
    }

    @Test
    void numericalParityIsAgainstCanonicalNanosNotDisplayString() {
        long nanos = 500_919_079_999L;
        // Display may round-trim trailing zeros; source of truth remains nanos.
        assertEquals(500_919_079_999L, nanos);
        assertEquals("500.919079999", UiFormat.secondsFromNanos(nanos));
    }

    @Test
    void physicalValueExact_preservesDoubleBitsAsStored() {
        double v = 1.2345678901234567;
        assertEquals(Double.toString(v), UiFormat.physicalValueExact(v));
    }
}
