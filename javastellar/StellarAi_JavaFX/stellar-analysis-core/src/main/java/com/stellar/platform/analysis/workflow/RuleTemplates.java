package com.stellar.platform.analysis.workflow;

import com.stellar.platform.analysis.model.AnalysisSeverity;
import com.stellar.platform.analysis.model.ThresholdOperator;
import com.stellar.platform.analysis.rules.CompareOp;
import com.stellar.platform.analysis.rules.DiagnosticRuleDefinition;
import com.stellar.platform.analysis.rules.DurationOp;
import com.stellar.platform.analysis.rules.LogicalOp;
import com.stellar.platform.analysis.rules.RuleCategory;
import com.stellar.platform.analysis.rules.RuleCondition;
import com.stellar.platform.analysis.rules.RuleInput;
import com.stellar.platform.analysis.rules.RuleOrigin;
import com.stellar.platform.analysis.rules.RuleParameter;
import com.stellar.platform.analysis.rules.TemporalConstraint;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Authoring templates that produce normal Phase 5 {@link DiagnosticRuleDefinition}s.
 * Not a second execution engine.
 */
public final class RuleTemplates {

    private RuleTemplates() {
    }

    public static DiagnosticRuleDefinition sustainedThreshold(
            String name,
            String role,
            double threshold,
            String unit,
            double durationSeconds,
            double hysteresisDelta,
            AnalysisSeverity severity,
            RuleCategory category
    ) {
        requireFinite(threshold, durationSeconds, hysteresisDelta);
        if (durationSeconds <= 0) {
            throw new IllegalArgumentException("duration must be > 0 seconds");
        }
        String id = customId("sustained");
        Double exit = threshold - Math.abs(hysteresisDelta);
        TemporalConstraint temporal = new TemporalConstraint(
                DurationOp.FOR_AT_LEAST, seconds(durationSeconds), exit, 0L, 0L);
        return new DiagnosticRuleDefinition(
                id, "1", blankTo(name, "Sustained threshold"),
                role + " " + threshold + " " + unit + " for " + durationSeconds + " s",
                category, severity, true,
                List.of(RuleInput.required(role, unit)),
                List.of(
                        param("threshold", unit, threshold, null, null),
                        param("durationSeconds", "s", durationSeconds, 0.001, 3600d),
                        param("hysteresis", unit, hysteresisDelta, 0d, null)),
                new RuleCondition.Threshold(role, ThresholdOperator.GREATER_OR_EQUAL, threshold, exit, unit, temporal),
                List.of(), List.of("template", "sustained"),
                RuleOrigin.PROJECT_CUSTOM, Instant.now(), Instant.now());
    }

    public static DiagnosticRuleDefinition difference(
            String name,
            String leftRole,
            String rightRole,
            double threshold,
            String unit,
            double durationSeconds,
            AnalysisSeverity severity
    ) {
        requireFinite(threshold, durationSeconds);
        String id = customId("delta");
        return new DiagnosticRuleDefinition(
                id, "1", blankTo(name, "Signal difference"),
                "abs(" + leftRole + " - " + rightRole + ") >= " + threshold + " " + unit,
                RuleCategory.POWERTRAIN, severity, true,
                List.of(RuleInput.required(leftRole, unit), RuleInput.required(rightRole, unit)),
                List.of(param("threshold", unit, threshold, 0d, null),
                        param("durationSeconds", "s", durationSeconds, 0.001, 3600d)),
                new RuleCondition.Delta(leftRole, rightRole, CompareOp.GTE, threshold, unit,
                        TemporalConstraint.atLeast(seconds(durationSeconds))),
                List.of(), List.of("template", "difference"),
                RuleOrigin.PROJECT_CUSTOM, Instant.now(), Instant.now());
    }

    public static DiagnosticRuleDefinition stateAndCondition(
            String name,
            String stateRole,
            String state,
            String valueRole,
            CompareOp op,
            double threshold,
            String unit,
            double durationMs,
            AnalysisSeverity severity
    ) {
        requireFinite(threshold, durationMs);
        String id = customId("state");
        RuleCondition and = new RuleCondition.Logical(LogicalOp.AND, List.of(
                new RuleCondition.StateEquals(stateRole, state, false),
                new RuleCondition.Comparison(valueRole, op, threshold, unit,
                        TemporalConstraint.atLeast(millis(durationMs)))));
        return new DiagnosticRuleDefinition(
                id, "1", blankTo(name, "State + condition"),
                stateRole + "=" + state + " AND " + valueRole + " " + op.symbol() + " " + threshold,
                RuleCategory.CUSTOM, severity, true,
                List.of(RuleInput.required(stateRole, ""), RuleInput.required(valueRole, unit)),
                List.of(param("threshold", unit, threshold, null, null),
                        param("durationMs", "ms", durationMs, 0d, 60_000d)),
                and, List.of(), List.of("template", "state"),
                RuleOrigin.PROJECT_CUSTOM, Instant.now(), Instant.now());
    }

    public static DiagnosticRuleDefinition responseDelay(
            String name,
            String triggerRole,
            String fromState,
            String toState,
            String responseRole,
            double responseThreshold,
            String unit,
            double maxDelayMs,
            AnalysisSeverity severity
    ) {
        requireFinite(responseThreshold, maxDelayMs);
        if (maxDelayMs < 0) {
            throw new IllegalArgumentException("maxDelayMs must be >= 0");
        }
        String id = customId("delay");
        return new DiagnosticRuleDefinition(
                id, "1", blankTo(name, "Response delay"),
                triggerRole + " " + fromState + "→" + toState + " then " + responseRole
                        + " within " + maxDelayMs + " ms",
                RuleCategory.BRAKING, severity, true,
                List.of(RuleInput.required(triggerRole, ""), RuleInput.required(responseRole, unit)),
                List.of(param("pressureThreshold", unit, responseThreshold, 0d, null),
                        param("maxDelayMs", "ms", maxDelayMs, 0d, 30_000d)),
                new RuleCondition.Sequence(List.of(
                        new RuleCondition.SequenceStep(
                                new RuleCondition.Transition(triggerRole, fromState, toState), 0L),
                        new RuleCondition.SequenceStep(
                                new RuleCondition.Comparison(responseRole, CompareOp.GTE, responseThreshold, unit,
                                        TemporalConstraint.none()),
                                millis(maxDelayMs)))),
                List.of(), List.of("template", "delay"),
                RuleOrigin.PROJECT_CUSTOM, Instant.now(), Instant.now());
    }

    public static DiagnosticRuleDefinition rateLimit(
            String name,
            String role,
            double rate,
            String unit,
            AnalysisSeverity severity
    ) {
        requireFinite(rate);
        String id = customId("rate");
        return new DiagnosticRuleDefinition(
                id, "1", blankTo(name, "Rate limit"),
                role + " rate > " + rate + " " + unit,
                RuleCategory.VEHICLE_DYNAMICS, severity, true,
                List.of(RuleInput.required(role, unit.contains("/") ? unit.substring(0, unit.indexOf('/')) : unit)),
                List.of(param("rate", unit, rate, 0d, null)),
                new RuleCondition.Rate(role, CompareOp.GT, rate, unit),
                List.of(), List.of("template", "rate"),
                RuleOrigin.PROJECT_CUSTOM, Instant.now(), Instant.now());
    }

    public static DiagnosticRuleDefinition operatingRange(
            String name,
            String role,
            double min,
            double max,
            String unit,
            boolean inside,
            AnalysisSeverity severity
    ) {
        requireFinite(min, max);
        if (max < min) {
            throw new IllegalArgumentException("range max < min");
        }
        String id = customId("range");
        return new DiagnosticRuleDefinition(
                id, "1", blankTo(name, "Operating range"),
                role + (inside ? " inside " : " outside ") + min + "–" + max + " " + unit,
                RuleCategory.CUSTOM, severity, true,
                List.of(RuleInput.required(role, unit)),
                List.of(param("min", unit, min, null, null), param("max", unit, max, null, null)),
                new RuleCondition.Range(role, inside, min, max, unit, TemporalConstraint.none()),
                List.of(), List.of("template", "range"),
                RuleOrigin.PROJECT_CUSTOM, Instant.now(), Instant.now());
    }

    public static DiagnosticRuleDefinition incrementVersion(DiagnosticRuleDefinition rule) {
        Objects.requireNonNull(rule, "rule");
        String next;
        try {
            next = Integer.toString(Integer.parseInt(rule.version()) + 1);
        } catch (NumberFormatException ex) {
            next = rule.version() + ".1";
        }
        return new DiagnosticRuleDefinition(
                rule.ruleId(), next, rule.name(), rule.description(), rule.category(), rule.severity(),
                rule.enabled(), rule.inputs(), rule.parameters(), rule.condition(), rule.guidance(),
                rule.tags(), rule.origin(), rule.createdAt(), Instant.now());
    }

    public static DiagnosticRuleDefinition withEnabled(DiagnosticRuleDefinition rule, boolean enabled) {
        return new DiagnosticRuleDefinition(
                rule.ruleId(), rule.version(), rule.name(), rule.description(), rule.category(), rule.severity(),
                enabled, rule.inputs(), rule.parameters(), rule.condition(), rule.guidance(),
                rule.tags(), rule.origin(), rule.createdAt(), rule.updatedAt());
    }

    private static RuleParameter param(String name, String unit, double value, Double min, Double max) {
        return new RuleParameter(name, "NUMBER", unit, value, min, max, name);
    }

    private static void requireFinite(double... values) {
        for (double v : values) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("parameter must be finite");
            }
        }
    }

    private static long seconds(double s) {
        return Math.round(s * 1_000_000_000d);
    }

    private static long millis(double ms) {
        return Math.round(ms * 1_000_000d);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String customId(String prefix) {
        return "project." + prefix + "." + UUID.randomUUID().toString().substring(0, 8)
                .toLowerCase(Locale.ROOT);
    }
}
