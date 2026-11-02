package com.stellar.platform.analysis.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public final class RuleValidator {

    private RuleValidator() {
    }

    public static List<RuleValidationIssue> validatePack(DiagnosticRulePack pack) {
        List<RuleValidationIssue> issues = new ArrayList<>();
        if (pack.schemaVersion() != 1) {
            issues.add(new RuleValidationIssue("SCHEMA", "Unsupported pack schemaVersion " + pack.schemaVersion()));
        }
        try {
            if (Integer.parseInt(pack.engineMinimumVersion()) > Integer.parseInt(RuleEngineVersion.CURRENT)) {
                issues.add(new RuleValidationIssue("ENGINE",
                        "Pack requires engine " + pack.engineMinimumVersion()));
            }
        } catch (NumberFormatException ex) {
            issues.add(new RuleValidationIssue("ENGINE", "Invalid engineMinimumVersion"));
        }
        Set<String> ids = new HashSet<>();
        for (DiagnosticRuleDefinition rule : pack.rules()) {
            if (!ids.add(rule.ruleId())) {
                issues.add(new RuleValidationIssue("DUP_ID", "Duplicate ruleId " + rule.ruleId()));
            }
            issues.addAll(validateRule(rule));
        }
        return List.copyOf(issues);
    }

    public static List<RuleValidationIssue> validateRule(DiagnosticRuleDefinition rule) {
        List<RuleValidationIssue> issues = new ArrayList<>();
        if (rule.version().isBlank()) {
            issues.add(new RuleValidationIssue("VERSION", "rule version required"));
        }
        Set<String> roles = new HashSet<>();
        for (RuleInput input : rule.inputs()) {
            if (!roles.add(input.role())) {
                issues.add(new RuleValidationIssue("DUP_ROLE", "Duplicate role " + input.role()));
            }
        }
        for (RuleParameter p : rule.parameters()) {
            try {
                p.validate(p.defaultValue());
            } catch (RuntimeException ex) {
                issues.add(new RuleValidationIssue("PARAM", ex.getMessage()));
            }
        }
        IdentityHashMap<RuleCondition, Boolean> seen = new IdentityHashMap<>();
        validateCondition(rule.condition(), roles, issues, seen, 0);
        return List.copyOf(issues);
    }

    private static void validateCondition(
            RuleCondition condition,
            Set<String> roles,
            List<RuleValidationIssue> issues,
            IdentityHashMap<RuleCondition, Boolean> seen,
            int depth
    ) {
        if (depth > 32) {
            issues.add(new RuleValidationIssue("CYCLE", "Condition tree too deep or cyclic"));
            return;
        }
        if (seen.put(condition, Boolean.TRUE) != null) {
            issues.add(new RuleValidationIssue("CYCLE", "Cyclic condition expression"));
            return;
        }
        switch (condition) {
            case RuleCondition.Comparison c -> requireRole(c.role(), roles, issues);
            case RuleCondition.Range c -> {
                requireRole(c.role(), roles, issues);
                if (c.max() < c.min()) {
                    issues.add(new RuleValidationIssue("RANGE", "range max < min"));
                }
            }
            case RuleCondition.StateEquals c -> requireRole(c.role(), roles, issues);
            case RuleCondition.Rate c -> requireRole(c.role(), roles, issues);
            case RuleCondition.Delta c -> {
                requireRole(c.leftRole(), roles, issues);
                requireRole(c.rightRole(), roles, issues);
            }
            case RuleCondition.Transition c -> requireRole(c.role(), roles, issues);
            case RuleCondition.Threshold c -> requireRole(c.role(), roles, issues);
            case RuleCondition.CrossSignalDifference c -> {
                requireRole(c.leftRole(), roles, issues);
                requireRole(c.rightRole(), roles, issues);
            }
            case RuleCondition.AnalysisMetric c -> {
                if (c.analysisType() == null) {
                    issues.add(new RuleValidationIssue("METRIC", "analysis type required"));
                }
                c.roles().forEach(r -> requireRole(r, roles, issues));
            }
            case RuleCondition.Sequence seq -> {
                if (seq.steps().isEmpty()) {
                    issues.add(new RuleValidationIssue("SEQ", "empty sequence"));
                }
                for (RuleCondition.SequenceStep step : seq.steps()) {
                    validateCondition(step.condition(), roles, issues, seen, depth + 1);
                }
            }
            case RuleCondition.Logical logic -> {
                if (logic.op() != LogicalOp.NOT && logic.children().isEmpty()) {
                    issues.add(new RuleValidationIssue("LOGIC", "empty " + logic.op()));
                }
                if (logic.op() == LogicalOp.NOT && logic.children().size() != 1) {
                    issues.add(new RuleValidationIssue("LOGIC", "NOT requires exactly one child"));
                }
                for (RuleCondition child : logic.children()) {
                    validateCondition(child, roles, issues, seen, depth + 1);
                }
            }
        }
    }

    private static void requireRole(String role, Set<String> roles, List<RuleValidationIssue> issues) {
        if (!roles.contains(role)) {
            issues.add(new RuleValidationIssue("ROLE", "Unknown role " + role));
        }
    }
}
