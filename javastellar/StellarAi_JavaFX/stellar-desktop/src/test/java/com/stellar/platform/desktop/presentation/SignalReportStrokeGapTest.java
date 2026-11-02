package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.SignalValidity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalReportStrokeGapTest {

    @Test
    void invalidAndNonFiniteSamplesBreakTheStrokeAndStayOutOfTheDomain() {
        double[] values = {10.0, 655.35, Double.NaN, 12.0, Double.POSITIVE_INFINITY, 40.0};
        SignalValidity[] validity = {
                SignalValidity.VALID,
                SignalValidity.INVALID_RAW_CODE,
                SignalValidity.NON_FINITE_VALUE,
                SignalValidity.VALID,
                SignalValidity.NON_FINITE_VALUE,
                SignalValidity.VALID
        };
        assertEquals(3, SignalReportRenderer.strokeSegmentCount(values, validity));
        double[] domain = SignalReportRenderer.yDomain(values, validity);
        assertTrue(domain[0] < 10.0);
        assertTrue(domain[1] > 40.0);
        assertTrue(domain[1] < 100.0, "invalid 655.35 must not set the report axis");
    }

    @Test
    void extremeFiniteValuesRemainInTheDomain() {
        double[] domain = SignalReportRenderer.yDomain(
                new double[]{-500.0, 1_000_000.0},
                new SignalValidity[]{SignalValidity.VALID, SignalValidity.VALID});
        assertTrue(domain[0] < -500.0);
        assertTrue(domain[1] > 1_000_000.0);
    }
}
