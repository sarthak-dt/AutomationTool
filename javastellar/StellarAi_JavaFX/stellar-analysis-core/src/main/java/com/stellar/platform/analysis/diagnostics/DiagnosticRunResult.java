package com.stellar.platform.analysis.diagnostics;

import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.rules.RuleEngineVersion;

import java.time.Instant;
import java.util.List;

public record DiagnosticRunResult(
        String runId,
        AnalysisStatus status,
        int rulesRequested,
        int rulesExecuted,
        int rulesSkipped,
        List<RuleOutcome> outcomes,
        List<DiagnosticFinding> findings,
        TimeWindow range,
        Instant startedAt,
        Instant completedAt,
        String message,
        int analysisCacheHits,
        String engineVersion
) {
    public DiagnosticRunResult {
        status = status == null ? AnalysisStatus.FAILED : status;
        outcomes = List.copyOf(outcomes == null ? List.of() : outcomes);
        findings = List.copyOf(findings == null ? List.of() : findings);
        range = range == null ? TimeWindow.unbounded() : range;
        engineVersion = engineVersion == null ? RuleEngineVersion.CURRENT : engineVersion;
        message = message == null ? "" : message;
    }

    public static DiagnosticRunResult cancelled(String runId, Instant started) {
        return new DiagnosticRunResult(runId, AnalysisStatus.CANCELLED, 0, 0, 0,
                List.of(), List.of(), TimeWindow.unbounded(), started, Instant.now(),
                "Cancelled", 0, RuleEngineVersion.CURRENT);
    }
}
