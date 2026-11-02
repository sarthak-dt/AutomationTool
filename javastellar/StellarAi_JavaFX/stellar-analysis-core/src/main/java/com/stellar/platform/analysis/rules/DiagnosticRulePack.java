package com.stellar.platform.analysis.rules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record DiagnosticRulePack(
        int schemaVersion,
        String packId,
        String packVersion,
        String name,
        String description,
        String engineMinimumVersion,
        List<DiagnosticRuleDefinition> rules
) {
    public DiagnosticRulePack {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1");
        }
        packId = Objects.requireNonNull(packId, "packId").trim();
        if (packId.isEmpty()) {
            throw new IllegalArgumentException("packId required");
        }
        packVersion = packVersion == null || packVersion.isBlank() ? "1.0" : packVersion;
        name = name == null ? packId : name;
        description = description == null ? "" : description;
        engineMinimumVersion = engineMinimumVersion == null ? RuleEngineVersion.CURRENT : engineMinimumVersion;
        rules = List.copyOf(rules == null ? List.of() : rules);
    }

    public DiagnosticRuleDefinition requireRule(String ruleId) {
        return rules.stream().filter(r -> r.ruleId().equals(ruleId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown rule: " + ruleId));
    }

    public Map<String, DiagnosticRuleDefinition> byId() {
        Map<String, DiagnosticRuleDefinition> map = new LinkedHashMap<>();
        for (DiagnosticRuleDefinition rule : rules) {
            map.put(rule.ruleId(), rule);
        }
        return Map.copyOf(map);
    }
}
