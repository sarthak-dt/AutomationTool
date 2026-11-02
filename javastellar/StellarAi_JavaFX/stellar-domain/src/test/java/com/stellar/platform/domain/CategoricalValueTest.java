package com.stellar.platform.domain;

import com.stellar.platform.domain.catalog.SignalSamplePoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoricalValueTest {

    @Test
    void falseAndTrueAreLabelsNotNumbers() {
        assertLabel("FALSE");
        assertLabel("TRUE");
        assertLabel("LOW");
        assertEquals(0, SignalValidity.VALID.storedCode());
        assertFalse(SignalValidity.VALID.usableWith(Double.NaN));
    }

    @Test
    void unknownCodeFailedConversionAndMissingStayDistinct() {
        assertFalse(CategoricalValue.isCategorical(SignalValidity.CONVERSION_FAILED, Double.NaN, null));
        assertFalse(CategoricalValue.isCategorical(SignalValidity.CONVERSION_FAILED, Double.NaN, "FALSE"));
        assertFalse(CategoricalValue.isCategorical(SignalValidity.NON_FINITE_VALUE, Double.NaN, null));
        assertFalse(CategoricalValue.isCategorical(SignalValidity.INVALID_RAW_CODE, 255, "invalid"));
        assertFalse(CategoricalValue.isCategorical(SignalValidity.INACTIVE_RAW_CODE, 0, "inactive"));
        assertFalse(CategoricalValue.isCategorical(SignalValidity.VALID, 72.96875, null));
        assertNull(CategoricalValue.label(SignalValidity.VALID, 72.96875, null));
        assertFalse(new SignalSamplePoint(1L, Double.NaN, null, null).usableMeasurement());
        assertEquals(SignalValidity.NON_FINITE_VALUE, new SignalSamplePoint(1L, Double.NaN, null, null).validity());
    }

    @Test
    void legacyUnrecordedLabelStaysCategoricalAndExplicitStatusIsKept() {
        assertEquals("FALSE", CategoricalValue.label(SignalValidity.UNRECORDED, Double.NaN, "FALSE"));
        SignalSamplePoint implied = new SignalSamplePoint(5_998_194L, Double.NaN, 0L, "FALSE");
        assertEquals(SignalValidity.VALID, implied.validity());
        assertEquals("FALSE", implied.textualValue());
        assertTrue(Double.isNaN(implied.physicalValue()));
        SignalSamplePoint failed = new SignalSamplePoint(
                1L, Double.NaN, 9L, null, SignalValidity.CONVERSION_FAILED);
        assertEquals(SignalValidity.CONVERSION_FAILED, failed.validity());
        assertFalse(CategoricalValue.isCategorical(failed.validity(), failed.physicalValue(), failed.textualValue()));
    }

    private static void assertLabel(String label) {
        assertTrue(CategoricalValue.isCategorical(SignalValidity.VALID, Double.NaN, label));
        assertEquals(label, CategoricalValue.label(SignalValidity.VALID, Double.NaN, label));
        assertFalse(SignalValidity.VALID.usableWith(Double.NaN));
    }
}
