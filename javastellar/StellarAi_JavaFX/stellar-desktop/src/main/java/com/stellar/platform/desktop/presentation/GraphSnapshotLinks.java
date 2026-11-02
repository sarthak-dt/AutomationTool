package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineerEventDefinition;
import com.stellar.platform.analysis.investigation.EngineeringFormats;
import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.EvidenceKind;
import com.stellar.platform.analysis.investigation.GraphSnapshotMetadata;
import com.stellar.platform.analysis.investigation.InvestigationStoragePaths;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.graph.timeline.UnknownOrderLayout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether a saved graph snapshot may be named as evidence for one event.
 * The snapshot image is not copied and is not redrawn.
 */
public final class GraphSnapshotLinks {

    private GraphSnapshotLinks() {
    }

    public record Choice(
            String snapshotId,
            String investigationId,
            String caption,
            String detail,
            boolean linked,
            boolean linkAllowed,
            Path png
    ) {
    }

    public static List<Choice> choices(
            EngineerEventDefinition event,
            List<EngineeringInvestigation> investigations,
            Path artifacts,
            List<String> linkedIds
    ) {
        List<Choice> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        if (investigations == null) {
            investigations = List.of();
        }
        for (EngineeringInvestigation investigation : investigations) {
            if (investigation == null) {
                continue;
            }
            for (var item : investigation.evidence()) {
                if (item == null || item.kind() != EvidenceKind.GRAPH_SNAPSHOT) {
                    continue;
                }
                Path png = png(artifacts, investigation.investigationId(), item.referenceId());
                GraphSnapshotMetadata meta = read(png);
                boolean linked = linkedIds != null && linkedIds.contains(item.referenceId());
                String refusal = refusal(event, meta, png != null, true);
                String caption = item.caption() == null || item.caption().isBlank()
                        ? (meta == null || meta.caption().isBlank() ? item.referenceId() : meta.caption())
                        : item.caption();
                String detail = describe(meta, item.stale());
                if (!refusal.isBlank()) {
                    detail = detail.isBlank() ? refusal : detail + " " + refusal;
                } else if (item.stale()) {
                    detail = detail + " This snapshot is stale and was not regenerated.";
                }
                seen.add(item.referenceId());
                out.add(new Choice(
                        item.referenceId(), investigation.investigationId(), caption, detail.strip(),
                        linked, refusal.isBlank(), png));
            }
        }
        if (linkedIds != null) {
            for (String snapshotId : linkedIds) {
                if (snapshotId == null || snapshotId.isBlank() || !seen.add(snapshotId)) {
                    continue;
                }
                Path png = png(artifacts, "", snapshotId);
                GraphSnapshotMetadata meta = read(png);
                String refusal = refusal(event, meta, png != null, true);
                String caption = meta == null || meta.caption().isBlank() ? snapshotId : meta.caption();
                String detail = describe(meta, false);
                if (png != null) {
                    detail = (detail + " Excluded from the report. The saved image was not deleted.").strip();
                }
                out.add(new Choice(
                        snapshotId, meta == null ? "" : meta.investigationId(), caption, detail,
                        true, refusal.isBlank(), png));
            }
        }
        return List.copyOf(out);
    }

    /**
     * @return a blank string when the snapshot covers this event in the same project
     */
    public static String refusal(
            EngineerEventDefinition event,
            GraphSnapshotMetadata meta,
            boolean filePresent,
            boolean sameProject
    ) {
        if (!sameProject) {
            return "That snapshot belongs to a different project.";
        }
        if (event == null) {
            return "Select an event first.";
        }
        if (!filePresent || meta == null) {
            return "The snapshot file is missing. It was not regenerated.";
        }
        if (meta.rangeStartNanos() == null || meta.rangeEndNanos() == null) {
            return "This snapshot has no time range. It was not linked as event evidence.";
        }
        long start = meta.rangeStartNanos();
        long end = meta.rangeEndNanos();
        boolean covers = event.window()
                ? event.startNanos() >= start && event.endNanos() != null && event.endNanos() <= end
                : event.startNanos() >= start && event.startNanos() <= end;
        if (!covers) {
            return "This snapshot does not cover the event. It was not linked as event evidence.";
        }
        if (!event.sourceFile().isBlank()) {
            for (String encoded : meta.signalKeys()) {
                SignalCatalogKey key = GraphInvestigationCodec.decodeKey(encoded);
                if (key == null || key.sourceFile() == null || key.sourceFile().isBlank()) {
                    continue;
                }
                String file = key.sourceFile();
                String name = file;
                int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
                if (slash >= 0 && slash + 1 < file.length()) {
                    name = file.substring(slash + 1);
                }
                if (!event.sourceFile().equals(file) && !event.sourceFile().equals(name)) {
                    return "This snapshot is from a different measurement. It was not linked.";
                }
            }
        }
        return "";
    }

    public static String describe(GraphSnapshotMetadata meta, boolean stale) {
        if (meta == null) {
            return "Snapshot file is missing. It was not regenerated.";
        }
        StringBuilder text = new StringBuilder();
        if (meta.rangeStartNanos() != null && meta.rangeEndNanos() != null) {
            text.append("Viewport ").append(EngineeringFormats.range(meta.rangeStartNanos(), meta.rangeEndNanos())).append(". ");
        }
        if ("LAYOUT_SLOT".equals(meta.timeBasis()) || (meta.timeBasis().isBlank() && layoutSlotRange(meta))) {
            text.append("Time basis: unknown-order layout slot (file position), not elapsed recording time. The image was not rewritten. ");
        } else if ("MEASUREMENT_ELAPSED".equals(meta.timeBasis())) {
            text.append("Time basis: measurement-relative elapsed time. ");
        }
        if (!meta.layout().isBlank()) {
            text.append("Layout ").append(meta.layout()).append(". ");
        }
        if (!meta.visibleSignals().isEmpty()) {
            text.append("Signals ").append(String.join(", ", meta.visibleSignals())).append(". ");
        }
        List<String> units = new ArrayList<>();
        for (String unit : meta.units()) {
            if (unit != null && !unit.isBlank()) {
                units.add(unit);
            }
        }
        if (!units.isEmpty()) {
            text.append("Units ").append(String.join(", ", units)).append(". ");
        }
        if (stale) {
            text.append("Stale, not regenerated. ");
        }
        return text.toString().strip();
    }

    /** A saved viewport that is exactly one equal-width layout slot, aligned to the slot grid. */
    static boolean layoutSlotRange(GraphSnapshotMetadata meta) {
        if (meta == null || meta.rangeStartNanos() == null || meta.rangeEndNanos() == null) {
            return false;
        }
        long start = meta.rangeStartNanos();
        long end = meta.rangeEndNanos();
        long span = UnknownOrderLayout.EQUAL_SLOT_SPAN;
        return end > start && end - start == span && start % span == 0L;
    }

    public static Path png(Path artifacts, String investigationId, String snapshotId) {
        if (artifacts == null || snapshotId == null || snapshotId.isBlank()) {
            return null;
        }
        if (investigationId != null && !investigationId.isBlank()) {
            Path direct = InvestigationStoragePaths.snapshot(artifacts, investigationId, snapshotId);
            if (Files.isRegularFile(direct)) {
                return direct;
            }
        }
        Path root = artifacts.resolve("investigations");
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.toList()) {
                Path candidate = dir.resolve("snapshots").resolve(InvestigationStoragePaths.safe(snapshotId) + ".png");
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        } catch (IOException ex) {
            return null;
        }
        return null;
    }

    public static GraphSnapshotMetadata read(Path png) {
        if (png == null || !Files.isRegularFile(png)) {
            return null;
        }
        String name = png.getFileName().toString();
        String jsonName = name.endsWith(".png") ? name.substring(0, name.length() - 4) + ".json" : name + ".json";
        Path json = png.resolveSibling(jsonName);
        if (!Files.isRegularFile(json)) {
            return null;
        }
        try {
            return GraphSnapshotMetadata.fromJson(Files.readString(json));
        } catch (IOException ex) {
            return null;
        }
    }
}
