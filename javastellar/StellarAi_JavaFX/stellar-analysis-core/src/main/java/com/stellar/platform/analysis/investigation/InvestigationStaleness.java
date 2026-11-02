package com.stellar.platform.analysis.investigation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Selective invalidation: evidence is stale only when its stored source fingerprint is no longer
 * among the project's current artifact fingerprints. Unrelated sources stay CURRENT.
 */
public final class InvestigationStaleness {

    public static final String FINGERPRINT_SEPARATOR = "|";

    private InvestigationStaleness() {
    }

    public static EngineeringEvidenceItem refresh(EngineeringEvidenceItem item, Set<String> currentFingerprints) {
        if (item == null) {
            return null;
        }
        if (item.sourceFingerprint().isBlank()) {
            return item.withStale(false, "");
        }
        Set<String> live = currentFingerprints == null ? Set.of() : currentFingerprints;
        List<String> missing = new ArrayList<>();
        for (String fp : split(item.sourceFingerprint())) {
            if (!live.contains(fp)) {
                missing.add(fp);
            }
        }
        if (missing.isEmpty()) {
            return item.withStale(false, "");
        }
        String reason = "Source artifact changed. This result was generated from a previous artifact generation.";
        if (item.kind() == EvidenceKind.ANALYSIS_RESULT || item.kind() == EvidenceKind.SPECTRAL_RESULT) {
            reason = "Source was reprocessed. This analysis result was generated from the previous artifact generation.";
        } else if (item.kind() == EvidenceKind.DIAGNOSTIC_FINDING) {
            reason = "Source was reprocessed. This diagnostic finding was generated from the previous artifact generation.";
        } else if (item.kind() == EvidenceKind.GRAPH_SNAPSHOT) {
            reason = "Source was reprocessed. This graph snapshot was captured against the previous artifact generation.";
        }
        return item.withStale(true, reason);
    }

    public static List<EngineeringEvidenceItem> refreshAll(
            List<EngineeringEvidenceItem> evidence, Set<String> currentFingerprints
    ) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        List<EngineeringEvidenceItem> out = new ArrayList<>();
        for (EngineeringEvidenceItem item : evidence) {
            out.add(refresh(item, currentFingerprints));
        }
        return List.copyOf(out);
    }

    public static String join(Iterable<String> fingerprints) {
        if (fingerprints == null) {
            return "";
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String fp : fingerprints) {
            if (fp != null && !fp.isBlank()) {
                unique.add(fp.trim());
            }
        }
        return String.join(FINGERPRINT_SEPARATOR, unique);
    }

    static List<String> split(String blob) {
        if (blob == null || blob.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : blob.split("\\|")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }
}
