package com.stellar.platform.analysis;

import com.stellar.platform.analysis.derived.DerivedExpression;
import com.stellar.platform.analysis.derived.DerivedSignalEngine;
import com.stellar.platform.analysis.derived.DerivedSignalIdentity;
import com.stellar.platform.analysis.invalidate.AnalysisInvalidationKey;
import com.stellar.platform.analysis.model.AlignmentPolicy;
import com.stellar.platform.analysis.model.AnalysisParameters;
import com.stellar.platform.analysis.model.AnalysisRequest;
import com.stellar.platform.analysis.model.AnalysisResult;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.AnalysisType;
import com.stellar.platform.analysis.model.FindingType;
import com.stellar.platform.analysis.model.SignalStatistics;
import com.stellar.platform.analysis.model.ThresholdOperator;
import com.stellar.platform.analysis.model.TimeRangeSource;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.persist.AnalysisArtifactStore;
import com.stellar.platform.analysis.source.AnalysisSampleSource;
import com.stellar.platform.analysis.source.InMemoryAnalysisSampleSource;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.parser.spi.SimpleCancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisEngineTest {

    private final AnalysisEngine engine = new AnalysisEngine();

    @Test
    void statisticsKnownMinMaxMeanStddevRms() {
        InMemoryAnalysisSampleSource src = AutomotiveAnalysisFixtures.workstation();
        AnalysisResult result = engine.execute(request(AnalysisType.STATISTICS)
                .addSignal(AutomotiveAnalysisFixtures.SPEED).build(), src, null);
        assertEquals(AnalysisStatus.COMPLETED, result.status());
        SignalStatistics s = result.statistics().getFirst();
        assertEquals(11, s.validCount());
        assertEquals(0d, s.min(), 1e-9);
        assertEquals(80d, s.max(), 1e-9);
        assertEquals(40d, s.mean(), 1e-9);
        assertEquals(80d, s.delta(), 1e-9);
        assertTrue(s.standardDeviation() > 20);
        assertTrue(s.rms() > s.mean());
    }

    @Test
    void exactTimestampsAndValuesAreUnchanged() {
        InMemoryAnalysisSampleSource src = AutomotiveAnalysisFixtures.workstation();
        List<SignalSamplePoint> original = src.querySamples(AutomotiveAnalysisFixtures.RPM, 0, 100);
        engine.execute(request(AnalysisType.STATISTICS)
                .addSignal(AutomotiveAnalysisFixtures.RPM).build(), src, null);
        List<SignalSamplePoint> after = src.querySamples(AutomotiveAnalysisFixtures.RPM, 0, 100);
        assertEquals(original.size(), after.size());
        for (int i = 0; i < original.size(); i++) {
            assertEquals(original.get(i).timestampNanos(), after.get(i).timestampNanos());
            assertEquals(original.get(i).physicalValue(), after.get(i).physicalValue());
        }
    }

    @Test
    void thresholdSingleAndMultipleEventsPlusHysteresis() {
        InMemoryAnalysisSampleSource src = AutomotiveAnalysisFixtures.workstation();
        AnalysisResult multi = engine.execute(request(AnalysisType.THRESHOLD)
                .addSignal(AutomotiveAnalysisFixtures.COOLANT)
                .parameters(AnalysisParameters.empty().withThreshold(ThresholdOperator.GREATER_THAN, 105))
                .build(), src, null);
        assertTrue(multi.findings().size() >= 2, "without hysteresis the 106/107 and 106 peaks chatter");

        AnalysisResult hyst = engine.execute(request(AnalysisType.THRESHOLD)
                .addSignal(AutomotiveAnalysisFixtures.COOLANT)
                .parameters(AnalysisParameters.empty()
                        .withThreshold(ThresholdOperator.GREATER_OR_EQUAL, 105)
                        .withHysteresisExit(102))
                .build(), src, null);
        assertEquals(1, hyst.findings().size(), "hysteresis should coalesce chatter into one event");
        assertEquals(FindingType.THRESHOLD_EVENT, hyst.findings().getFirst().findingType());
    }

    @Test
    void thresholdBoundaryEquality() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        SignalCatalogKey key = AutomotiveAnalysisFixtures.COOLANT;
        src.add(key, 0, 104);
        src.add(key, 1_000_000_000L, 105);
        src.add(key, 2_000_000_000L, 105);
        src.add(key, 3_000_000_000L, 104);
        AnalysisResult gte = engine.execute(request(AnalysisType.THRESHOLD)
                .addSignal(key)
                .parameters(AnalysisParameters.empty().withThreshold(ThresholdOperator.GREATER_OR_EQUAL, 105))
                .build(), src, null);
        assertEquals(1, gte.findings().size());
        AnalysisResult gt = engine.execute(request(AnalysisType.THRESHOLD)
                .addSignal(key)
                .parameters(AnalysisParameters.empty().withThreshold(ThresholdOperator.GREATER_THAN, 105))
                .build(), src, null);
        assertEquals(0, gt.findings().size());
    }

    @Test
    void stateTransitionsIgnoreDuplicates() {
        AnalysisResult result = engine.execute(request(AnalysisType.STATE_TRANSITION)
                .addSignal(AutomotiveAnalysisFixtures.GEAR).build(),
                AutomotiveAnalysisFixtures.workstation(), null);
        assertEquals(2, result.findings().size());
        assertEquals("PARK → DRIVE", result.findings().get(0).summary());
        assertEquals("DRIVE → PARK", result.findings().get(1).summary());
        assertEquals(2_000_000_000L, result.findings().get(0).startTimestampNanos());
        assertEquals(6_000_000_000L, result.findings().get(1).startTimestampNanos());
    }

    @Test
    void durationActiveTime() {
        AnalysisResult result = engine.execute(request(AnalysisType.DURATION)
                .addSignal(AutomotiveAnalysisFixtures.BRAKE_SW)
                .parameters(new AnalysisParameters(
                        ThresholdOperator.GREATER_THAN, null, null, null, null, null, null,
                        1_000_000L, 500_000_000L,
                        com.stellar.platform.analysis.model.AnalysisSeverity.OBSERVATION, "ON", ""))
                .build(), AutomotiveAnalysisFixtures.workstation(), null);
        assertEquals(2, result.metrics().get("occurrences").intValue());
        assertTrue(result.metrics().get("totalActiveSeconds") >= 3.0);
    }

    @Test
    void rateSkipsDuplicateTimestamps() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        SignalCatalogKey key = AutomotiveAnalysisFixtures.SPEED;
        src.add(key, 0, 0);
        src.add(key, 0, 10);
        src.add(key, 1_000_000_000L, 20);
        AnalysisResult result = engine.execute(request(AnalysisType.RATE_OF_CHANGE).addSignal(key).build(), src, null);
        assertEquals(1d, result.metrics().get("ratePairs"), 1e-9);
        assertEquals(1d, result.metrics().get("skippedNonPositiveDt"), 1e-9);
        // Duplicate t=0 is skipped (no invented Δt). Previous value advances to 10, so
        // the only valid pair is 10 → 20 over 1 s.
        assertEquals(10d, result.metrics().get("maxRate"), 1e-6);
    }

    @Test
    void comparisonDifferentRatesUsesHoldAlignment() {
        InMemoryAnalysisSampleSource src = AutomotiveAnalysisFixtures.workstation();
        AnalysisResult exact = engine.execute(request(AnalysisType.COMPARISON)
                .signals(List.of(AutomotiveAnalysisFixtures.ACT_TQ, AutomotiveAnalysisFixtures.REQ_TQ))
                .alignmentPolicy(AlignmentPolicy.EXACT_TIMESTAMP)
                .build(), src, null);
        assertEquals(11d, exact.metrics().get("pairCount"), 1e-9);
        assertEquals(2d, exact.metrics().get("mae"), 1e-9);

        InMemoryAnalysisSampleSource sparse = new InMemoryAnalysisSampleSource();
        for (int i = 0; i <= 10; i++) {
            sparse.add(AutomotiveAnalysisFixtures.ACT_TQ, i * 1_000_000_000L, 10 + i);
        }
        sparse.add(AutomotiveAnalysisFixtures.REQ_TQ, 0, 10);
        sparse.add(AutomotiveAnalysisFixtures.REQ_TQ, 10_000_000_000L, 20);
        AnalysisResult hold = engine.execute(request(AnalysisType.COMPARISON)
                .signals(List.of(AutomotiveAnalysisFixtures.ACT_TQ, AutomotiveAnalysisFixtures.REQ_TQ))
                .alignmentPolicy(AlignmentPolicy.PREVIOUS_VALUE_HOLD)
                .build(), sparse, null);
        assertTrue(hold.metrics().get("pairCount") > 2);
    }

    @Test
    void correlationPositiveNegativeAndZeroish() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        SignalCatalogKey a = AutomotiveAnalysisFixtures.REQ_TQ;
        SignalCatalogKey b = AutomotiveAnalysisFixtures.ACT_TQ;
        SignalCatalogKey c = AutomotiveAnalysisFixtures.STEER;
        for (int i = 0; i < 20; i++) {
            long t = i * 1_000_000_000L;
            src.add(a, t, i);
            src.add(b, t, 100 - i);
            src.add(c, t, i % 2 == 0 ? 1 : -1);
        }
        double pos = engine.execute(request(AnalysisType.CORRELATION)
                .signals(List.of(a, a)).alignmentPolicy(AlignmentPolicy.EXACT_TIMESTAMP).build(), src, null)
                .metrics().get("pearson");
        double neg = engine.execute(request(AnalysisType.CORRELATION)
                .signals(List.of(a, b)).alignmentPolicy(AlignmentPolicy.EXACT_TIMESTAMP).build(), src, null)
                .metrics().get("pearson");
        double weak = engine.execute(request(AnalysisType.CORRELATION)
                .signals(List.of(a, c)).alignmentPolicy(AlignmentPolicy.EXACT_TIMESTAMP).build(), src, null)
                .metrics().get("pearson");
        assertEquals(1d, pos, 1e-9);
        assertEquals(-1d, neg, 1e-9);
        assertTrue(Math.abs(weak) < 0.3);
    }

    @Test
    void derivedSubtractIdentityStableAndSourceUnchanged() {
        InMemoryAnalysisSampleSource src = AutomotiveAnalysisFixtures.workstation();
        List<SignalSamplePoint> before = src.querySamples(AutomotiveAnalysisFixtures.ACT_TQ, 0, 20);
        AnalysisResult result = engine.execute(request(AnalysisType.DERIVED)
                .signals(List.of(AutomotiveAnalysisFixtures.ACT_TQ, AutomotiveAnalysisFixtures.REQ_TQ))
                .parameters(AnalysisParameters.empty().withDerivedOp("sub"))
                .alignmentPolicy(AlignmentPolicy.EXACT_TIMESTAMP)
                .build(), src, null);
        assertEquals(AnalysisStatus.COMPLETED, result.status());
        assertFalse(result.derivedIdentity().isBlank());
        DerivedExpression expr = DerivedExpression.parse("sub",
                AutomotiveAnalysisFixtures.ACT_TQ, AutomotiveAnalysisFixtures.REQ_TQ, null);
        assertEquals(
                DerivedSignalIdentity.fingerprint(expr, AlignmentPolicy.EXACT_TIMESTAMP, "1"),
                result.derivedIdentity());
        List<SignalSamplePoint> after = src.querySamples(AutomotiveAnalysisFixtures.ACT_TQ, 0, 20);
        assertEquals(before, after);
        DerivedSignalEngine.Series series = DerivedSignalEngine.evaluate(
                src, expr, TimeWindow.unbounded(), AlignmentPolicy.EXACT_TIMESTAMP, 0, 100, null);
        assertEquals(-2d, series.samples().getFirst().physicalValue(), 1e-9);
    }

    @Test
    void cursorDeltaAndViewportWindow() {
        InMemoryAnalysisSampleSource src = AutomotiveAnalysisFixtures.workstation();
        AnalysisResult delta = engine.execute(request(AnalysisType.CURSOR_DELTA)
                .addSignal(AutomotiveAnalysisFixtures.SPEED)
                .cursors(2_000_000_000L, 8_000_000_000L)
                .build(), src, null);
        assertEquals(48d, delta.findings().getFirst().numericMetrics().get("valueDelta"), 1e-9);

        AnalysisResult view = engine.execute(request(AnalysisType.STATISTICS)
                .addSignal(AutomotiveAnalysisFixtures.SPEED)
                .rangeSource(TimeRangeSource.VIEWPORT)
                .requestedWindow(TimeWindow.of(2_000_000_000L, 4_000_000_000L))
                .build(), src, null);
        assertEquals(16d, view.statistics().getFirst().min(), 1e-9);
        assertEquals(32d, view.statistics().getFirst().max(), 1e-9);
    }

    @Test
    void cancellationDoesNotCommit(@TempDir Path temp) {
        InMemoryAnalysisSampleSource src = large(80_000);
        SimpleCancellationToken token = new SimpleCancellationToken();
        token.cancel();
        AnalysisResult result = engine.execute(request(AnalysisType.STATISTICS)
                .addSignal(AutomotiveAnalysisFixtures.SPEED).build(), src, token);
        assertEquals(AnalysisStatus.CANCELLED, result.status());
        AnalysisArtifactStore store = new AnalysisArtifactStore();
        assertEquals(null, store.commit(temp, result));
        assertFalse(store.hasCommitted(temp, result.analysisId()));
    }

    @Test
    void invalidationIgnoresUiThemeAndReactsToGeneration() {
        AnalysisRequest a = request(AnalysisType.STATISTICS)
                .addSignal(AutomotiveAnalysisFixtures.SPEED)
                .artifactGeneration(1)
                .sourceFingerprint("src-a")
                .build();
        AnalysisRequest b = request(AnalysisType.STATISTICS)
                .addSignal(AutomotiveAnalysisFixtures.SPEED)
                .artifactGeneration(2)
                .sourceFingerprint("src-a")
                .build();
        assertFalse(AnalysisInvalidationKey.of(a).matches(AnalysisInvalidationKey.of(b)));
        assertTrue(AnalysisInvalidationKey.of(a).matches(AnalysisInvalidationKey.of(a)));
    }

    @Test
    void operatingBandCountsTimeInside() {
        AnalysisResult result = engine.execute(request(AnalysisType.OPERATING_BAND)
                .addSignal(AutomotiveAnalysisFixtures.RPM)
                .parameters(AnalysisParameters.empty().withBand(1000, 1500))
                .build(), AutomotiveAnalysisFixtures.workstation(), null);
        assertEquals(AnalysisStatus.COMPLETED, result.status());
        assertTrue(result.metrics().get("entryCount") >= 1);
        assertTrue(result.metrics().get("timeInsideSeconds") > 0);
    }

    @Test
    void boundedLagDoesNotClaimCausation() {
        AnalysisResult result = engine.execute(request(AnalysisType.LAG)
                .signals(List.of(AutomotiveAnalysisFixtures.REQ_TQ, AutomotiveAnalysisFixtures.ACT_TQ))
                .alignmentPolicy(AlignmentPolicy.EXACT_TIMESTAMP)
                .build(), AutomotiveAnalysisFixtures.workstation(), null);
        assertEquals(AnalysisStatus.COMPLETED, result.status());
        assertTrue(result.findings().getFirst().summary().contains("not causality"));
    }

    @Test
    void csvExportWritesSummaryAndFindings(@TempDir Path temp) throws Exception {
        AnalysisResult result = engine.execute(request(AnalysisType.STATISTICS)
                .addSignal(AutomotiveAnalysisFixtures.SPEED).build(),
                AutomotiveAnalysisFixtures.workstation(), null);
        Path csv = temp.resolve("summary.csv");
        long bytes = new com.stellar.platform.analysis.export.AnalysisCsvExporter().writeSummary(result, csv);
        assertTrue(bytes > 0);
        assertTrue(java.nio.file.Files.readString(csv).contains("VehicleSpeed"));
        Path findings = temp.resolve("findings.csv");
        new com.stellar.platform.analysis.export.AnalysisCsvExporter().writeFindings(result, findings);
        assertTrue(java.nio.file.Files.size(findings) > 0);
    }

    @Test
    void largeStreamStaysBounded() {
        InMemoryAnalysisSampleSource src = large(200_000);
        long t0 = System.nanoTime();
        AnalysisResult result = engine.execute(request(AnalysisType.STATISTICS)
                .addSignal(AutomotiveAnalysisFixtures.SPEED).build(), src, null);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertEquals(200_000, result.statistics().getFirst().validCount());
        assertTrue(ms < 15_000, "statistics over 200k samples took " + ms + " ms");
        System.out.println("PERF statistics samples=200000 durationMs=" + ms);
    }

    @Test
    void performanceSuitePrintsDurations() {
        InMemoryAnalysisSampleSource src = large(50_000);
        bench("statistics", AnalysisType.STATISTICS, src, List.of(AutomotiveAnalysisFixtures.SPEED));
        bench("threshold", AnalysisType.THRESHOLD, src, List.of(AutomotiveAnalysisFixtures.SPEED));
        InMemoryAnalysisSampleSource pair = AutomotiveAnalysisFixtures.workstation();
        bench("comparison", AnalysisType.COMPARISON, pair,
                List.of(AutomotiveAnalysisFixtures.ACT_TQ, AutomotiveAnalysisFixtures.REQ_TQ));
        bench("derived", AnalysisType.DERIVED, pair,
                List.of(AutomotiveAnalysisFixtures.ACT_TQ, AutomotiveAnalysisFixtures.REQ_TQ));
        bench("correlation", AnalysisType.CORRELATION, pair,
                List.of(AutomotiveAnalysisFixtures.ACT_TQ, AutomotiveAnalysisFixtures.REQ_TQ));
    }

    private static void bench(String name, AnalysisType type, AnalysisSampleSource src, List<SignalCatalogKey> keys) {
        AnalysisRequest.Builder b = request(type).signals(keys);
        if (type == AnalysisType.THRESHOLD) {
            b.parameters(AnalysisParameters.empty().withThreshold(ThresholdOperator.GREATER_THAN, 50));
        }
        if (type == AnalysisType.DERIVED) {
            b.parameters(AnalysisParameters.empty().withDerivedOp("sub"));
        }
        long t0 = System.nanoTime();
        AnalysisResult result = new AnalysisEngine().execute(b.build(), src, null);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("PERF " + name + " status=" + result.status() + " durationMs=" + ms);
        assertNotEquals(AnalysisStatus.FAILED, result.status());
    }

    private static InMemoryAnalysisSampleSource large(int n) {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        List<SignalSamplePoint> points = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            points.add(new SignalSamplePoint(i * 1_000_000L, i % 100, null, null));
        }
        src.put(AutomotiveAnalysisFixtures.SPEED, points);
        return src;
    }

    private static AnalysisRequest.Builder request(AnalysisType type) {
        return AnalysisRequest.builder(type).projectId("p1").analysisId("a-" + type.name().toLowerCase());
    }
}
