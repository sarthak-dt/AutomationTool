package com.stellar.platform.analysis.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;

public record FindingEvidence(
        String role,
        String signalName,
        String statement,
        Map<String, Double> measured
) {
    public FindingEvidence {
        role = role == null ? "" : role;
        signalName = signalName == null ? "" : signalName;
        statement = statement == null ? "" : statement;
        measured = Map.copyOf(measured == null ? Map.of() : new LinkedHashMap<>(measured));
    }
}
