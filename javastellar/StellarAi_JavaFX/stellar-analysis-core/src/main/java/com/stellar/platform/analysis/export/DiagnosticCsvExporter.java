package com.stellar.platform.analysis.export;

import com.stellar.platform.analysis.diagnostics.DiagnosticFinding;
import com.stellar.platform.analysis.diagnostics.DiagnosticRunResult;
import com.stellar.platform.analysis.diagnostics.FindingEvidence;
import com.stellar.platform.analysis.diagnostics.RuleOutcome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DiagnosticCsvExporter {

    public long writeSummary(DiagnosticRunResult result, Path destination) throws IOException {
        StringBuilder sb = new StringBuilder(
                "runId,status,rulesRequested,rulesExecuted,rulesSkipped,findings,engineVersion,message\n");
        sb.append(esc(result.runId())).append(',')
                .append(result.status()).append(',')
                .append(result.rulesRequested()).append(',')
                .append(result.rulesExecuted()).append(',')
                .append(result.rulesSkipped()).append(',')
                .append(result.findings().size()).append(',')
                .append(esc(result.engineVersion())).append(',')
                .append(esc(result.message())).append('\n');
        return write(destination, sb.toString());
    }

    public long writeFindings(DiagnosticRunResult result, Path destination) throws IOException {
        StringBuilder sb = new StringBuilder(
                "findingId,runId,ruleId,ruleVersion,severity,category,startNanos,endNanos,summary,evidence\n");
        for (DiagnosticFinding f : result.findings()) {
            sb.append(esc(f.findingId())).append(',')
                    .append(esc(f.diagnosticRunId())).append(',')
                    .append(esc(f.ruleId())).append(',')
                    .append(esc(f.ruleVersion())).append(',')
                    .append(f.severity()).append(',')
                    .append(f.category()).append(',')
                    .append(f.startTimestampNanos()).append(',')
                    .append(f.endTimestampNanos() == null ? "" : f.endTimestampNanos()).append(',')
                    .append(esc(f.summary())).append(',')
                    .append(esc(evidenceLine(f))).append('\n');
        }
        return write(destination, sb.toString());
    }

    public long writeEvaluation(DiagnosticRunResult result, Path destination) throws IOException {
        StringBuilder sb = new StringBuilder("ruleId,state,reason,findings\n");
        for (RuleOutcome o : result.outcomes()) {
            sb.append(esc(o.ruleId())).append(',')
                    .append(o.state()).append(',')
                    .append(esc(o.reason())).append(',')
                    .append(o.findings().size()).append('\n');
        }
        return write(destination, sb.toString());
    }

    private static String evidenceLine(DiagnosticFinding finding) {
        StringBuilder sb = new StringBuilder();
        for (FindingEvidence e : finding.evidence()) {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(e.role()).append('=').append(e.statement());
        }
        return sb.toString();
    }

    private static long write(Path destination, String text) throws IOException {
        Files.createDirectories(destination.getParent() == null ? Path.of(".") : destination.getParent());
        Files.writeString(destination, text, StandardCharsets.UTF_8);
        return Files.size(destination);
    }

    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
