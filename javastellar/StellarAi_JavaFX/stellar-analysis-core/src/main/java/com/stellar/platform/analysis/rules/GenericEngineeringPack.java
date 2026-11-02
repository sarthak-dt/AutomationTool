package com.stellar.platform.analysis.rules;

import com.stellar.platform.analysis.model.AnalysisSeverity;
import com.stellar.platform.analysis.model.AnalysisType;
import com.stellar.platform.analysis.model.ThresholdOperator;

import java.time.Instant;
import java.util.List;

/**
 * Generic engineering reference rules. Not OEM-certified diagnostics.
 */
public final class GenericEngineeringPack {

    public static final String PACK_ID = "generic-engineering";
    public static final String PACK_VERSION = "1.0";

    private GenericEngineeringPack() {
    }

    public static DiagnosticRulePack pack() {
        Instant now = Instant.EPOCH;
        return new DiagnosticRulePack(1, PACK_ID, PACK_VERSION,
                "Generic Engineering Reference",
                "Deterministic reference rules for thermal, powertrain, braking and dynamics. "
                        + "Not manufacturer-certified diagnostics.",
                RuleEngineVersion.CURRENT,
                List.of(coolant(now), torque(now), brake(now), steering(now), correlation(now)));
    }

    public static DiagnosticRuleDefinition coolant(Instant now) {
        return new DiagnosticRuleDefinition(
                "thermal.high-coolant", "1", "High coolant temperature sustained",
                "CoolantTemperature >= 105 °C for at least 10 s. Generic reference only.",
                RuleCategory.THERMAL, AnalysisSeverity.WARNING, true,
                List.of(RuleInput.required("COOLANT_TEMPERATURE", "°C",
                        "CoolantTemperature", "EngineCoolantTemp")),
                List.of(new RuleParameter("threshold", "NUMBER", "°C", 105, 80d, 150d, "Enter threshold"),
                        new RuleParameter("durationSeconds", "NUMBER", "s", 10, 1d, 120d, "Minimum duration")),
                new RuleCondition.Threshold("COOLANT_TEMPERATURE", ThresholdOperator.GREATER_OR_EQUAL, 105d, 102d,
                        "°C", new TemporalConstraint(DurationOp.FOR_AT_LEAST, 10_000_000_000L, 102d, 0L, 0L)),
                List.of("inspect cooling fan command/state",
                        "check coolant sensor plausibility",
                        "inspect coolant circulation"),
                List.of("thermal", "reference"), RuleOrigin.BUILT_IN, now, now);
    }

    public static DiagnosticRuleDefinition torque(Instant now) {
        return new DiagnosticRuleDefinition(
                "powertrain.torque-deviation", "1", "Requested vs actual torque deviation",
                "abs(RequestedTorque - ActualTorque) above threshold for a configured duration.",
                RuleCategory.POWERTRAIN, AnalysisSeverity.WARNING, true,
                List.of(RuleInput.required("REQUESTED_TORQUE", "Nm", "RequestedTorque"),
                        RuleInput.required("ACTUAL_TORQUE", "Nm", "ActualTorque")),
                List.of(new RuleParameter("threshold", "NUMBER", "Nm", 8, 1d, 500d, "Absolute deviation"),
                        new RuleParameter("durationSeconds", "NUMBER", "s", 1, 0.1d, 30d, "Minimum duration")),
                new RuleCondition.Delta("REQUESTED_TORQUE", "ACTUAL_TORQUE", CompareOp.GTE, 8d, "Nm",
                        TemporalConstraint.atLeast(1_000_000_000L)),
                List.of("inspect torque request path", "compare actuator response timing"),
                List.of("powertrain", "reference"), RuleOrigin.BUILT_IN, now, now);
    }

    public static DiagnosticRuleDefinition brake(Instant now) {
        return new DiagnosticRuleDefinition(
                "braking.response-delay", "1", "Brake pressure response delay",
                "Brake pedal PRESSED then pressure crosses threshold. Delay above limit emits a finding.",
                RuleCategory.BRAKING, AnalysisSeverity.WARNING, true,
                List.of(RuleInput.required("BRAKE_PEDAL", "", "BrakePedalState", "BrakePedalPressed"),
                        RuleInput.required("BRAKE_PRESSURE", "bar", "BrakePressure")),
                List.of(new RuleParameter("pressureThreshold", "NUMBER", "bar", 20, 1d, 200d, "Pressure threshold"),
                        new RuleParameter("maxDelayMs", "NUMBER", "ms", 500, 10d, 5000d, "Allowed delay")),
                new RuleCondition.Sequence(List.of(
                        new RuleCondition.SequenceStep(
                                new RuleCondition.Transition("BRAKE_PEDAL", "OFF", "ON"), 0L),
                        new RuleCondition.SequenceStep(
                                new RuleCondition.Comparison("BRAKE_PRESSURE", CompareOp.GTE, 20d, "bar",
                                        TemporalConstraint.none()),
                                500_000_000L)
                )),
                List.of("confirm pedal switch and pressure sensor timing",
                        "inspect hydraulic response if delay repeats"),
                List.of("braking", "reference"), RuleOrigin.BUILT_IN, now, now);
    }

    public static DiagnosticRuleDefinition steering(Instant now) {
        return new DiagnosticRuleDefinition(
                "dynamics.high-steering-rate", "1", "High steering rate",
                "SteeringAngle rate above a configured engineering limit.",
                RuleCategory.VEHICLE_DYNAMICS, AnalysisSeverity.OBSERVATION, true,
                List.of(RuleInput.required("STEERING_ANGLE", "deg", "SteeringAngle")),
                List.of(new RuleParameter("rate", "NUMBER", "deg/s", 10, 1d, 1000d, "Rate limit")),
                new RuleCondition.Rate("STEERING_ANGLE", CompareOp.GT, 10d, "deg/s"),
                List.of("review driver/steering input around the timestamp"),
                List.of("dynamics", "reference"), RuleOrigin.BUILT_IN, now, now);
    }

    public static DiagnosticRuleDefinition correlation(Instant now) {
        return new DiagnosticRuleDefinition(
                "powertrain.torque-correlation", "1", "Requested/actual torque correlation",
                "Pearson correlation below configured limit in the analysis window. Not causation.",
                RuleCategory.POWERTRAIN, AnalysisSeverity.OBSERVATION, true,
                List.of(RuleInput.required("REQUESTED_TORQUE", "Nm", "RequestedTorque"),
                        RuleInput.required("ACTUAL_TORQUE", "Nm", "ActualTorque")),
                List.of(new RuleParameter("minCorrelation", "NUMBER", "", 0.5, -1d, 1d, "Minimum Pearson r")),
                new RuleCondition.AnalysisMetric(AnalysisType.CORRELATION,
                        List.of("REQUESTED_TORQUE", "ACTUAL_TORQUE"), "pearson", CompareOp.LT, 0.5d),
                List.of("correlation is not causation"),
                List.of("powertrain", "reference"), RuleOrigin.BUILT_IN, now, now);
    }
}
