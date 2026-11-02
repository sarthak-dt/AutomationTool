package com.stellar.platform.analysis.workflow;

import com.stellar.platform.analysis.model.AnalysisSeverity;
import com.stellar.platform.analysis.rules.DiagnosticRuleDefinition;
import com.stellar.platform.analysis.rules.RuleCategory;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Presentation-only. Does not reprocess. */
public record RuleListFilter(String query, RuleCategory category, RuleReadiness readiness, AnalysisSeverity severity) {

    public static RuleListFilter all() {
        return new RuleListFilter("", null, null, null);
    }

    public RuleListFilter {
        query = query == null ? "" : query;
    }

    public List<DiagnosticRuleDefinition> apply(
            List<DiagnosticRuleDefinition> rules,
            Map<String, RuleReadiness> readinessById
    ) {
        if (rules == null) {
            return List.of();
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        return rules.stream()
                .filter(r -> category == null || r.category() == category)
                .filter(r -> severity == null || r.severity() == severity)
                .filter(r -> readiness == null
                        || (readinessById != null && readinessById.get(r.ruleId()) == readiness))
                .filter(r -> q.isEmpty()
                        || r.name().toLowerCase(Locale.ROOT).contains(q)
                        || r.ruleId().toLowerCase(Locale.ROOT).contains(q)
                        || r.description().toLowerCase(Locale.ROOT).contains(q))
                .toList();
    }
}
