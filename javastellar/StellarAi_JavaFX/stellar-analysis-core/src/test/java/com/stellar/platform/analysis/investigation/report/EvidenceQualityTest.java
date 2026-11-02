package com.stellar.platform.analysis.investigation.report;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** QA fixtures. These findings are not Tata Sierra vehicle results. */
class EvidenceQualityTest {

    @Test
    void syntheticFixturesStayNeutralAndKeepOriginalTimestamps() {
        EvidenceQuality.SampleObservation valid = EvidenceQuality.observe(
                "QA-valid", "C", "qa.mf4", "1", "qa.dbc",
                new long[] {1_000L, 2_000L, 3_000L}, new double[] {1.5, 2.5, 3.5}, null);
        assertTrue(EvidenceQuality.fromSamples(List.of(valid)).stream()
                .noneMatch(f -> f.type().equals("constant-values") || f.type().equals("unusable-samples")));
        assertEquals(1_000L, valid.firstNanos());

        EvidenceQuality.SampleObservation missing = EvidenceQuality.observe(
                "QA-missing", "C", "qa.mf4", "1", "qa.dbc",
                new long[] {10L, 20L}, new double[] {Double.NaN, 4.0}, new boolean[] {false, true});
        QualityFinding unusable = type(missing, "unusable-samples");
        assertEquals(QualityStatus.OBSERVED, unusable.status());
        assertTrue(unusable.measured().contains("unusable=1"));
        assertFalse(unusable.explanation().contains("0.0"));

        EvidenceQuality.SampleObservation clockA = EvidenceQuality.observe(
                "QA-clock-a", "C", "a.mf4", "1", "", new long[] {5L, 6L}, new double[] {1, 2}, null);
        EvidenceQuality.SampleObservation clockB = EvidenceQuality.observe(
                "QA-clock-b", "C", "b.mf4", "1", "", new long[] {9_000_000_000_000L, 9_000_000_001_000L},
                new double[] {1, 2}, null);
        List<QualityFinding> clocks = EvidenceQuality.fromSamples(List.of(clockA, clockB));
        assertTrue(clocks.stream().anyMatch(f -> f.signal().equals("QA-clock-a") && f.time().startsWith("5")));
        assertTrue(clocks.stream().anyMatch(f -> f.signal().equals("QA-clock-b") && f.time().contains("9000000000000")));

        EvidenceQuality.SampleObservation gap = EvidenceQuality.observe(
                "QA-gap", "C", "qa.mf4", "1", "",
                new long[] {0L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000_000L},
                new double[] {1, 2, 3, 4, 5, 6}, null);
        QualityFinding interval = type(gap, "measured-timestamp-interval");
        assertEquals("4000 -> 5000000", interval.time());
        assertTrue(interval.measured().contains("intervalNanos=4996000"));
        assertTrue(interval.limitations().contains("abnormal gap"));
        assertFalse(interval.explanation().toLowerCase().contains("logger"));

        EvidenceQuality.SampleObservation backward = EvidenceQuality.observe(
                "QA-order", "C", "qa.mf4", "1", "",
                new long[] {300L, 100L, 400L}, new double[] {1, 2, 3}, null);
        QualityFinding order = type(backward, "non-monotonic-timestamps");
        assertEquals("300 -> 100", order.time());
        assertTrue(order.limitations().contains("stored order"));

        EvidenceQuality.SampleObservation constant = EvidenceQuality.observe(
                "QA-constant", "C", "qa.mf4", "1", "",
                new long[] {1L, 2L, 3L}, new double[] {7.25, 7.25, 7.25}, null);
        QualityFinding held = type(constant, "constant-values");
        assertTrue(held.limitations().contains("not a failed-sensor"));
        assertTrue(held.measured().contains("7.25"));

        EvidenceQuality.SampleObservation blankUnit = EvidenceQuality.observe(
                "QA-unit", "", "qa.mf4", "1", "",
                new long[] {1L, 2L}, new double[] {1, 2}, null);
        assertEquals(QualityStatus.UNAVAILABLE, type(blankUnit, "missing-unit").status());

        QualityFinding units = EvidenceQuality.incompatibleUnits();
        assertEquals(QualityStatus.INDETERMINATE, units.status());
        assertTrue(units.limitations().contains("not treated as an incompatible-unit"));

        QualityFinding dbc = EvidenceQuality.dbcNotAttached("qa.mf4");
        assertTrue(dbc.explanation().contains("not classified as invalid"));

        QualityFinding ready = EvidenceQuality.unprocessed("qa-ready.mf4");
        assertEquals(QualityStatus.UNAVAILABLE, ready.status());
        assertTrue(ready.explanation().contains("not processed"));

        QualityFinding staleSignal = EvidenceQuality.staleSignal("bookmark-1", "missing-signal");
        assertEquals("missing-signal", staleSignal.signal());

        QualityFinding missingShot = EvidenceQuality.missingSnapshot("snap-1", "Brake view");
        assertTrue(missingShot.explanation().contains("Brake view"));
        assertTrue(missingShot.limitations().contains("not regenerated") || missingShot.explanation().contains("not regenerated"));

        QualityFinding mismatch = EvidenceQuality.snapshotMismatch("snap-2", "CH01");
        assertTrue(mismatch.explanation().contains("CH01"));

        QualityFinding gnss = EvidenceQuality.gnss("bookmark-2", "GPS evidence stale or missing", "qa.mf4", "1000");
        assertEquals("1000", gnss.time());
        assertTrue(gnss.limitations().contains("No coordinate is invented"));

        QualityFinding multi = EvidenceQuality.clocks(List.of(
                new EvidenceQuality.ClockFact("b.mf4", false, false, "", ""),
                new EvidenceQuality.ClockFact("a.mf4", true, true, "", "1000")));
        assertEquals(QualityStatus.INDETERMINATE, multi.status());
        assertTrue(multi.limitations().contains("not proof that a timestamp is wrong"));
        assertFalse(multi.explanation().contains("comparable() is true"));
        QualityFinding comparable = EvidenceQuality.clocks(List.of(
                new EvidenceQuality.ClockFact("a.mf4", true, true, "", "1000"),
                new EvidenceQuality.ClockFact("b.mf4", true, true, "", "2000")));
        assertEquals(QualityStatus.OBSERVED, comparable.status());
        assertEquals("clocks-comparable", comparable.id());
        assertTrue(comparable.explanation().contains("1000"));
        assertTrue(comparable.limitations().contains("not merged"));
        QualityFinding unavailableCoverage = EvidenceQuality.dbcCoverage(1, List.of()).getFirst();
        assertEquals(QualityStatus.UNAVAILABLE, unavailableCoverage.status());
        assertTrue(unavailableCoverage.explanation().contains("not reconstructed"));
        QualityFinding storedCoverage = EvidenceQuality.dbcCoverage(1, List.of(
                new EvidenceQuality.StoredChannelCoverage("qa.mf4", 2, 10, 7, "MAPPED"))).getFirst();
        assertEquals(QualityStatus.CALCULATED, storedCoverage.status());
        assertTrue(storedCoverage.measured().contains("unmatchedIdentities=3"));
        assertTrue(storedCoverage.limitations().contains("not reconstructed"));
        assertFalse(storedCoverage.measured().contains("0x"));

        QualityFinding missingFile = EvidenceQuality.missingSource("bookmark-3", "QA event", "absent.mf4");
        assertTrue(missingFile.explanation().contains("not in this project"));

        EvidenceQuality.SampleObservation clean = EvidenceQuality.observe(
                "QA-clean", "C", "qa.mf4", "1", "qa.dbc",
                new long[] {10L, 20L, 30L}, new double[] {1, 2, 1}, null);
        List<QualityFinding> cleanFindings = EvidenceQuality.fromSamples(List.of(clean));
        assertEquals(2, cleanFindings.size());
        assertEquals("sample-inventory", cleanFindings.getFirst().type());
        assertTrue(cleanFindings.getFirst().limitations().startsWith("Scan evidence is complete"));
        assertEquals("measured-timestamp-interval", cleanFindings.get(1).type());
        assertFalse(cleanFindings.get(1).explanation().toLowerCase().contains("abnormal"));
        EvidenceQuality.SampleObservation partial = EvidenceQuality.observe(
                "QA-partial", "C", "qa.mf4", "1", "",
                new long[] {1L, 2L}, new double[] {1, 2}, null, 10, false);
        QualityFinding partialFinding = EvidenceQuality.fromSamples(List.of(partial)).getFirst();
        assertTrue(partialFinding.limitations().contains("Scan evidence is partial"));
        assertTrue(partialFinding.limitations().contains("does not describe"));
    }

    private static QualityFinding type(EvidenceQuality.SampleObservation sample, String type) {
        return EvidenceQuality.fromSamples(List.of(sample)).stream()
                .filter(finding -> finding.type().equals(type))
                .findFirst()
                .orElseThrow();
    }
}
