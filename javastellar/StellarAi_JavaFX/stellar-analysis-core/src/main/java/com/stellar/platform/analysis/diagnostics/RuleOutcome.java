package com.stellar.platform.analysis.diagnostics;

import com.stellar.platform.analysis.rules.RuleEvaluationState;

import java.util.List;

public record RuleOutcome(
        String ruleId,
        RuleEvaluationState state,
        String reason,
        List<DiagnosticFinding> findings
) {
    public RuleOutcome {
        ruleId = ruleId == null ? "" : ruleId;
        state = state == null ? RuleEvaluationState.FAILED : state;
        reason = reason == null ? "" : reason;
        findings = List.copyOf(findings == null ? List.of() : findings);
    }
}
