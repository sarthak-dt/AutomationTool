package com.stellar.platform.analysis;

import com.stellar.platform.analysis.advanced.BoundedSampleBuffer;
import com.stellar.platform.analysis.align.SampleAligner;
import com.stellar.platform.analysis.band.OperatingBandAnalyzer;
import com.stellar.platform.analysis.derived.DerivedExpression;
import com.stellar.platform.analysis.derived.DerivedSignalEngine;
import com.stellar.platform.analysis.lag.BoundedLagAnalyzer;
import com.stellar.platform.analysis.model.AlignmentPolicy;
import com.stellar.platform.analysis.model.AnalysisParameters;
import com.stellar.platform.analysis.model.AnalysisSeverity;
import com.stellar.platform.analysis.model.ThresholdOperator;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.rate.RateOfChangeAnalyzer;
import com.stellar.platform.analysis.source.InMemoryAnalysisSampleSource;
import com.stellar.platform.analysis.state.StateTransitionAnalyzer;
import com.stellar.platform.analysis.stats.StreamingStatisticsAnalyzer;
import com.stellar.platform.analysis.threshold.ThresholdAnalyzer;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.parser.spi.CancellationToken;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumericEligibilityRemediationTest {

    private static final long S = 1_000_000_000L;
    private static final TimeWindow WINDOW = TimeWindow.of(0L, 20 * S);
    private static final CancellationToken TOKEN = CancellationToken.NONE;

    @Test
    void mixedFixtureKeepsOnlyEligibleNumbers() {
        SignalCatalogKey a = key("A");
        SignalCatalogKey b = key("B");
        InMemoryAnalysisSampleSource source = new InMemoryAnalysisSampleSource();
        source.put(a, mixed());
        source.put(b, partner());

        var stats = StreamingStatisticsAnalyzer.compute(source, a, WINDOW, TOKEN);
        assertEquals(2, stats.validCount());
        assertEquals(20.0, stats.mean(), 1e-12);
        assertEquals(Math.sqrt(500.0), stats.rms(), 1e-12);

        List<SampleAligner.Pair> pairs = SampleAligner.align(
                source, a, b, WINDOW, AlignmentPolicy.EXACT_TIMESTAMP, 0L, 100, TOKEN);
        assertEquals(List.of(10.0, 30.0), pairs.stream().map(SampleAligner.Pair::a).toList());
        assertEquals(List.of(10.0, 30.0), pairs.stream().map(SampleAligner.Pair::b).toList());
        assertEquals(1.0, pearson(pairs), 1e-12);

        assertTrue(ThresholdAnalyzer.detect(
                source, a, WINDOW,
                AnalysisParameters.empty().withThreshold(ThresholdOperator.GREATER_THAN, 100),
                "audit", TOKEN).isEmpty());

        var rate = RateOfChangeAnalyzer.compute(source, a, WINDOW, TOKEN);
        assertEquals(0, rate.pairs());
        assertTrue(Double.isNaN(rate.maxRate()));

        var band = OperatingBandAnalyzer.compute(source, a, WINDOW, 0, 100, TOKEN);
        assertEquals(30.0, band.maxInside(), 0.0);

        var buffer = BoundedSampleBuffer.collect(source, a, WINDOW, 100, TOKEN);
        assertEquals("[10.0, 30.0]", Arrays.toString(buffer.values()));
        assertEquals(1, buffer.textCount());
        assertEquals(3, buffer.excludedCount());

        var derived = DerivedSignalEngine.evaluate(
                source, DerivedExpression.parse("abs", a, null, null),
                WINDOW, AlignmentPolicy.EXACT_TIMESTAMP, 0L, 100, TOKEN);
        assertEquals(List.of(10.0, 30.0), derived.samples().stream().map(SignalSamplePoint::physicalValue).toList());
        assertTrue(derived.samples().stream().allMatch(sample -> sample.validity() == SignalValidity.VALID));

        String states = StateTransitionAnalyzer.detect(
                source, a, WINDOW, "audit", AnalysisSeverity.OBSERVATION, TOKEN)
                .stream().map(finding -> finding.summary()).reduce("", (left, right) -> left + " | " + right);
        assertTrue(states.contains("FALSE"));
        assertTrue(states.contains("UNRECORDED"));
        assertTrue(!states.contains("42"));
    }

    @Test
    void eightValidPairsStayPerfectlyCorrelatedWhenAnInvalidSampleIsPresent() {
        InMemoryAnalysisSampleSource source = new InMemoryAnalysisSampleSource();
        SignalCatalogKey lead = key("Lead");
        SignalCatalogKey follow = key("Follow");
        List<SignalSamplePoint> leadSamples = new ArrayList<>();
        List<SignalSamplePoint> followSamples = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            leadSamples.add(point(i * S, i, null, null, SignalValidity.VALID));
            followSamples.add(point(i * S, i, null, null, SignalValidity.VALID));
        }
        leadSamples.add(point(9 * S, 1000, 1000L, "invalid", SignalValidity.INVALID_RAW_CODE));
        followSamples.add(point(9 * S, 1, null, null, SignalValidity.VALID));
        source.put(lead, leadSamples);
        source.put(follow, followSamples);
        var lag = BoundedLagAnalyzer.estimate(
                source, lead, follow, WINDOW, AlignmentPolicy.EXACT_TIMESTAMP, 0L, 0L, TOKEN);
        assertEquals(8, lag.pairCount());
        assertEquals(1.0, lag.bestCorrelation(), 1e-12);
    }

    @Test
    void neighboringEligibleSamplesHaveARateAndAnInvalidSampleBreaksTheInterval() {
        InMemoryAnalysisSampleSource adjacent = new InMemoryAnalysisSampleSource();
        SignalCatalogKey speed = key("Speed");
        adjacent.put(speed, List.of(
                point(S, 10, null, null, SignalValidity.VALID),
                point(8 * S, 30, null, null, SignalValidity.VALID)));
        var open = RateOfChangeAnalyzer.compute(adjacent, speed, WINDOW, TOKEN);
        assertEquals(1, open.pairs());
        assertEquals(20.0 / 7.0, open.maxRate(), 1e-12);
    }

    @Test
    void holdDoesNotCarryAValueAcrossAnInvalidSample() {
        InMemoryAnalysisSampleSource source = new InMemoryAnalysisSampleSource();
        SignalCatalogKey a = key("A");
        SignalCatalogKey b = key("B");
        source.put(a, List.of(point(3 * S, 5, null, null, SignalValidity.VALID)));
        source.put(b, List.of(
                point(S, 5, null, null, SignalValidity.VALID),
                point(2 * S, 999, 999L, "invalid", SignalValidity.INVALID_RAW_CODE)));
        assertTrue(SampleAligner.align(
                source, a, b, WINDOW, AlignmentPolicy.PREVIOUS_VALUE_HOLD, 0L, 20, TOKEN).isEmpty());
    }

    private static double pearson(List<SampleAligner.Pair> pairs) {
        int n = pairs.size();
        double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
        for (SampleAligner.Pair pair : pairs) {
            sx += pair.a();
            sy += pair.b();
            sxx += pair.a() * pair.a();
            syy += pair.b() * pair.b();
            sxy += pair.a() * pair.b();
        }
        double num = n * sxy - sx * sy;
        double den = Math.sqrt((n * sxx - sx * sx) * (n * syy - sy * sy));
        return den == 0 ? Double.NaN : num / den;
    }

    static List<SignalSamplePoint> mixed() {
        return List.of(
                point(S, 10, null, null, SignalValidity.VALID),
                point(2 * S, Double.NaN, 0L, "FALSE", SignalValidity.VALID),
                point(3 * S, 255, 255L, "invalid", SignalValidity.INVALID_RAW_CODE),
                point(4 * S, 7, 1L, "inactive", SignalValidity.INACTIVE_RAW_CODE),
                point(5 * S, Double.NaN, 9L, null, SignalValidity.CONVERSION_FAILED),
                point(7 * S, 42, 42L, null, SignalValidity.UNRECORDED),
                point(8 * S, 30, null, null, SignalValidity.VALID));
    }

    private static List<SignalSamplePoint> partner() {
        return List.of(
                point(S, 10, null, null, SignalValidity.VALID),
                point(2 * S, 10, null, null, SignalValidity.VALID),
                point(3 * S, 10, null, null, SignalValidity.VALID),
                point(4 * S, 10, null, null, SignalValidity.VALID),
                point(5 * S, 10, null, null, SignalValidity.VALID),
                point(6 * S, 10, null, null, SignalValidity.VALID),
                point(7 * S, 10, null, null, SignalValidity.VALID),
                point(8 * S, 30, null, null, SignalValidity.VALID));
    }

    private static SignalSamplePoint point(long ts, double physical, Long raw, String text, SignalValidity validity) {
        return new SignalSamplePoint(ts, physical, raw, text, validity);
    }

    private static SignalCatalogKey key(String name) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "DIRECT_MEASUREMENT", 0L);
    }
}
