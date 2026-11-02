package com.stellar.platform.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EnumeratedStateTest {

    @Test
    void hvbatCodesUseTheStoredRawLabelAndLeaveUnknownNumeric() {
        assertEquals("0 = Sleep", EnumeratedState.codeAndText(0L, SignalValidity.VALID, 0.0, "Sleep"));
        assertEquals("1 = Initialization", EnumeratedState.codeAndText(1L, SignalValidity.VALID, 0.0, "Initialization"));
        assertEquals("3 = Ready", EnumeratedState.codeAndText(3L, SignalValidity.VALID, 3.0, "Ready"));
        assertEquals("7 = Reserved", EnumeratedState.codeAndText(7L, SignalValidity.VALID, 7.0, "Reserved"));
        assertEquals("15 = Reserved", EnumeratedState.codeAndText(15L, SignalValidity.VALID, 15.0, "Reserved"));
        assertNull(EnumeratedState.text(SignalValidity.VALID, 16.0, null));
        assertNull(EnumeratedState.codeAndText(16L, SignalValidity.VALID, 16.0, null));
        assertNull(EnumeratedState.codeAndText(16L, SignalValidity.VALID, 16.0, "   "));
    }

    @Test
    void scaledPhysicalValueDoesNotChooseTheLabel() {
        assertEquals("Initialization", EnumeratedState.text(SignalValidity.VALID, 10.5, "Initialization"));
        assertEquals("1 = Initialization", EnumeratedState.codeAndText(1L, SignalValidity.VALID, 10.5, "Initialization"));
        assertNull(EnumeratedState.text(SignalValidity.VALID, 10.5, null));
    }

    @Test
    void invalidInactiveAndAnalogIntegersAreNotStates() {
        assertNull(EnumeratedState.text(SignalValidity.INVALID_RAW_CODE, 255.0, "invalid"));
        assertNull(EnumeratedState.text(SignalValidity.INACTIVE_RAW_CODE, 0.0, "inactive"));
        assertNull(EnumeratedState.text(SignalValidity.VALID, 72.96875, null));
        assertNull(EnumeratedState.text(null, 255.0, "invalid"));
        assertNull(EnumeratedState.text(SignalValidity.VALID, Double.NaN, "FALSE"));
        assertEquals("Sleep", EnumeratedState.text(null, 0.0, "Sleep"));
    }
}
