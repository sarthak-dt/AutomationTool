package com.stellar.platform.analysis.report;

import com.stellar.platform.analysis.diagnostics.DiagnosticFinding;
import com.stellar.platform.analysis.diagnostics.DiagnosticRunResult;
import com.stellar.platform.analysis.diagnostics.FindingEvidence;
import com.stellar.platform.analysis.diagnostics.RuleOutcome;
import com.stellar.platform.analysis.model.AnalysisSeverity;

import java.util.List;
import java.util.Locale;

public final class DiagnosticReportFormatter {

    private DiagnosticReportFormatter() {
    }

    public static String format(DiagnosticRunResult result) {
        if (result == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("DIAGNOSTICS\n\n");
        sb.append("Deterministic engineering rules — not AI diagnosis, not OEM-certified.\n\n");
        sb.append("Status           ").append(result.status()).append('\n');
        sb.append("Rules requested  ").append(result.rulesRequested()).append('\n');
        sb.append("Rules executed   ").append(result.rulesExecuted()).append('\n');
        sb.append("Rules skipped    ").append(result.rulesSkipped()).append('\n');
        sb.append("Findings         ").append(result.findings().size()).append('\n');
        sb.append("Engine           ").append(result.engineVersion()).append("\n\n");
        sb.append("CRITICAL     ").append(count(result.findings(), AnalysisSeverity.CRITICAL)).append('\n');
        sb.append("WARNING      ").append(count(result.findings(), AnalysisSeverity.WARNING)).append('\n');
        sb.append("OBSERVATION  ").append(count(result.findings(), AnalysisSeverity.OBSERVATION)).append('\n');
        sb.append("INFO         ").append(count(result.findings(), AnalysisSeverity.INFO)).append("\n\n");
        sb.append("Rule evaluation\n");
        for (RuleOutcome o : result.outcomes()) {
            sb.append("  ").append(o.ruleId()).append("  ").append(o.state())
                    .append("  ").append(o.reason()).append('\n');
        }
        sb.append('\n');
        if (!result.findings().isEmpty()) {
            sb.append("Findings\n");
            for (DiagnosticFinding f : result.findings()) {
                sb.append(String.format(Locale.ROOT, "  %-10s  %s  %s%n",
                        sec(f.startTimestampNanos()), f.severity(), f.summary()));
                for (FindingEvidence e : f.evidence()) {
                    sb.append("    ").append(e.role()).append(": ").append(e.statement()).append('\n');
                }
                if (!f.guidance().isEmpty()) {
                    sb.append("    Recommended checks:\n");
                    f.guidance().forEach(g -> sb.append("      - ").append(g).append('\n'));
                }
            }
        }
        return sb.toString();
    }

    private static long count(List<DiagnosticFinding> findings, AnalysisSeverity severity) {
        return findings.stream().filter(f -> f.severity() == severity).count();
    }

    private static String sec(long nanos) {
        if (nanos == Long.MIN_VALUE || nanos == Long.MAX_VALUE) {
            return "full";
        }
        return String.format(Locale.ROOT, "%.3f s", nanos / 1_000_000_000.0d);
    }
}
