package com.stellar.platform.analysis.workflow;

import com.stellar.platform.analysis.diagnostics.DiagnosticFinding;
import com.stellar.platform.analysis.model.AnalysisSeverity;
import com.stellar.platform.analysis.rules.RuleCategory;

import java.util.List;
import java.util.Locale;

/** Presentation-only filter. Does not reprocess measurements. */
public record FindingListFilter(
        AnalysisSeverity severity,
        RuleCategory category,
        String ruleId,
        String query
) {
    public static FindingListFilter all() {
        return new FindingListFilter(null, null, "", "");
    }

    public FindingListFilter {
        ruleId = ruleId == null ? "" : ruleId;
        query = query == null ? "" : query;
    }

    public List<DiagnosticFinding> apply(List<DiagnosticFinding> findings) {
        if (findings == null) {
            return List.of();
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        return findings.stream()
                .filter(f -> severity == null || f.severity() == severity)
                .filter(f -> category == null || f.category() == category)
                .filter(f -> ruleId.isBlank() || f.ruleId().equals(ruleId))
                .filter(f -> q.isEmpty()
                        || f.summary().toLowerCase(Locale.ROOT).contains(q)
                        || f.ruleId().toLowerCase(Locale.ROOT).contains(q))
                .toList();
    }
}
