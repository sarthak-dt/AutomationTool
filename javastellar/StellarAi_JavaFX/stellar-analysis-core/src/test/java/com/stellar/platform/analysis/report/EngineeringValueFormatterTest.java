package com.stellar.platform.analysis.report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineeringValueFormatterTest {

    @Test
    void largeIntegerAvoidsRawScientificNotation() {
        double raw = Double.parseDouble("5.43653e+10");
        assertEquals(54_365_300_000d, raw);
        String shown = EngineeringValueFormatter.timestamp(raw);
        assertEquals("54,365,300,000", shown);
        assertFalse(shown.toLowerCase().contains("e+"));
        assertEquals(54_365_300_000d, raw);
    }

    @Test
    void plainDecimalsTrimTrailingZeros() {
        assertEquals("1.308649", EngineeringValueFormatter.decimal(1.308649123d));
        assertEquals("902", EngineeringValueFormatter.decimal(902.0d));
        assertEquals("0.000012", EngineeringValueFormatter.decimal(0.0000123456d));
    }

    @Test
    void secondsAndTimestampsKeepCallerUnits() {
        assertEquals("1.308649 s", EngineeringValueFormatter.seconds(1.308649123d));
        assertEquals("500 ns", EngineeringValueFormatter.nanos(500d));
        assertEquals("12.5 %", EngineeringValueFormatter.percent(12.5d));
        assertEquals("10 Hz", EngineeringValueFormatter.hertz(10d));
        assertEquals("—", EngineeringValueFormatter.decimal(Double.NaN));
    }

    @Test
    void extremeMagnitudesMayUseScientificNotation() {
        String tiny = EngineeringValueFormatter.decimal(1.23e-8d);
        assertTrue(tiny.toLowerCase().contains("e"));
        String huge = EngineeringValueFormatter.decimal(1.23e16d);
        assertTrue(huge.toLowerCase().contains("e"));
    }
}
