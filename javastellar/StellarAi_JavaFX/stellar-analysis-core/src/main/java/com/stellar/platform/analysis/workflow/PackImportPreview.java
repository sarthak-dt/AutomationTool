package com.stellar.platform.analysis.workflow;

import com.stellar.platform.analysis.rules.DiagnosticRuleDefinition;
import com.stellar.platform.analysis.rules.DiagnosticRulePack;
import com.stellar.platform.analysis.rules.RulePackJson;
import com.stellar.platform.analysis.rules.RuleValidationIssue;
import com.stellar.platform.analysis.rules.RuleValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record PackImportPreview(
        DiagnosticRulePack pack,
        List<RuleValidationIssue> issues,
        List<String> conflicts,
        List<String> warnings,
        boolean importable
) {
    public PackImportPreview {
        issues = List.copyOf(issues == null ? List.of() : issues);
        conflicts = List.copyOf(conflicts == null ? List.of() : conflicts);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    public static PackImportPreview parse(String json, DiagnosticRulePack existing) {
        DiagnosticRulePack incoming;
        try {
            incoming = RulePackJson.read(json);
        } catch (RuntimeException ex) {
            return new PackImportPreview(null, List.of(), List.of(),
                    List.of(ex.getMessage() == null ? "Parse failed" : ex.getMessage()), false);
        }
        List<RuleValidationIssue> issues = RuleValidator.validatePack(incoming);
        List<String> conflicts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (existing != null
                && Objects.equals(existing.packId(), incoming.packId())
                && Objects.equals(existing.packVersion(), incoming.packVersion())) {
            conflicts.add("Same pack ID and version already exist: "
                    + incoming.packId() + " " + incoming.packVersion());
        }
        if (existing != null && Objects.equals(existing.packId(), incoming.packId())) {
            for (DiagnosticRuleDefinition rule : incoming.rules()) {
                existing.rules().stream()
                        .filter(r -> r.ruleId().equals(rule.ruleId()) && !sameContent(r, rule))
                        .findFirst()
                        .ifPresent(prev -> conflicts.add(
                                "Rule " + rule.ruleId() + " exists with different content (v" + prev.version() + ")"));
            }
        }
        boolean importable = issues.isEmpty() && conflicts.isEmpty();
        if (!issues.isEmpty()) {
            warnings.add("Pack failed semantic validation");
        }
        return new PackImportPreview(incoming, issues, conflicts, warnings, importable);
    }

    private static boolean sameContent(DiagnosticRuleDefinition a, DiagnosticRuleDefinition b) {
        return a.version().equals(b.version())
                && a.condition().getClass().equals(b.condition().getClass())
                && a.name().equals(b.name());
    }
}
