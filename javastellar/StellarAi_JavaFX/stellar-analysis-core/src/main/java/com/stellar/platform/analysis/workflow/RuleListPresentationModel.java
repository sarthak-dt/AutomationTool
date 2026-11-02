package com.stellar.platform.analysis.workflow;

import com.stellar.platform.analysis.rules.DiagnosticRuleDefinition;
import com.stellar.platform.analysis.rules.SignalBinding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Presentation-only window over a rule list. Does not instantiate UI controls.
 * Suitable for 10 / 100 / 500 rules without materialising nodes.
 */
public final class RuleListPresentationModel {

    private List<DiagnosticRuleDefinition> source = List.of();
    private Map<String, RuleReadiness> readiness = Map.of();
    private RuleListFilter filter = RuleListFilter.all();

    public void setSource(List<DiagnosticRuleDefinition> rules) {
        source = rules == null ? List.of() : List.copyOf(rules);
    }

    public void setReadiness(Map<String, RuleReadiness> next) {
        readiness = next == null ? Map.of() : Map.copyOf(next);
    }

    public void setFilter(RuleListFilter next) {
        filter = next == null ? RuleListFilter.all() : next;
    }

    public List<DiagnosticRuleDefinition> view() {
        return filter.apply(source, readiness);
    }

    public int sourceSize() {
        return source.size();
    }

    public int viewSize() {
        return view().size();
    }

    public static Map<String, RuleReadiness> readinessOf(
            List<DiagnosticRuleDefinition> rules,
            Map<String, SignalBinding> bindings,
            Map<String, Boolean> enabledById
    ) {
        Map<String, RuleReadiness> out = new LinkedHashMap<>();
        if (rules == null) {
            return Map.of();
        }
        for (DiagnosticRuleDefinition rule : rules) {
            boolean enabled = enabledById == null
                    || enabledById.getOrDefault(rule.ruleId(), rule.enabled());
            out.put(rule.ruleId(), RuleReadinessEvaluator.evaluate(rule, bindings, enabled));
        }
        return Map.copyOf(out);
    }

    public static List<DiagnosticRuleDefinition> window(
            List<DiagnosticRuleDefinition> view, int offset, int limit
    ) {
        if (view == null || view.isEmpty() || limit <= 0) {
            return List.of();
        }
        int from = Math.max(0, offset);
        if (from >= view.size()) {
            return List.of();
        }
        int to = Math.min(view.size(), from + limit);
        return new ArrayList<>(view.subList(from, to));
    }
}
