package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MinMaxLodReducerTest {
    @Test void preservesShortSpikeInOverviewBucket() {
        List<SignalSamplePoint> input = List.of(
                p(1, 800), p(2, 810), p(3, 805), p(4, 6200), p(5, 808), p(6, 800));
        List<GraphBucket> buckets = MinMaxLodReducer.reduce(input, 2);
        assertEquals(2, buckets.size());
        assertEquals(6200.0, buckets.get(1).maxValue());
        assertEquals(4L, buckets.get(1).maxTimestampNanos());
        assertEquals(3, buckets.get(1).sampleCount());
    }

    @Test void returnsRawResolutionWhenUnderBudget() {
        List<GraphBucket> buckets = MinMaxLodReducer.reduce(List.of(p(1, 1), p(2, 2)), 10);
        assertEquals(2, buckets.size());
        assertTrue(buckets.stream().allMatch(b -> b.sampleCount() == 1));
    }

    @Test void nanBeforeFiniteSamplesDoesNotPoisonTheBucket() {
        List<GraphBucket> buckets = MinMaxLodReducer.reduce(List.of(
                p(1, Double.NaN), p(2, 10), p(3, -4), p(4, 20)), 1);
        assertEquals(1, buckets.size());
        assertEquals(-4.0, buckets.getFirst().minValue(), 0.0);
        assertEquals(20.0, buckets.getFirst().maxValue(), 0.0);
        assertTrue(Double.isNaN(buckets.getFirst().firstValue()));
        assertEquals(SignalValidity.NON_FINITE_VALUE, buckets.getFirst().firstValidity());
        assertEquals(20.0, buckets.getFirst().lastValue(), 0.0);
    }

    @Test void nanBetweenAndAfterFiniteSamplesLeavesFiniteExtrema() {
        List<GraphBucket> buckets = MinMaxLodReducer.reduce(List.of(
                p(1, 5), p(2, Double.POSITIVE_INFINITY), p(3, -2),
                p(4, Double.NEGATIVE_INFINITY), p(5, 8)), 1);
        GraphBucket bucket = buckets.getFirst();
        assertEquals(-2.0, bucket.minValue(), 0.0);
        assertEquals(8.0, bucket.maxValue(), 0.0);
        assertEquals(5.0, bucket.firstValue(), 0.0);
        assertEquals(8.0, bucket.lastValue(), 0.0);
        assertEquals(5, bucket.sampleCount());
    }

    @Test void allNonFiniteBucketDoesNotInventAFiniteRange() {
        List<GraphBucket> buckets = MinMaxLodReducer.reduce(List.of(
                p(1, Double.NaN), p(2, Double.POSITIVE_INFINITY)), 1);
        GraphBucket bucket = buckets.getFirst();
        assertTrue(Double.isNaN(bucket.minValue()));
        assertTrue(Double.isNaN(bucket.maxValue()));
        assertFalse(bucket.minUsable());
        assertEquals(2, bucket.sampleCount());
    }

    @Test void invalidFiniteValueDoesNotDefineExtrema() {
        List<SignalSamplePoint> input = List.of(
                new SignalSamplePoint(1, 655.35, 65535L, "invalid", SignalValidity.INVALID_RAW_CODE),
                p(2, 10),
                p(3, 42));
        GraphBucket bucket = MinMaxLodReducer.reduce(input, 1).getFirst();
        assertEquals(10.0, bucket.minValue(), 0.0);
        assertEquals(42.0, bucket.maxValue(), 0.0);
        assertEquals(655.35, bucket.firstValue(), 0.0);
        assertEquals(SignalValidity.INVALID_RAW_CODE, bucket.firstValidity());
    }

    @Test void extremeFiniteValueIsPreserved() {
        GraphBucket bucket = MinMaxLodReducer.reduce(List.of(p(1, -1.0e308), p(2, 1.0e308)), 1).getFirst();
        assertEquals(-1.0e308, bucket.minValue(), 0.0);
        assertEquals(1.0e308, bucket.maxValue(), 0.0);
    }

    private static SignalSamplePoint p(long ts, double v) { return new SignalSamplePoint(ts, v, null, null); }
}
