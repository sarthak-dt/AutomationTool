package com.stellar.platform.analysis.workflow;

import com.stellar.platform.analysis.rules.DiagnosticRuleDefinition;
import com.stellar.platform.analysis.rules.DiagnosticRulePack;
import com.stellar.platform.analysis.rules.RuleEngineVersion;
import com.stellar.platform.analysis.rules.RuleOrigin;

import java.util.ArrayList;
import java.util.List;

public final class ProjectCustomPack {

    public static final String PACK_ID = "project-custom";
    public static final String PACK_VERSION = "1.0";
    public static final String PACK_NAME = "Project Custom Rules";

    private ProjectCustomPack() {
    }

    public static DiagnosticRulePack empty() {
        return new DiagnosticRulePack(1, PACK_ID, PACK_VERSION, PACK_NAME,
                "Project-local custom diagnostic rules.", RuleEngineVersion.CURRENT, List.of());
    }

    public static DiagnosticRulePack of(List<DiagnosticRuleDefinition> rules) {
        List<DiagnosticRuleDefinition> custom = new ArrayList<>();
        if (rules != null) {
            for (DiagnosticRuleDefinition rule : rules) {
                custom.add(rule.origin() == RuleOrigin.PROJECT_CUSTOM
                        ? rule
                        : rule.asProjectCustom(rule.ruleId()));
            }
        }
        return new DiagnosticRulePack(1, PACK_ID, PACK_VERSION, PACK_NAME,
                "Project-local custom diagnostic rules.", RuleEngineVersion.CURRENT, custom);
    }

    public static DiagnosticRulePack withRule(DiagnosticRulePack pack, DiagnosticRuleDefinition rule) {
        DiagnosticRulePack base = pack == null ? empty() : pack;
        List<DiagnosticRuleDefinition> next = new ArrayList<>();
        boolean replaced = false;
        for (DiagnosticRuleDefinition existing : base.rules()) {
            if (existing.ruleId().equals(rule.ruleId())) {
                next.add(rule);
                replaced = true;
            } else {
                next.add(existing);
            }
        }
        if (!replaced) {
            next.add(rule);
        }
        return new DiagnosticRulePack(base.schemaVersion(), base.packId(), base.packVersion(),
                base.name(), base.description(), base.engineMinimumVersion(), next);
    }

    public static DiagnosticRulePack withoutRule(DiagnosticRulePack pack, String ruleId) {
        if (pack == null || ruleId == null) {
            return empty();
        }
        List<DiagnosticRuleDefinition> next = pack.rules().stream()
                .filter(r -> !r.ruleId().equals(ruleId))
                .toList();
        return new DiagnosticRulePack(pack.schemaVersion(), pack.packId(), pack.packVersion(),
                pack.name(), pack.description(), pack.engineMinimumVersion(), next);
    }
}
