package com.stellar.platform.analysis.rules;

import java.util.Map;
import java.util.Objects;

public record CompiledDiagnosticRule(
        DiagnosticRuleDefinition definition,
        Map<String, SignalBinding> bindings,
        String bindingFingerprint
) {
    public CompiledDiagnosticRule {
        definition = Objects.requireNonNull(definition, "definition");
        bindings = Map.copyOf(bindings == null ? Map.of() : bindings);
        bindingFingerprint = bindingFingerprint == null ? "" : bindingFingerprint;
    }

    public boolean runnable() {
        return definition.inputs().stream()
                .filter(RuleInput::required)
                .allMatch(i -> bindings.get(i.role()) != null && bindings.get(i.role()).bound());
    }
}
