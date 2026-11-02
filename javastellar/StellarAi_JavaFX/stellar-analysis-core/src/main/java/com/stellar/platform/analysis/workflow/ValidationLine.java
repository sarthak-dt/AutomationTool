package com.stellar.platform.analysis.workflow;

import com.stellar.platform.analysis.rules.BindingState;
import com.stellar.platform.analysis.rules.DiagnosticRuleDefinition;
import com.stellar.platform.analysis.rules.RuleValidationIssue;
import com.stellar.platform.analysis.rules.RuleValidator;
import com.stellar.platform.analysis.rules.SignalBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record ValidationLine(boolean ok, boolean warning, String message) {

    public String label() {
        if (ok) {
            return "OK  " + message;
        }
        if (warning) {
            return "WARN  " + message;
        }
        return "FAIL  " + message;
    }

    public static List<ValidationLine> forRule(
            DiagnosticRuleDefinition rule,
            Map<String, SignalBinding> bindings
    ) {
        List<ValidationLine> lines = new ArrayList<>();
        if (rule == null) {
            return List.of(new ValidationLine(false, false, "No rule selected"));
        }
        lines.add(new ValidationLine(!rule.ruleId().isBlank(), false,
                rule.ruleId().isBlank() ? "Rule ID missing" : "Rule ID valid"));
        List<RuleValidationIssue> issues = RuleValidator.validateRule(rule);
        if (issues.isEmpty()) {
            lines.add(new ValidationLine(true, false, "Condition tree valid"));
        } else {
            issues.forEach(i -> lines.add(new ValidationLine(false, false, i.message())));
        }
        if (bindings != null) {
            bindings.forEach((role, b) -> {
                if (b.bound()) {
                    lines.add(new ValidationLine(true, false,
                            role + " bound (" + b.signalUnit() + ")"));
                } else if (b.state() == BindingState.AMBIGUOUS) {
                    lines.add(new ValidationLine(false, true, role + " not bound — " + b.reason()));
                } else {
                    lines.add(new ValidationLine(false, false, role + " — " + b.reason()));
                }
            });
        }
        return List.copyOf(lines);
    }
}
