package com.stellar.platform.analysis.model;

import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * Typed analysis parameters. No scripting. Missing values mean "not configured".
 */
public record AnalysisParameters(
        ThresholdOperator thresholdOperator,
        Double threshold,
        Double thresholdHigh,
        Double hysteresisExit,
        Double bandMin,
        Double bandMax,
        Double scaleConstant,
        Long nearestToleranceNanos,
        Long maxLagNanos,
        AnalysisSeverity findingSeverity,
        String expectedState,
        String derivedOp
) {

    public static AnalysisParameters empty() {
        return new AnalysisParameters(
                ThresholdOperator.GREATER_THAN, null, null, null, null, null, null,
                1_000_000L, 500_000_000L, AnalysisSeverity.OBSERVATION, "", "");
    }

    public AnalysisParameters {
        thresholdOperator = thresholdOperator == null ? ThresholdOperator.GREATER_THAN : thresholdOperator;
        nearestToleranceNanos = nearestToleranceNanos == null ? 1_000_000L : Math.max(0L, nearestToleranceNanos);
        maxLagNanos = maxLagNanos == null ? 500_000_000L : Math.max(0L, maxLagNanos);
        findingSeverity = findingSeverity == null ? AnalysisSeverity.OBSERVATION : findingSeverity;
        expectedState = expectedState == null ? "" : expectedState;
        derivedOp = derivedOp == null ? "" : derivedOp;
    }

    public OptionalDouble thresholdValue() {
        return threshold == null ? OptionalDouble.empty() : OptionalDouble.of(threshold);
    }

    public OptionalDouble hysteresisExitValue() {
        return hysteresisExit == null ? OptionalDouble.empty() : OptionalDouble.of(hysteresisExit);
    }

    public OptionalLong toleranceNanos() {
        return OptionalLong.of(nearestToleranceNanos);
    }

    public AnalysisParameters withThreshold(ThresholdOperator operator, double value) {
        return new AnalysisParameters(
                operator, value, thresholdHigh, hysteresisExit, bandMin, bandMax, scaleConstant,
                nearestToleranceNanos, maxLagNanos, findingSeverity, expectedState, derivedOp);
    }

    public AnalysisParameters withHysteresisExit(double exit) {
        return new AnalysisParameters(
                thresholdOperator, threshold, thresholdHigh, exit, bandMin, bandMax, scaleConstant,
                nearestToleranceNanos, maxLagNanos, findingSeverity, expectedState, derivedOp);
    }

    public AnalysisParameters withBand(double min, double max) {
        return new AnalysisParameters(
                thresholdOperator, threshold, thresholdHigh, hysteresisExit, min, max, scaleConstant,
                nearestToleranceNanos, maxLagNanos, findingSeverity, expectedState, derivedOp);
    }

    public AnalysisParameters withScale(double constant) {
        return new AnalysisParameters(
                thresholdOperator, threshold, thresholdHigh, hysteresisExit, bandMin, bandMax, constant,
                nearestToleranceNanos, maxLagNanos, findingSeverity, expectedState, derivedOp);
    }

    public AnalysisParameters withDerivedOp(String op) {
        return new AnalysisParameters(
                thresholdOperator, threshold, thresholdHigh, hysteresisExit, bandMin, bandMax, scaleConstant,
                nearestToleranceNanos, maxLagNanos, findingSeverity, expectedState, op);
    }
}
