package com.stellar.platform.analysis.rules;

import com.stellar.platform.analysis.model.AnalysisSeverity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record DiagnosticRuleDefinition(
        String ruleId,
        String version,
        String name,
        String description,
        RuleCategory category,
        AnalysisSeverity severity,
        boolean enabled,
        List<RuleInput> inputs,
        List<RuleParameter> parameters,
        RuleCondition condition,
        List<String> guidance,
        List<String> tags,
        RuleOrigin origin,
        Instant createdAt,
        Instant updatedAt
) {
    public DiagnosticRuleDefinition {
        ruleId = Objects.requireNonNull(ruleId, "ruleId").trim();
        if (ruleId.isEmpty()) {
            throw new IllegalArgumentException("ruleId required");
        }
        version = version == null || version.isBlank() ? "1" : version.trim();
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        category = category == null ? RuleCategory.CUSTOM : category;
        severity = severity == null ? AnalysisSeverity.OBSERVATION : severity;
        inputs = List.copyOf(inputs == null ? List.of() : inputs);
        parameters = List.copyOf(parameters == null ? List.of() : parameters);
        condition = Objects.requireNonNull(condition, "condition");
        guidance = List.copyOf(guidance == null ? List.of() : guidance);
        tags = List.copyOf(tags == null ? List.of() : tags);
        origin = origin == null ? RuleOrigin.PROJECT_CUSTOM : origin;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public DiagnosticRuleDefinition withOrigin(RuleOrigin next) {
        return new DiagnosticRuleDefinition(
                ruleId, version, name, description, category, severity, enabled,
                inputs, parameters, condition, guidance, tags, next, createdAt, updatedAt);
    }

    public DiagnosticRuleDefinition asProjectCustom(String newId) {
        return new DiagnosticRuleDefinition(
                newId, version, name, description, category, severity, enabled,
                inputs, parameters, condition, guidance, tags, RuleOrigin.PROJECT_CUSTOM,
                Instant.now(), Instant.now());
    }
}
