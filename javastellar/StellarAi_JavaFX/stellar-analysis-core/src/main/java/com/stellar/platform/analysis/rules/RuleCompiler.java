package com.stellar.platform.analysis.rules;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class RuleCompiler {

    private RuleCompiler() {
    }

    public static CompiledDiagnosticRule compile(
            DiagnosticRuleDefinition rule,
            Map<String, SignalBinding> bindings
    ) {
        List<RuleValidationIssue> issues = RuleValidator.validateRule(rule);
        if (!issues.isEmpty()) {
            throw new IllegalArgumentException("Invalid rule " + rule.ruleId() + ": " + issues.getFirst().message());
        }
        String fp = fingerprint(rule, bindings);
        return new CompiledDiagnosticRule(rule, bindings, fp);
    }

    public static String fingerprint(DiagnosticRuleDefinition rule, Map<String, SignalBinding> bindings) {
        StringBuilder sb = new StringBuilder(rule.ruleId()).append('|').append(rule.version())
                .append('|').append(RuleEngineVersion.CURRENT);
        if (bindings != null) {
            bindings.forEach((k, v) -> sb.append('|').append(k).append('=')
                    .append(v.state()).append(':')
                    .append(v.key() == null ? "" : v.key()));
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 required", e);
        }
    }
}
