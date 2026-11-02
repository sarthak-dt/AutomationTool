package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Groups the series one logical signal may draw. Compatible files become segments of that
 * signal. They are not separate tracks. Sample timestamps are not rewritten.
 */
public final class GraphMeasurementSeries {

    /**
     * One selected logical signal and the physical keys that belong on its single track.
     * {@code measurementLabels} is parallel to {@code physicalKeys} and keeps file identity.
     */
    public record PlotGroup(
            SignalCatalogKey logicalKey,
            String unit,
            List<SignalCatalogKey> physicalKeys,
            List<String> measurementLabels
    ) {
        public PlotGroup {
            Objects.requireNonNull(logicalKey, "logicalKey");
            unit = unit == null ? "" : unit;
            physicalKeys = physicalKeys == null ? List.of() : List.copyOf(physicalKeys);
            measurementLabels = measurementLabels == null ? List.of() : List.copyOf(measurementLabels);
        }
    }

    private GraphMeasurementSeries() {
    }

    public static List<PlotGroup> groupsForPlot(
            List<SignalCatalogKey> selected,
            List<SignalCatalogEntry> selectedEntries,
            List<SignalCatalogEntry> physicalCatalog,
            boolean separateMeasurementClocks
    ) {
        if (selected == null || selected.isEmpty()) {
            return List.of();
        }
        List<PlotGroup> groups = new java.util.ArrayList<>();
        if (!separateMeasurementClocks || physicalCatalog == null || physicalCatalog.isEmpty()) {
            for (SignalCatalogKey key : selected) {
                if (key == null) {
                    continue;
                }
                groups.add(new PlotGroup(key, unitOf(key, selectedEntries) == null ? "" : unitOf(key, selectedEntries),
                        List.of(key), List.of(labelOf(key, selectedEntries))));
            }
            return List.copyOf(groups);
        }
        for (SignalCatalogKey key : selected) {
            if (key == null) {
                continue;
            }
            if (ProjectMergedSignalStore.restrictsToOneMeasurement(key.sourceFile())) {
                groups.add(new PlotGroup(key, unitOf(key, selectedEntries) == null ? "" : unitOf(key, selectedEntries),
                        List.of(key), List.of(labelOf(key, selectedEntries))));
                continue;
            }
            String unit = unitOf(key, selectedEntries);
            List<SignalCatalogKey> physicalKeys = new java.util.ArrayList<>();
            List<String> labels = new java.util.ArrayList<>();
            for (SignalCatalogEntry physical : physicalCatalog) {
                if (physical == null || physical.key() == null) {
                    continue;
                }
                if (!sameEngineering(key, physical.key())) {
                    continue;
                }
                if (unit != null && !unit.equals(normalize(physical.unit()))) {
                    continue;
                }
                physicalKeys.add(physical.key());
                String file = physical.sourceFile();
                labels.add(file == null ? "" : file);
            }
            if (!physicalKeys.isEmpty()) {
                groups.add(new PlotGroup(key, unit == null ? "" : unit, physicalKeys, labels));
            }
        }
        return List.copyOf(groups);
    }

    public static List<SignalCatalogKey> keysForPlot(
            List<SignalCatalogKey> selected,
            List<SignalCatalogEntry> selectedEntries,
            List<SignalCatalogEntry> physicalCatalog,
            boolean separateMeasurementClocks
    ) {
        LinkedHashSet<SignalCatalogKey> out = new LinkedHashSet<>();
        for (PlotGroup group : groupsForPlot(selected, selectedEntries, physicalCatalog, separateMeasurementClocks)) {
            out.addAll(group.physicalKeys());
        }
        return List.copyOf(out);
    }

    public static boolean spansMultipleMeasurements(List<SignalCatalogKey> keys) {
        if (keys == null || keys.size() < 2) {
            return false;
        }
        String first = null;
        for (SignalCatalogKey key : keys) {
            if (key == null) {
                continue;
            }
            String source = key.sourceFile() == null ? "" : key.sourceFile().trim();
            if (source.isEmpty() || ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE.equalsIgnoreCase(source)) {
                continue;
            }
            if (first == null) {
                first = source;
            } else if (!first.equals(source)) {
                return true;
            }
        }
        return false;
    }

    private static String labelOf(SignalCatalogKey key, List<SignalCatalogEntry> entries) {
        if (entries == null) {
            return "";
        }
        for (SignalCatalogEntry entry : entries) {
            if (entry != null && key.equals(entry.key())) {
                String file = entry.sourceFile();
                return file == null ? "" : file;
            }
        }
        return "";
    }

    private static String unitOf(SignalCatalogKey key, List<SignalCatalogEntry> entries) {
        if (entries == null) {
            return null;
        }
        for (SignalCatalogEntry entry : entries) {
            if (entry != null && key.equals(entry.key())) {
                return normalize(entry.unit());
            }
        }
        return null;
    }

    private static boolean sameEngineering(SignalCatalogKey logical, SignalCatalogKey physical) {
        return logical.signalName().equals(physical.signalName())
                && logical.sourceType() == physical.sourceType()
                && logical.messageName().equals(physical.messageName())
                && logical.canId() == physical.canId()
                && logical.channel() == physical.channel();
    }

    private static String normalize(String unit) {
        return unit == null ? "" : unit.trim();
    }

    public static String traceLabel(SignalCatalogEntry entry) {
        if (entry == null || entry.key() == null) {
            return "";
        }
        String name = entry.key().signalName();
        String file = entry.sourceFile();
        if (file == null || file.isBlank()) {
            file = entry.key().sourceFile();
        }
        if (file == null || file.isBlank()
                || ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE.equalsIgnoreCase(file)) {
            return name;
        }
        return name + " · " + file;
    }
}
