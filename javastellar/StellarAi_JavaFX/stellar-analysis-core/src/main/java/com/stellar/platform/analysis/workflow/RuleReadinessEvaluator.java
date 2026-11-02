package com.stellar.platform.analysis.workflow;

import com.stellar.platform.analysis.rules.BindingState;
import com.stellar.platform.analysis.rules.DiagnosticRuleDefinition;
import com.stellar.platform.analysis.rules.RuleValidationIssue;
import com.stellar.platform.analysis.rules.RuleValidator;
import com.stellar.platform.analysis.rules.SignalBinding;

import java.util.List;
import java.util.Map;

public final class RuleReadinessEvaluator {

    private RuleReadinessEvaluator() {
    }

    public static RuleReadiness evaluate(
            DiagnosticRuleDefinition rule,
            Map<String, SignalBinding> bindings,
            boolean projectEnabled
    ) {
        if (rule == null || !rule.enabled() || !projectEnabled) {
            return RuleReadiness.DISABLED;
        }
        List<RuleValidationIssue> issues = RuleValidator.validateRule(rule);
        if (!issues.isEmpty()) {
            return RuleReadiness.INVALID_CONFIGURATION;
        }
        if (bindings == null) {
            return RuleReadiness.MISSING_INPUT;
        }
        for (var input : rule.inputs()) {
            if (!input.required()) {
                continue;
            }
            SignalBinding b = bindings.get(input.role());
            if (b == null || b.state() == BindingState.MISSING) {
                return RuleReadiness.MISSING_INPUT;
            }
            if (b.state() == BindingState.AMBIGUOUS) {
                return RuleReadiness.BINDING_REQUIRED;
            }
            if (b.state() == BindingState.INCOMPATIBLE_UNIT) {
                return RuleReadiness.INCOMPATIBLE_UNIT;
            }
            if (b.state() == BindingState.STALE) {
                return RuleReadiness.MISSING_INPUT;
            }
            if (!b.bound()) {
                return RuleReadiness.MISSING_INPUT;
            }
        }
        return RuleReadiness.READY;
    }

    public static String reason(RuleReadiness readiness, Map<String, SignalBinding> bindings) {
        if (readiness == RuleReadiness.READY) {
            return "Ready";
        }
        if (readiness == RuleReadiness.DISABLED) {
            return "Disabled for this run";
        }
        if (bindings != null) {
            for (SignalBinding b : bindings.values()) {
                if (b != null && !b.reason().isBlank() && !b.bound()) {
                    return b.reason();
                }
            }
        }
        return readiness.name();
    }
}
