package com.stellar.platform.analysis.investigation;

import java.util.Locale;

/** Locale-stable CSV for investigation package exports. Decimal separator is always '.'. */
public final class InvestigationCsvExporter {

    private InvestigationCsvExporter() {
    }

    public static String findings(EngineeringInvestigation investigation) {
        StringBuilder sb = new StringBuilder();
        sb.append("order,kind,reference_id,caption,summary,stale,stale_reason\n");
        if (investigation == null) {
            return sb.toString();
        }
        for (EngineeringEvidenceItem item : investigation.evidence()) {
            if (item.kind() != EvidenceKind.DIAGNOSTIC_FINDING) {
                continue;
            }
            row(sb, item);
        }
        return sb.toString();
    }

    public static String analysis(EngineeringInvestigation investigation) {
        StringBuilder sb = new StringBuilder();
        sb.append("order,kind,reference_id,caption,summary,stale,stale_reason\n");
        if (investigation == null) {
            return sb.toString();
        }
        for (EngineeringEvidenceItem item : investigation.evidence()) {
            if (item.kind() != EvidenceKind.ANALYSIS_RESULT && item.kind() != EvidenceKind.SPECTRAL_RESULT) {
                continue;
            }
            row(sb, item);
        }
        return sb.toString();
    }

    public static String evidence(EngineeringInvestigation investigation) {
        StringBuilder sb = new StringBuilder();
        sb.append("order,kind,reference_id,caption,summary,stale,stale_reason\n");
        if (investigation == null) {
            return sb.toString();
        }
        for (EngineeringEvidenceItem item : investigation.evidence()) {
            row(sb, item);
        }
        return sb.toString();
    }

    private static void row(StringBuilder sb, EngineeringEvidenceItem item) {
        sb.append(item.sortOrder()).append(',')
                .append(csv(item.kind().name())).append(',')
                .append(csv(item.referenceId())).append(',')
                .append(csv(item.caption())).append(',')
                .append(csv(item.summary())).append(',')
                .append(item.stale() ? "true" : "false").append(',')
                .append(csv(item.staleReason())).append('\n');
    }

    private static String csv(String value) {
        String v = value == null ? "" : value.replace("\"", "\"\"");
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0) {
            return '"' + v + '"';
        }
        return String.format(Locale.ROOT, "%s", v);
    }
}
