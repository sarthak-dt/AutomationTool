package com.stellar.platform.analysis.workflow;

import com.stellar.platform.analysis.rules.DiagnosticRuleDefinition;
import com.stellar.platform.analysis.rules.DurationOp;
import com.stellar.platform.analysis.rules.LogicalOp;
import com.stellar.platform.analysis.rules.RuleCondition;
import com.stellar.platform.analysis.rules.SignalBinding;
import com.stellar.platform.analysis.rules.TemporalConstraint;

import java.util.Locale;
import java.util.Map;

/** Human-readable effective rule. Not a generative explanation. */
public final class RulePreviewFormatter {

    private RulePreviewFormatter() {
    }

    public static String preview(DiagnosticRuleDefinition rule, Map<String, SignalBinding> bindings) {
        if (rule == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("IF\n");
        sb.append(describe(rule.condition(), 1)).append('\n');
        sb.append("Severity: ").append(rule.severity()).append('\n');
        sb.append("Category: ").append(rule.category()).append('\n');
        sb.append("Origin: ").append(rule.origin()).append('\n');
        if (bindings != null && !bindings.isEmpty()) {
            sb.append("Bindings:\n");
            bindings.forEach((role, b) -> sb.append("  ").append(role).append(" → ")
                    .append(b.bound() ? b.key().signalName() + " [" + b.signalUnit() + "]" : b.state())
                    .append('\n'));
        }
        return sb.toString();
    }

    private static String describe(RuleCondition condition, int indent) {
        String pad = "  ".repeat(indent);
        return switch (condition) {
            case RuleCondition.Threshold t -> pad + t.role() + " " + t.operator() + " " + t.enter()
                    + " " + t.unit() + temporal(t.temporal())
                    + (t.hysteresisExit() == null ? "" : "\n" + pad + "EXIT when <= " + t.hysteresisExit() + " " + t.unit());
            case RuleCondition.Comparison c -> pad + c.role() + " " + c.op().symbol() + " " + c.value()
                    + " " + c.unit() + temporal(c.temporal());
            case RuleCondition.Range r -> pad + r.role() + (r.inside() ? " inside " : " outside ")
                    + r.min() + "–" + r.max() + " " + r.unit() + temporal(r.temporal());
            case RuleCondition.Delta d -> pad + "abs(" + d.leftRole() + " - " + d.rightRole() + ") "
                    + d.op().symbol() + " " + d.value() + " " + d.unit() + temporal(d.temporal());
            case RuleCondition.Rate r -> pad + r.role() + " rate " + r.op().symbol() + " " + r.value() + " " + r.unit();
            case RuleCondition.Transition t -> pad + t.role() + " " + t.fromState() + " → " + t.toState();
            case RuleCondition.StateEquals s -> pad + s.role() + (s.negate() ? " != " : " == ") + s.state();
            case RuleCondition.Sequence seq -> {
                StringBuilder sb = new StringBuilder(pad + "SEQUENCE");
                for (int i = 0; i < seq.steps().size(); i++) {
                    sb.append('\n').append(describe(seq.steps().get(i).condition(), indent + 1));
                    if (i > 0) {
                        sb.append(String.format(Locale.ROOT, "  (within %.0f ms)",
                                seq.steps().get(i).withinNanos() / 1_000_000d));
                    }
                }
                yield sb.toString();
            }
            case RuleCondition.Logical logic -> {
                StringBuilder sb = new StringBuilder(pad + logic.op());
                if (logic.op() == LogicalOp.NOT && logic.children().size() == 1) {
                    sb.append('\n').append(describe(logic.children().getFirst(), indent + 1));
                } else {
                    logic.children().forEach(c -> sb.append('\n').append(describe(c, indent + 1)));
                }
                yield sb.toString();
            }
            case RuleCondition.CrossSignalDifference d -> pad + "diff(" + d.leftRole() + "," + d.rightRole()
                    + ") " + d.op().symbol() + " " + d.value();
            case RuleCondition.AnalysisMetric m -> pad + m.metric() + " " + m.op().symbol() + " " + m.value();
        };
    }

    private static String temporal(TemporalConstraint t) {
        if (t == null || !t.hasDuration()) {
            return "";
        }
        double s = t.durationNanos() / 1_000_000_000d;
        String op = t.durationOp() == DurationOp.FOR_AT_MOST ? "FOR <= " : "FOR >= ";
        return "\n    " + op + String.format(Locale.ROOT, "%.3f s", s);
    }
}
