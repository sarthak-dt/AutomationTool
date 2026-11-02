package com.stellar.platform.analysis.investigation.eval;

import com.stellar.platform.analysis.investigation.EngineerEventDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic rule checks. Limits here are test values, not customer criteria. */
class EvaluationEngineTest {

    @Test
    void fourOutcomesBoundariesCoverageGapsAndPointSamples() {
        EngineerEventDefinition window = event("w", 0L, 1_000_000_000L);
        EvaluationSeries samples = series(
                sample(0L, 1.0d, true, ""),
                sample(500_000_000L, 2.0d, true, ""),
                sample(1_000_000_000L, 3.0d, true, ""));
        EvaluationResult meets = EvaluationEngine.evaluate(
                range(EvaluationRule.ComparisonOperator.MINIMUM_WITHIN_RANGE, 1.0d, 3.0d,
                        EvaluationRule.BoundaryPolicy.INCLUSIVE, EvaluationRule.BoundaryPolicy.INCLUSIVE, 1_000_000_000L, 1.0d),
                window, List.of(samples), true);
        assertEquals(EvaluationOutcome.MEETS_CRITERION, meets.outcome());
        assertTrue(meets.exploratory());
        assertTrue(meets.actualText().contains("minimum 1"));
        assertEquals("stored-sample-min 1", meets.methodText());

        EvaluationResult fails = EvaluationEngine.evaluate(
                range(EvaluationRule.ComparisonOperator.MINIMUM_WITHIN_RANGE, 1.0d, 3.0d,
                        EvaluationRule.BoundaryPolicy.EXCLUSIVE, EvaluationRule.BoundaryPolicy.INCLUSIVE, 1_000_000_000L, 1.0d),
                window, List.of(samples), true);
        assertEquals(EvaluationOutcome.DOES_NOT_MEET_CRITERION, fails.outcome());

        EvaluationResult exclusiveHigh = EvaluationEngine.evaluate(
                range(EvaluationRule.ComparisonOperator.MAXIMUM_WITHIN_RANGE, 0.0d, 3.0d,
                        EvaluationRule.BoundaryPolicy.INCLUSIVE, EvaluationRule.BoundaryPolicy.EXCLUSIVE, 1_000_000_000L, 1.0d),
                window, List.of(samples), true);
        assertEquals(EvaluationOutcome.DOES_NOT_MEET_CRITERION, exclusiveHigh.outcome());

        EvaluationResult inclusiveHigh = EvaluationEngine.evaluate(
                range(EvaluationRule.ComparisonOperator.MAXIMUM_WITHIN_RANGE, 0.0d, 3.0d,
                        EvaluationRule.BoundaryPolicy.INCLUSIVE, EvaluationRule.BoundaryPolicy.INCLUSIVE, 1_000_000_000L, 1.0d),
                window, List.of(samples), true);
        assertEquals(EvaluationOutcome.MEETS_CRITERION, inclusiveHigh.outcome());
        assertTrue(inclusiveHigh.actualText().contains("maximum 3"));

        EvaluationResult missing = EvaluationEngine.evaluate(
                range(EvaluationRule.ComparisonOperator.MINIMUM_WITHIN_RANGE, 0.0d, 5.0d,
                        EvaluationRule.BoundaryPolicy.INCLUSIVE, EvaluationRule.BoundaryPolicy.INCLUSIVE, 1_000_000_000L, 1.0d),
                window, List.of(new EvaluationSeries("OTHER", "C", "src", List.of(sample(0L, 1.0d, true, "")))), true);
        assertEquals(EvaluationOutcome.INDETERMINATE, missing.outcome());
        assertEquals(EvaluationEngine.REQUIRED_SIGNAL_MISSING, missing.reason());

        EvaluationResult unit = EvaluationEngine.evaluate(
                range(EvaluationRule.ComparisonOperator.MINIMUM_WITHIN_RANGE, 0.0d, 5.0d,
                        EvaluationRule.BoundaryPolicy.INCLUSIVE, EvaluationRule.BoundaryPolicy.INCLUSIVE, 1_000_000_000L, 1.0d),
                window, List.of(new EvaluationSeries("CH01", "K", "src", samples.samples())), true);
        assertEquals(EvaluationEngine.UNIT_INCOMPATIBLE, unit.reason());

        EvaluationSeries sparse = series(sample(0L, 1.0d, true, ""), sample(500_000_000L, 0.0d, false, ""));
        EvaluationResult coverage = EvaluationEngine.evaluate(
                range(EvaluationRule.ComparisonOperator.MINIMUM_WITHIN_RANGE, 0.0d, 5.0d,
                        EvaluationRule.BoundaryPolicy.INCLUSIVE, EvaluationRule.BoundaryPolicy.INCLUSIVE, 1_000_000_000L, 1.0d),
                window, List.of(sparse), true);
        assertEquals(EvaluationEngine.INSUFFICIENT_COVERAGE, coverage.reason());

        EvaluationResult gap = EvaluationEngine.evaluate(
                range(EvaluationRule.ComparisonOperator.MINIMUM_WITHIN_RANGE, 0.0d, 5.0d,
                        EvaluationRule.BoundaryPolicy.INCLUSIVE, EvaluationRule.BoundaryPolicy.INCLUSIVE, 100L, 1.0d),
                window, List.of(samples), true);
        assertEquals(EvaluationEngine.UNACCEPTABLE_TIMESTAMP_GAP, gap.reason());

        EvaluationResult clock = EvaluationEngine.evaluate(
                range(EvaluationRule.ComparisonOperator.MINIMUM_WITHIN_RANGE, 0.0d, 5.0d,
                        EvaluationRule.BoundaryPolicy.INCLUSIVE, EvaluationRule.BoundaryPolicy.INCLUSIVE, 1_000_000_000L, 1.0d),
                window, List.of(samples), false);
        assertEquals(EvaluationEngine.UNKNOWN_CLOCK_COMPARABILITY, clock.reason());

        EvaluationResult invalid = EvaluationEngine.evaluate(
                new EvaluationRule("r1", 1, "QA procedure", "1", "Thermal observation", List.of("CH01"), "src",
                        EvaluationRule.ComparisonOperator.MINIMUM_WITHIN_RANGE, null, 0.0d, 5.0d, "", "",
                        EvaluationRule.BoundaryPolicy.INCLUSIVE, EvaluationRule.BoundaryPolicy.INCLUSIVE,
                        null, 1_000_000_000L, 1.0d, EvaluationRule.RuleApproval.DRAFT, "", "QA-only", "t", "t"),
                window, List.of(samples), true);
        assertEquals(EvaluationEngine.INVALID_RULE, invalid.reason());

        EvaluationResult notRun = EvaluationEngine.notEvaluated(
                range(EvaluationRule.ComparisonOperator.MINIMUM_WITHIN_RANGE, 0.0d, 5.0d,
                        EvaluationRule.BoundaryPolicy.INCLUSIVE, EvaluationRule.BoundaryPolicy.INCLUSIVE, 1_000_000_000L, 1.0d),
                window);
        assertEquals(EvaluationOutcome.NOT_EVALUATED, notRun.outcome());
        assertEquals(EvaluationEngine.RULE_NOT_SELECTED, notRun.reason());

        EngineerEventDefinition point = event("p", 10L, null);
        EvaluationResult pointMeets = EvaluationEngine.evaluate(pointRule(2.5d, null), point,
                List.of(series(sample(10L, 2.5d, true, ""))), true);
        assertEquals(EvaluationOutcome.MEETS_CRITERION, pointMeets.outcome());
        EvaluationResult pointMiss = EvaluationEngine.evaluate(pointRule(2.0d, 0.1d), point,
                List.of(series(sample(10L, 2.5d, true, ""))), true);
        assertEquals(EvaluationOutcome.DOES_NOT_MEET_CRITERION, pointMiss.outcome());
        EvaluationResult pointAbsent = EvaluationEngine.evaluate(pointRule(2.5d, null), point,
                List.of(series(sample(11L, 2.5d, true, ""))), true);
        assertEquals(EvaluationEngine.INSUFFICIENT_COVERAGE, pointAbsent.reason());
        assertTrue(pointAbsent.detail().contains("not interpolated"));

        EvaluationResult state = EvaluationEngine.evaluate(stateRule(), window, List.of(series(
                sample(0L, 0.0d, true, "ON"),
                sample(1_000_000_000L, 0.0d, true, "ON"))), true);
        assertEquals(EvaluationOutcome.MEETS_CRITERION, state.outcome());
        EvaluationResult stateFail = EvaluationEngine.evaluate(stateRule(), window, List.of(series(
                sample(0L, 0.0d, true, "ON"),
                sample(1_000_000_000L, 0.0d, true, "OFF"))), true);
        assertEquals(EvaluationOutcome.DOES_NOT_MEET_CRITERION, stateFail.outcome());

        EvaluationResult threshold = EvaluationEngine.evaluate(
                range(EvaluationRule.ComparisonOperator.THRESHOLD_OVER_INTERVAL, 0.5d, 3.0d,
                        EvaluationRule.BoundaryPolicy.INCLUSIVE, EvaluationRule.BoundaryPolicy.INCLUSIVE, 1_000_000_000L, 1.0d),
                window, List.of(samples), true);
        assertEquals(EvaluationOutcome.MEETS_CRITERION, threshold.outcome());

        List<EvaluationSample> original = new ArrayList<>(List.of(sample(0L, 0.0d, false, ""), sample(1_000_000_000L, 5.0d, true, "")));
        EvaluationResult ignoredZero = EvaluationEngine.evaluate(
                range(EvaluationRule.ComparisonOperator.MINIMUM_WITHIN_RANGE, 4.0d, 6.0d,
                        EvaluationRule.BoundaryPolicy.INCLUSIVE, EvaluationRule.BoundaryPolicy.INCLUSIVE, 1_000_000_000L, 0.5d),
                window, List.of(new EvaluationSeries("CH01", "C", "src", original)), true);
        assertEquals(EvaluationOutcome.MEETS_CRITERION, ignoredZero.outcome());
        assertTrue(ignoredZero.actualText().contains("minimum 5"));
        assertEquals(0.0d, original.getFirst().value(), 0.0d);
        assertFalse(original.getFirst().usable());

        EvaluationRule decoded = EvaluationDocuments.decodeRule(EvaluationDocuments.encodeRule(meetsRule()));
        assertEquals(1, decoded.version());
        assertEquals(1.0d, decoded.expectedLow(), 0.0d);
        assertEquals(EvaluationRule.CONVERSION_POLICY, decoded.calculationMethod().isBlank() ? "" : EvaluationRule.CONVERSION_POLICY);
        EvaluationResult roundTrip = EvaluationDocuments.decodeResult(EvaluationDocuments.encodeResult(meets));
        assertEquals(EvaluationOutcome.MEETS_CRITERION, roundTrip.outcome());
        assertEquals(meets.expectedText(), roundTrip.expectedText());
        assertEquals(meets.ruleVersion(), roundTrip.ruleVersion());
    }

    private static EvaluationRule meetsRule() {
        return range(EvaluationRule.ComparisonOperator.MINIMUM_WITHIN_RANGE, 1.0d, 3.0d,
                EvaluationRule.BoundaryPolicy.INCLUSIVE, EvaluationRule.BoundaryPolicy.INCLUSIVE, 1_000_000_000L, 1.0d);
    }

    private static EngineerEventDefinition event(String id, long start, Long end) {
        return new EngineerEventDefinition(id, "Event", "Thermal observation", "src", "file.mf4",
                start, end, List.of("CH01"), "", "Engineer note", "", List.of(), false, "Draft", "", "", "");
    }

    private static EvaluationRule range(
            EvaluationRule.ComparisonOperator operator, double low, double high,
            EvaluationRule.BoundaryPolicy lowBoundary, EvaluationRule.BoundaryPolicy highBoundary,
            long gap, double coverage
    ) {
        return new EvaluationRule("r1", 1, "QA procedure", "1", "Thermal observation", List.of("CH01"), "src",
                operator, null, low, high, "", "C", lowBoundary, highBoundary, null, gap, coverage,
                EvaluationRule.RuleApproval.DRAFT, "", "QA-only fixture. Not a customer-approved criterion.", "t", "t");
    }

    private static EvaluationRule pointRule(double value, Double tolerance) {
        return new EvaluationRule("p", 1, "QA procedure", "1", "Sensor anomaly", List.of("CH01"), "src",
                EvaluationRule.ComparisonOperator.POINT_SAMPLE, value, null, null, "", "C",
                EvaluationRule.BoundaryPolicy.INCLUSIVE, EvaluationRule.BoundaryPolicy.INCLUSIVE, tolerance, null, null,
                EvaluationRule.RuleApproval.DRAFT, "", "QA-only fixture. Not a customer-approved criterion.", "t", "t");
    }

    private static EvaluationRule stateRule() {
        return new EvaluationRule("s", 1, "QA procedure", "1", "Custom event", List.of("CH01"), "src",
                EvaluationRule.ComparisonOperator.STATE_EQUALS, null, null, null, "ON", "C",
                EvaluationRule.BoundaryPolicy.INCLUSIVE, EvaluationRule.BoundaryPolicy.INCLUSIVE, null,
                1_000_000_000L, 1.0d, EvaluationRule.RuleApproval.DRAFT, "",
                "QA-only fixture. Not a customer-approved criterion.", "t", "t");
    }

    private static EvaluationSeries series(EvaluationSample... samples) {
        return new EvaluationSeries("CH01", "C", "src", List.of(samples));
    }

    private static EvaluationSample sample(long time, double value, boolean usable, String state) {
        return new EvaluationSample(time, value, usable, state);
    }
}
