package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.EvidenceKind;
import com.stellar.platform.analysis.investigation.GraphSnapshotMetadata;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModel;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadataStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Names linked snapshots on the report event. The image already copied for graph evidence
 * is left where it is. This does not draw a new snapshot.
 */
public final class EventSnapshotReport {

    private EventSnapshotReport() {
    }

    public static EngineeringReportModel place(
            EngineeringReportModel model,
            EngineeringInvestigation investigation,
            ProjectMetadataStore metadata
    ) {
        if (model == null || investigation == null || metadata == null || model.events().isEmpty()) {
            return model;
        }
        List<EngineeringBookmarkMetadata> bookmarks = metadata.listBookmarks(investigation.projectId());
        Map<String, EngineeringBookmarkMetadata> byId = new HashMap<>();
        for (EngineeringBookmarkMetadata bookmark : bookmarks) {
            if (bookmark != null && bookmark.bookmarkId() != null) {
                byId.put(bookmark.bookmarkId(), bookmark);
            }
        }
        Path artifacts = AnalysisStorageRoots.projectArtifactsRoot(investigation.projectId());
        List<EngineeringReportModel.EventRow> rows = new ArrayList<>();
        boolean changed = false;
        for (EngineeringReportModel.EventRow event : model.events()) {
            String bookmarkId = bookmarkId(event.provenance());
            EngineeringBookmarkMetadata bookmark = byId.get(bookmarkId);
            var defined = EngineerEventDocuments.decode(bookmark);
            if (defined == null || defined.snapshotIds().isEmpty()) {
                rows.add(event);
                continue;
            }
            String text = reportText(defined, bookmark, investigation, artifacts);
            changed = true;
            rows.add(new EngineeringReportModel.EventRow(
                    event.name(), event.type(), event.start(), event.end(), event.sourceFile(),
                    event.signals(), event.description(), text, event.location(), event.provenance(),
                    event.narrative()));
        }
        return changed ? model.withEvents(rows) : model;
    }

    /**
     * Says when the snapshot does not contain an event signal. The image file is not rewritten.
     */
    public static String signalDisclosure(
            EngineeringBookmarkMetadata bookmark, GraphSnapshotMetadata meta
    ) {
        if (bookmark == null || meta == null) {
            return "";
        }
        java.util.List<String> eventKeys = EngineerEventDocuments.signalKeys(bookmark);
        if (eventKeys.isEmpty()) {
            return "";
        }
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String key : eventKeys) {
            boolean present = false;
            for (String stored : meta.signalKeys()) {
                if (key.equals(stored)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                missing.add(signalName(key));
            }
        }
        if (missing.isEmpty()) {
            return "";
        }
        return "This snapshot does not include " + String.join(", ", missing)
                + ". It is not a picture of that event signal.";
    }

    private static String signalName(String key) {
        if (key == null || key.isBlank()) {
            return "the event signal";
        }
        int split = key.indexOf('\u001f');
        String name = split < 0 ? key : key.substring(0, split);
        return name.isBlank() ? "the event signal" : name;
    }

    private static String reportText(
            com.stellar.platform.analysis.investigation.EngineerEventDefinition event,
            EngineeringBookmarkMetadata bookmark,
            EngineeringInvestigation investigation,
            Path artifacts
    ) {
        StringBuilder text = new StringBuilder();
        for (String snapshotId : event.snapshotIds()) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            var item = evidence(investigation, snapshotId);
            Path png = GraphSnapshotLinks.png(
                    artifacts, item == null ? "" : item.investigationId(), snapshotId);
            GraphSnapshotMetadata meta = GraphSnapshotLinks.read(png);
            String caption = item != null && item.caption() != null && !item.caption().isBlank()
                    ? item.caption()
                    : (meta == null || meta.caption().isBlank() ? snapshotId : meta.caption());
            if (png == null || meta == null) {
                text.append("Snapshot ").append(caption).append(" is missing and was not regenerated.");
                continue;
            }
            String refusal = GraphSnapshotLinks.refusal(event, meta, true, true);
            if (!refusal.isBlank()) {
                text.append(caption).append(". ").append(refusal);
                continue;
            }
            if (item == null) {
                text.append(caption).append(" is excluded from this report. The saved image was not deleted.");
                continue;
            }
            String detail = GraphSnapshotLinks.describe(meta, item.stale());
            text.append(caption).append(detail.isBlank() ? "" : ". " + detail)
                    .append(" Included once in Graph Evidence.");
            String disclosure = signalDisclosure(bookmark, meta);
            if (!disclosure.isBlank()) {
                text.append(' ').append(disclosure);
            }
        }
        String value = text.toString().strip();
        return value.isBlank() ? "No graph snapshot is linked." : value;
    }

    private static com.stellar.platform.analysis.investigation.EngineeringEvidenceItem evidence(
            EngineeringInvestigation investigation, String snapshotId
    ) {
        for (var item : investigation.evidence()) {
            if (item != null && item.kind() == EvidenceKind.GRAPH_SNAPSHOT
                    && snapshotId.equals(item.referenceId())) {
                return item;
            }
        }
        return null;
    }

    private static String bookmarkId(String provenance) {
        String prefix = "Engineer event ";
        if (provenance == null || !provenance.startsWith(prefix)) {
            return "";
        }
        int dot = provenance.indexOf('.');
        if (dot <= prefix.length()) {
            return "";
        }
        return provenance.substring(prefix.length(), dot).trim();
    }
}
