package com.stellar.platform.analysis.investigation;

import java.util.ArrayList;
import java.util.List;

public final class InvestigationValidity {

    private InvestigationValidity() {
    }

    public static InvestigationHealth health(List<EngineeringEvidenceItem> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return InvestigationHealth.CURRENT;
        }
        int stale = 0;
        for (EngineeringEvidenceItem item : evidence) {
            if (item != null && item.stale()) {
                stale++;
            }
        }
        if (stale == 0) {
            return InvestigationHealth.CURRENT;
        }
        if (stale == evidence.size()) {
            return InvestigationHealth.STALE;
        }
        return InvestigationHealth.PARTIALLY_STALE;
    }

    public static List<String> explanations(List<EngineeringEvidenceItem> evidence) {
        List<String> out = new ArrayList<>();
        if (evidence == null) {
            return List.of();
        }
        for (EngineeringEvidenceItem item : evidence) {
            if (item != null && item.stale()) {
                String reason = item.staleReason().isBlank() ? "Source artifact changed." : item.staleReason();
                out.add(item.kind() + " " + display(item) + ": " + reason);
            }
        }
        return List.copyOf(out);
    }

    private static String display(EngineeringEvidenceItem item) {
        if (!item.caption().isBlank()) {
            return item.caption();
        }
        if (!item.summary().isBlank()) {
            return item.summary();
        }
        return item.referenceId();
    }
}
