package com.stellar.platform.analysis.invalidate;

import com.stellar.platform.analysis.diagnostics.DiagnosticRunRequest;
import com.stellar.platform.analysis.rules.RuleEngineVersion;
import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Diagnostic results are stale only when data, bindings, rules or window change.
 * Theme, inspector collapse and graph layout are not dependencies.
 */
public record DiagnosticInvalidationKey(
        long artifactGeneration,
        String sourceFingerprint,
        String packId,
        String packVersion,
        String ruleDigest,
        String bindingDigest,
        String windowDigest,
        String engineVersion
) {
    public static DiagnosticInvalidationKey of(DiagnosticRunRequest request) {
        Objects.requireNonNull(request, "request");
        String rules = request.ruleIds().isEmpty()
                ? request.pack().rules().stream().map(r -> r.ruleId() + "@" + r.version()).collect(Collectors.joining(","))
                : String.join(",", request.ruleIds());
        String bindings = request.explicitBindings().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + key(e.getValue()))
                .collect(Collectors.joining(";"));
        return new DiagnosticInvalidationKey(
                request.artifactGeneration(),
                request.sourceFingerprint(),
                request.pack().packId(),
                request.pack().packVersion(),
                rules,
                bindings,
                request.window().startNanos() + ":" + request.window().endNanos() + ":" + request.rangeSource(),
                RuleEngineVersion.CURRENT
        );
    }

    public String compact() {
        return artifactGeneration + "|" + sourceFingerprint + "|" + packId + "@" + packVersion
                + "|" + ruleDigest + "|" + bindingDigest + "|" + windowDigest + "|" + engineVersion;
    }

    private static String key(SignalCatalogKey value) {
        return value == null ? "" : value.toString();
    }
}
