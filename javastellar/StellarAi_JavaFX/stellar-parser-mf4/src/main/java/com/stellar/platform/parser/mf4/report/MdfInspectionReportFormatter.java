package com.stellar.platform.parser.mf4.report;

import com.stellar.platform.parser.mf4.analysis.MdfConsistencyIssue;
import com.stellar.platform.parser.mf4.analysis.MdfRecoveryCandidate;
import com.stellar.platform.parser.mf4.block.MdfLinkIssue;

/**
 * Deterministic human-readable formatter for {@link MdfInspectionReport}.
 */
public final class MdfInspectionReportFormatter {

    private MdfInspectionReportFormatter() {
    }

    public static String format(MdfInspectionReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("MDF version: ").append(report.mdfVersion()).append('\n');
        sb.append("Physical size: ").append(report.physicalSize()).append(" bytes").append('\n');
        sb.append("Finalization status: ").append(report.finalizationStatus()).append('\n');
        sb.append("DG count: ").append(report.dgCount()).append('\n');
        sb.append("CG count: ").append(report.cgCount()).append('\n');
        sb.append("CN count: ").append(report.cnCount()).append('\n');
        sb.append("DT count: ").append(report.dtCount()).append('\n');
        sb.append("DZ count: ").append(report.dzCount()).append('\n');
        sb.append("DL count: ").append(report.dlCount()).append('\n');
        sb.append("Declared cycles: ").append(report.declaredCycleCountTotal()).append('\n');
        if (report.possibleRecordCount() != null) {
            sb.append("Possible record count: ").append(report.possibleRecordCount()).append('\n');
        }
        sb.append("Physical data detected: ").append(report.physicalDataDetected() ? "YES" : "NO").append('\n');
        sb.append("Physical data-region length: ").append(report.physicalDataRegionLength()).append('\n');
        sb.append("Recovery candidate: ").append(report.recoveryCandidate() ? "YES" : "NO").append('\n');
        sb.append("Consistency issues: ").append(report.consistencyIssues().size()).append('\n');
        sb.append("Unknown blocks: ").append(report.unknownBlocks().size()).append('\n');
        sb.append("Broken links: ").append(report.brokenLinks().size()).append('\n');
        if (report.cancelled()) {
            sb.append("Cancelled: YES").append('\n');
        }
        if (!report.finalizationReasons().isEmpty()) {
            sb.append("Reasons:").append('\n');
            for (String reason : report.finalizationReasons()) {
                sb.append("  - ").append(reason).append('\n');
            }
        }
        if (!report.consistencyIssues().isEmpty()) {
            sb.append("Issues:").append('\n');
            for (MdfConsistencyIssue issue : report.consistencyIssues()) {
                sb.append("  - [").append(issue.severity()).append("] ")
                        .append(issue.code()).append(": ").append(issue.message()).append('\n');
            }
        }
        if (!report.recoveryCandidates().isEmpty()) {
            sb.append("Recovery candidates:").append('\n');
            for (MdfRecoveryCandidate candidate : report.recoveryCandidates()) {
                sb.append("  - @").append(candidate.address()).append(" ")
                        .append(candidate.reason())
                        .append(" (").append(candidate.physicalBytes()).append(" bytes)").append('\n');
            }
        }
        if (!report.brokenLinks().isEmpty()) {
            sb.append("Broken links detail:").append('\n');
            for (MdfLinkIssue link : report.brokenLinks()) {
                sb.append("  - ").append(link.message()).append('\n');
            }
        }
        if (!report.unsupportedStructures().isEmpty()) {
            sb.append("Unsupported structures:").append('\n');
            for (String u : report.unsupportedStructures()) {
                sb.append("  - ").append(u).append('\n');
            }
        }
        return sb.toString();
    }

    public static String formatSummary(MdfInspectionReport report) {
        return "MDF version: " + report.mdfVersion() + "\n"
                + "Physical size: " + report.physicalSize() + " bytes\n"
                + "Finalization status: " + report.finalizationStatus() + "\n"
                + "DG count: " + report.dgCount() + "\n"
                + "CG count: " + report.cgCount() + "\n"
                + "CN count: " + report.cnCount() + "\n"
                + "Declared cycles: " + report.declaredCycleCountTotal() + "\n"
                + "Physical data detected: " + (report.physicalDataDetected() ? "YES" : "NO") + "\n"
                + "Recovery candidate: " + (report.recoveryCandidate() ? "YES" : "NO") + "\n"
                + "Consistency issues: " + report.consistencyIssues().size() + "\n";
    }
}
