package com.stellar.platform.desktop.presentation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeasurementAddConfirmationTest {

    @Test
    void singleFileDoesNotConfirm() {
        assertFalse(MeasurementAddConfirmation.requiresConfirmation(1));
        assertFalse(MeasurementAddConfirmation.requiresConfirmation(0));
    }

    @Test
    void multiFileShowsCountAndAsksToAdd() {
        assertTrue(MeasurementAddConfirmation.requiresConfirmation(11));
        assertEquals("11 measurement files selected", MeasurementAddConfirmation.heading(11));
        assertEquals("Add 11 measurement files?", MeasurementAddConfirmation.question(11));
    }
}
