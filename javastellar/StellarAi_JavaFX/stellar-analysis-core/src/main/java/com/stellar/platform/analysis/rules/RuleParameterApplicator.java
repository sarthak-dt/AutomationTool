package com.stellar.platform.analysis.rules;

import java.util.Map;

/** Applies validated numeric parameter overrides to a rule condition tree. No scripting. */
public final class RuleParameterApplicator {

    private RuleParameterApplicator() {
    }

    public static DiagnosticRuleDefinition apply(DiagnosticRuleDefinition rule, Map<String, Double> overrides) {
        if (rule == null || overrides == null || overrides.isEmpty()) {
            return rule;
        }
        for (RuleParameter parameter : rule.parameters()) {
            Double raw = overrides.get(parameter.name());
            if (raw != null) {
                parameter.validate(raw);
            }
        }
        RuleCondition next = applyCondition(rule.condition(), overrides);
        if (next == rule.condition()) {
            return rule;
        }
        return new DiagnosticRuleDefinition(
                rule.ruleId(), rule.version(), rule.name(), rule.description(),
                rule.category(), rule.severity(), rule.enabled(),
                rule.inputs(), rule.parameters(), next, rule.guidance(), rule.tags(),
                rule.origin(), rule.createdAt(), rule.updatedAt());
    }

    private static RuleCondition applyCondition(RuleCondition condition, Map<String, Double> overrides) {
        Double threshold = overrides.get("threshold");
        Double durationSeconds = overrides.get("durationSeconds");
        Double rate = overrides.get("rate");
        Double pressure = overrides.get("pressureThreshold");
        Double maxDelayMs = overrides.get("maxDelayMs");
        Double minCorrelation = overrides.get("minCorrelation");
        return switch (condition) {
            case RuleCondition.Threshold t -> new RuleCondition.Threshold(
                    t.role(), t.operator(),
                    threshold == null ? t.enter() : threshold,
                    t.hysteresisExit(), t.unit(),
                    withDuration(t.temporal(), durationSeconds));
            case RuleCondition.Comparison c -> new RuleCondition.Comparison(
                    c.role(), c.op(),
                    threshold == null ? c.value() : threshold,
                    c.unit(), withDuration(c.temporal(), durationSeconds));
            case RuleCondition.Delta d -> new RuleCondition.Delta(
                    d.leftRole(), d.rightRole(), d.op(),
                    threshold == null ? d.value() : threshold,
                    d.unit(), withDuration(d.temporal(), durationSeconds));
            case RuleCondition.Rate r -> new RuleCondition.Rate(
                    r.role(), r.op(),
                    rate == null ? r.value() : rate,
                    r.unit());
            case RuleCondition.AnalysisMetric m -> new RuleCondition.AnalysisMetric(
                    m.analysisType(), m.roles(), m.metric(), m.op(),
                    minCorrelation == null ? m.value() : minCorrelation);
            case RuleCondition.Sequence s -> applySequence(s, pressure, maxDelayMs);
            default -> condition;
        };
    }

    private static RuleCondition.Sequence applySequence(
            RuleCondition.Sequence sequence, Double pressure, Double maxDelayMs
    ) {
        if (pressure == null && maxDelayMs == null) {
            return sequence;
        }
        return new RuleCondition.Sequence(sequence.steps().stream().map(step -> {
            RuleCondition c = step.condition();
            if (c instanceof RuleCondition.Comparison cmp && pressure != null) {
                c = new RuleCondition.Comparison(cmp.role(), cmp.op(), pressure, cmp.unit(), cmp.temporal());
            }
            long within = maxDelayMs == null ? step.withinNanos() : Math.round(maxDelayMs * 1_000_000d);
            return new RuleCondition.SequenceStep(c, within);
        }).toList());
    }

    private static TemporalConstraint withDuration(TemporalConstraint temporal, Double durationSeconds) {
        if (durationSeconds == null || temporal == null) {
            return temporal;
        }
        long nanos = Math.round(durationSeconds * 1_000_000_000d);
        return new TemporalConstraint(
                temporal.durationOp() == DurationOp.NONE ? DurationOp.FOR_AT_LEAST : temporal.durationOp(),
                nanos, temporal.hysteresisExit(), temporal.debounceNanos(), temporal.mergeGapNanos());
    }
}
