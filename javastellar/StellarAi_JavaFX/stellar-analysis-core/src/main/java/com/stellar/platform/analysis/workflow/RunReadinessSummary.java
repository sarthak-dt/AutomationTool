package com.stellar.platform.analysis.workflow;

import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.rules.DiagnosticRuleDefinition;
import com.stellar.platform.analysis.rules.DiagnosticRulePack;
import com.stellar.platform.analysis.rules.SignalBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record RunReadinessSummary(
        int selected,
        int ready,
        int unavailable,
        int boundSignals,
        String scopeLabel,
        List<String> skippedReasons
) {
    public RunReadinessSummary {
        skippedReasons = List.copyOf(skippedReasons == null ? List.of() : skippedReasons);
        scopeLabel = scopeLabel == null ? "" : scopeLabel;
    }

    public static RunReadinessSummary of(
            DiagnosticRulePack pack,
            List<String> enabledIds,
            Map<String, SignalBinding> bindings,
            TimeWindow window
    ) {
        List<DiagnosticRuleDefinition> selected = pack == null ? List.of() : pack.rules().stream()
                .filter(r -> enabledIds == null || enabledIds.isEmpty() || enabledIds.contains(r.ruleId()))
                .toList();
        int ready = 0;
        List<String> skipped = new ArrayList<>();
        for (DiagnosticRuleDefinition rule : selected) {
            Map<String, SignalBinding> per = bindings == null ? Map.of() : bindings;
            RuleReadiness status = RuleReadinessEvaluator.evaluate(rule, per, true);
            if (status == RuleReadiness.READY) {
                ready++;
            } else {
                skipped.add(rule.ruleId() + ": " + RuleReadinessEvaluator.reason(status, per));
            }
        }
        long bound = bindings == null ? 0 : bindings.values().stream().filter(SignalBinding::bound).count();
        return new RunReadinessSummary(
                selected.size(), ready, selected.size() - ready, (int) bound,
                format(window), skipped);
    }

    private static String format(TimeWindow window) {
        if (window == null || window.isUnbounded()) {
            return "Full recording";
        }
        return String.format(Locale.ROOT, "%.3f s – %.3f s",
                window.startNanos() / 1e9, window.endNanos() / 1e9);
    }
}
