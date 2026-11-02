package com.stellar.platform.analysis.workflow;

import com.stellar.platform.analysis.diagnostics.DiagnosticFinding;
import com.stellar.platform.analysis.diagnostics.DiagnosticRunResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record RunComparison(
        List<DiagnosticFinding> persistent,
        List<DiagnosticFinding> resolved,
        List<DiagnosticFinding> added,
        boolean versionsComparable,
        String warning
) {
    public RunComparison {
        persistent = List.copyOf(persistent == null ? List.of() : persistent);
        resolved = List.copyOf(resolved == null ? List.of() : resolved);
        added = List.copyOf(added == null ? List.of() : added);
        warning = warning == null ? "" : warning;
    }

    public static RunComparison compare(DiagnosticRunResult a, DiagnosticRunResult b) {
        if (a == null || b == null) {
            return new RunComparison(List.of(), List.of(), List.of(), false, "Both runs required");
        }
        boolean comparable = a.engineVersion().equals(b.engineVersion());
        String warning = comparable ? "" : "Engine or rule versions differ — comparison is approximate.";
        Set<String> keysA = fingerprints(a.findings());
        Set<String> keysB = fingerprints(b.findings());
        List<DiagnosticFinding> persistent = new ArrayList<>();
        List<DiagnosticFinding> resolved = new ArrayList<>();
        for (DiagnosticFinding f : a.findings()) {
            if (keysB.contains(fingerprint(f))) {
                persistent.add(f);
            } else {
                resolved.add(f);
            }
        }
        List<DiagnosticFinding> added = b.findings().stream()
                .filter(f -> !keysA.contains(fingerprint(f)))
                .toList();
        return new RunComparison(persistent, resolved, added, comparable, warning);
    }

    private static Set<String> fingerprints(List<DiagnosticFinding> findings) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        findings.forEach(f -> out.add(fingerprint(f)));
        return out;
    }

    private static String fingerprint(DiagnosticFinding f) {
        return f.ruleId() + "|" + f.ruleVersion() + "|" + f.startTimestampNanos();
    }
}
