package com.stellar.platform.analysis.investigation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Report inclusion points at a saved event. Removing the pointer does not delete the event.
 */
public final class EngineerEventInclusion {

    private EngineerEventInclusion() {
    }

    public static List<EngineeringEvidenceItem> include(
            List<EngineeringEvidenceItem> evidence,
            String investigationId,
            String eventId,
            String caption
    ) {
        List<EngineeringEvidenceItem> current = evidence == null ? List.of() : evidence;
        if (eventId == null || eventId.isBlank() || linked(current, eventId)) {
            return current;
        }
        List<EngineeringEvidenceItem> next = new ArrayList<>(current);
        String label = caption == null || caption.isBlank() ? eventId : caption;
        next.add(new EngineeringEvidenceItem(
                null, investigationId, EvidenceKind.ANNOTATION, eventId, next.size(),
                false, "", "", 0L, label, label, Instant.now()));
        return List.copyOf(next);
    }

    public static List<EngineeringEvidenceItem> exclude(List<EngineeringEvidenceItem> evidence, String eventId) {
        if (evidence == null || evidence.isEmpty() || eventId == null || eventId.isBlank()) {
            return evidence == null ? List.of() : evidence;
        }
        List<EngineeringEvidenceItem> next = new ArrayList<>();
        int order = 0;
        for (EngineeringEvidenceItem item : evidence) {
            if (item.kind() == EvidenceKind.ANNOTATION && eventId.equals(item.referenceId())) {
                continue;
            }
            next.add(item.withOrder(order++));
        }
        return List.copyOf(next);
    }

    public static boolean linked(List<EngineeringEvidenceItem> evidence, String eventId) {
        if (evidence == null || eventId == null) {
            return false;
        }
        for (EngineeringEvidenceItem item : evidence) {
            if (item.kind() == EvidenceKind.ANNOTATION && eventId.equals(item.referenceId())) {
                return true;
            }
        }
        return false;
    }
}
