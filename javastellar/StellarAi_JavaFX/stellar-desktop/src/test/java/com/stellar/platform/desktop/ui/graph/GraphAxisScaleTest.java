package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.processing.graph.GraphBucket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphAxisScaleTest {

    @Test
    void positiveValuesKeepTheirOwnMinAndMax() {
        GraphAxisScale.Domain domain = GraphAxisScale.fromBuckets(List.of(
                usable(0, 10, 10),
                usable(1, 30, 30),
                usable(2, 18, 18)));
        assertTrue(domain.hasUsable());
        assertEquals(10, domain.dataMin(), 0);
        assertEquals(30, domain.dataMax(), 0);
        assertTrue(domain.axisMin() < domain.dataMin());
        assertTrue(domain.axisMax() > domain.dataMax());
        assertFalse(domain.binary());
        assertFalse(domain.constant());
    }

    @Test
    void negativeValuesStayNegative() {
        GraphAxisScale.Domain domain = GraphAxisScale.fromBuckets(List.of(
                usable(0, -40, -40),
                usable(1, -5, -5)));
        assertEquals(-40, domain.dataMin(), 0);
        assertEquals(-5, domain.dataMax(), 0);
        assertTrue(domain.axisMin() < -40);
        assertTrue(domain.dataMax() < 0);
    }

    @Test
    void constantTraceIsNotForcedOntoABinaryAxis() {
        GraphAxisScale.Domain flat = GraphAxisScale.fromBuckets(List.of(usable(0, 7, 7), usable(1, 7, 7)));
        assertTrue(flat.constant());
        assertFalse(flat.binary());
        assertEquals(7, flat.dataMin(), 0);
        assertEquals(7, flat.dataMax(), 0);
        assertTrue(flat.axisMin() < 7 && flat.axisMax() > 7);

        GraphAxisScale.Domain zero = GraphAxisScale.fromBuckets(List.of(usable(0, 0, 0)));
        assertTrue(zero.constant());
        assertFalse(zero.binary());
        assertEquals(0, zero.dataMin(), 0);
    }

    @Test
    void binaryNeedsBothZeroAndOne() {
        GraphAxisScale.Domain domain = GraphAxisScale.fromBuckets(List.of(usable(0, 0, 0), usable(1, 1, 1)));
        assertTrue(domain.binary());
        assertEquals(0, domain.axisMin(), 0);
        assertEquals(1, domain.axisMax(), 0);
        assertEquals(0, domain.dataMin(), 0);
        assertEquals(1, domain.dataMax(), 0);
    }

    @Test
    void extremeFiniteValuesAreNotClamped() {
        GraphAxisScale.Domain domain = GraphAxisScale.fromBuckets(List.of(
                usable(0, -1e308, -1e308),
                usable(1, 1e308, 1e308)));
        assertEquals(-1e308, domain.dataMin(), 0);
        assertEquals(1e308, domain.dataMax(), 0);
        assertTrue(Double.isFinite(domain.axisMin()));
        assertTrue(Double.isFinite(domain.axisMax()));
        assertTrue(domain.axisMin() <= domain.dataMin());
        assertTrue(domain.axisMax() >= domain.dataMax());
    }

    @Test
    void invalidAndNonFiniteSamplesDoNotDefineTheRange() {
        GraphBucket invalid = new GraphBucket(
                0, 655.35, 0, 655.35, 0, 655.35, 0, 655.35, 1,
                SignalValidity.INVALID_RAW_CODE, SignalValidity.INVALID_RAW_CODE,
                SignalValidity.INVALID_RAW_CODE, SignalValidity.INVALID_RAW_CODE);
        GraphBucket nan = new GraphBucket(
                1, Double.NaN, 1, Double.NaN, 1, Double.NaN, 1, Double.NaN, 1,
                SignalValidity.NON_FINITE_VALUE, SignalValidity.NON_FINITE_VALUE,
                SignalValidity.NON_FINITE_VALUE, SignalValidity.NON_FINITE_VALUE);
        GraphBucket infinite = new GraphBucket(
                2, Double.POSITIVE_INFINITY, 2, Double.POSITIVE_INFINITY,
                2, Double.POSITIVE_INFINITY, 2, Double.POSITIVE_INFINITY, 1,
                SignalValidity.NON_FINITE_VALUE, SignalValidity.NON_FINITE_VALUE,
                SignalValidity.NON_FINITE_VALUE, SignalValidity.NON_FINITE_VALUE);
        GraphAxisScale.Domain domain = GraphAxisScale.fromBuckets(List.of(
                invalid, nan, infinite, usable(3, 10, 10), usable(4, 40, 40)));
        assertEquals(10, domain.dataMin(), 0);
        assertEquals(40, domain.dataMax(), 0);
        assertTrue(domain.dataMax() < 100);

        GraphAxisScale.Domain none = GraphAxisScale.fromBuckets(List.of(invalid, nan, infinite));
        assertFalse(none.hasUsable());
    }

    @Test
    void sameUnitSharesAPhysicalFrameAndADifferentUnitDoesNot() {
        GraphAxisScale.Domain slow = GraphAxisScale.fromRange(0, 80);
        GraphAxisScale.Domain fast = GraphAxisScale.fromRange(10, 200);
        GraphAxisScale.Domain speed = GraphAxisScale.fromRange(
                Math.min(slow.dataMin(), fast.dataMin()),
                Math.max(slow.dataMax(), fast.dataMax()));
        GraphAxisScale.Domain rpm = GraphAxisScale.fromRange(800, 4000);
        assertEquals(0, speed.dataMin(), 0);
        assertEquals(200, speed.dataMax(), 0);
        assertTrue(speed.axisMax() > 200);
        assertEquals(4000, rpm.dataMax(), 0);
        assertTrue(rpm.dataMin() > speed.dataMax());
        assertFalse(rpm.axisMax() == 100 && rpm.axisMin() == 0);
    }

    @Test
    void ticksKeepSignAndFractionAndNormalizedTicksSayPercent() {
        assertEquals("1000.25", GraphAxisScale.formatTick(1000.25));
        assertEquals("-40.5", GraphAxisScale.formatTick(-40.5));
        double noisy = 0.1 + 0.2;
        assertEquals("0.3", GraphAxisScale.formatTick(noisy));
        assertTrue(GraphAxisScale.formatTick(noisy).length() < Double.toString(noisy).length());
        assertEquals("—", GraphAxisScale.formatTick(Double.NaN));
        assertEquals("—", GraphAxisScale.formatTick(Double.POSITIVE_INFINITY));
        assertTrue(GraphAxisScale.formatTick(1e308).startsWith("1"));
        String percent = GraphAxisScale.formatNormalizedPercent(50);
        assertTrue(percent.endsWith("%"));
        assertFalse(percent.contains("km/h"));
    }

    private static GraphBucket usable(long ts, double min, double max) {
        return new GraphBucket(ts, min, ts, min, ts, max, ts, max, 1);
    }
}
