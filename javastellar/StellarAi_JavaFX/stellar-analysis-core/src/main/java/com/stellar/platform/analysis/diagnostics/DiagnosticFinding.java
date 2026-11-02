package com.stellar.platform.analysis.diagnostics;

import com.stellar.platform.analysis.model.AnalysisSeverity;
import com.stellar.platform.analysis.model.EngineeringFinding;
import com.stellar.platform.analysis.model.FindingType;
import com.stellar.platform.analysis.rules.RuleCategory;
import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Explainable diagnostic finding. Reuses {@link EngineeringFinding} for overlay/export. */
public record DiagnosticFinding(
        EngineeringFinding finding,
        String diagnosticRunId,
        String ruleId,
        String ruleVersion,
        RuleCategory category,
        List<FindingEvidence> evidence,
        List<ClauseTrace> trace,
        List<String> guidance
) {
    public DiagnosticFinding {
        finding = finding == null
                ? new EngineeringFinding(null, diagnosticRunId, FindingType.DIAGNOSTIC,
                AnalysisSeverity.OBSERVATION, List.of(), 0L, 0L, "", "", Map.of(), Instant.now())
                : finding;
        diagnosticRunId = diagnosticRunId == null ? finding.analysisId() : diagnosticRunId;
        ruleId = ruleId == null ? "" : ruleId;
        ruleVersion = ruleVersion == null ? "" : ruleVersion;
        category = category == null ? RuleCategory.CUSTOM : category;
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        trace = List.copyOf(trace == null ? List.of() : trace);
        guidance = List.copyOf(guidance == null ? List.of() : guidance);
    }

    public String findingId() {
        return finding.findingId();
    }

    public AnalysisSeverity severity() {
        return finding.severity();
    }

    public long startTimestampNanos() {
        return finding.startTimestampNanos();
    }

    public Long endTimestampNanos() {
        return finding.endTimestampNanos();
    }

    public String summary() {
        return finding.summary();
    }

    public List<SignalCatalogKey> signalKeys() {
        return finding.signalKeys();
    }
}
