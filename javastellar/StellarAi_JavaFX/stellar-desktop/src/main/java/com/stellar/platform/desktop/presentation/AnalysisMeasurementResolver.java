package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.catalog.SignalSeriesStore;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Binds Analyze Selected / Graphs to one measurement without mixing samples.
 * <p>
 * "All Measurements" is a Signals browsing scope. Analysis always resolves to a single
 * processed MF4 recording. This class never starts processing and never concatenates series.
 */
public final class AnalysisMeasurementResolver {

    public record Resolution(
            String measurementId,
            String measurementFileName,
            List<SignalCatalogKey> keys,
            boolean droppedCrossMeasurementKeys,
            boolean ambiguous,
            String reason
    ) {
        public Resolution(
                String measurementId,
                String measurementFileName,
                List<SignalCatalogKey> keys,
                boolean droppedCrossMeasurementKeys,
                boolean ambiguous
        ) {
            this(measurementId, measurementFileName, keys, droppedCrossMeasurementKeys, ambiguous, "");
        }

        public Resolution {
            measurementId = measurementId == null ? "" : measurementId;
            measurementFileName = measurementFileName == null ? "" : measurementFileName;
            keys = keys == null ? List.of() : List.copyOf(keys);
            reason = reason == null ? "" : reason;
        }

        public boolean canAnalyze() {
            return !ambiguous && !measurementId.isBlank();
        }

        public AnalysisMeasurementContext context() {
            return AnalysisMeasurementContext.of(measurementId, measurementFileName);
        }

        /** Engineer-facing reason Analyze is enabled or blocked. Never picks a measurement silently. */
        public String explanation() {
            if (canAnalyze()) {
                return reason.isBlank()
                        ? "Plot the signals in the active workspace."
                        : reason;
            }
            if (!reason.isBlank()) {
                return reason;
            }
            if (droppedCrossMeasurementKeys) {
                return "Selected signals belong to more than one measurement. "
                        + "Choose one measurement in the browser, then Analyze.";
            }
            return "Analyze needs signals from exactly one processed measurement.";
        }
    }

    private AnalysisMeasurementResolver() {
    }

    public static Resolution resolve(
            List<SignalCatalogKey> requested,
            List<SignalCatalogEntry> catalog,
            Optional<WorkspaceFile> currentActive,
            List<WorkspaceFile> measurements
    ) {
        return resolve(requested, catalog, currentActive, measurements, null, null);
    }

    /**
     * Analyze / Waveform resolution. A specific Signals browser measurement is authoritative.
     * All Measurements requires selected keys to belong to exactly one measurement.
     */
    public static Resolution resolve(
            List<SignalCatalogKey> requested,
            List<SignalCatalogEntry> catalog,
            Optional<WorkspaceFile> currentActive,
            List<WorkspaceFile> measurements,
            MeasurementCatalogScope.Ref browserScope
    ) {
        return resolve(requested, catalog, currentActive, measurements, browserScope, null);
    }

    public static Resolution resolve(
            List<SignalCatalogKey> requested,
            List<SignalCatalogEntry> catalog,
            Optional<WorkspaceFile> currentActive,
            List<WorkspaceFile> measurements,
            MeasurementCatalogScope.Ref browserScope,
            SignalSeriesStore store
    ) {
        List<SignalCatalogKey> keys = requested == null ? List.of() : requested.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
        List<SignalCatalogEntry> entries = catalog == null ? List.of() : catalog;
        List<WorkspaceFile> files = measurements == null ? List.of() : measurements;
        WorkspaceFile active = currentActive == null ? null : currentActive.orElse(null);

        if (browserScope != null && !browserScope.allMeasurements()) {
            WorkspaceFile chosen = chooseFile(browserScope.identity(), active, files, entries);
            if (chosen == null) {
                chosen = chooseFile(browserScope.displayName(), active, files, entries);
            }
            if (chosen == null) {
                return new Resolution("", "", keys, false, true,
                        blocked("BROWSER_SCOPE_UNRESOLVED", active, Set.of(), keys.size(), store));
            }
            List<SignalCatalogEntry> scopedCatalog = entries;
            if (store instanceof ProjectMergedSignalStore merged) {
                List<SignalCatalogEntry> physical = merged.physicalCatalog(chosen.id(), chosen.fileName());
                if (physical.isEmpty()) {
                    physical = merged.physicalCatalog(browserScope.identity(), browserScope.displayName());
                }
                if (!physical.isEmpty()) {
                    scopedCatalog = physical;
                }
            }
            List<SignalCatalogKey> scoped = remap(keys, scopedCatalog, chosen);
            return new Resolution(
                    chosen.id(),
                    chosen.fileName(),
                    scoped,
                    scoped.size() != keys.size(),
                    false,
                    enabled(chosen, keys.size())
            );
        }

        Set<String> identities = identitiesOf(keys, entries, store);
        LinkedHashSet<WorkspaceFile> chosenFiles = new LinkedHashSet<>();
        for (String identity : identities) {
            if (isProjectScopeIdentity(identity)) {
                continue;
            }
            WorkspaceFile file = chooseFile(identity, null, files, entries);
            if (file != null) {
                chosenFiles.add(file);
            }
        }
        if (chosenFiles.size() > 1) {
            WorkspaceFile owner = ownerOfAll(keys, active, files, store, entries);
            if (owner != null) {
                List<SignalCatalogKey> scoped = remap(keys, entries, owner);
                return new Resolution(
                        owner.id(),
                        owner.fileName(),
                        scoped.isEmpty() ? keys : scoped,
                        false,
                        false,
                        enabled(owner, keys.size())
                );
            }
            return new Resolution("", "", keys, true, true,
                    blocked("CROSS_MEASUREMENT", active, identities, keys.size(), store));
        }
        WorkspaceFile chosen = chosenFiles.isEmpty() ? null : chosenFiles.iterator().next();
        if (chosen == null && ownsAll(keys, active, store, entries)) {
            chosen = active;
        }
        if (chosen == null) {
            chosen = soleCatalogFile(entries, files, active, store);
        }
        if (chosen == null) {
            return new Resolution("", "", keys, identities.size() > 1, true,
                    blocked("NO_OWNING_MEASUREMENT", active, identities, keys.size(), store));
        }
        List<SignalCatalogKey> scoped = remap(keys, entries, chosen);
        return new Resolution(
                chosen.id(),
                chosen.fileName(),
                scoped.isEmpty() ? keys : scoped,
                false,
                false,
                enabled(chosen, keys.size())
        );
    }

    private static boolean isProjectScopeIdentity(String identity) {
        return identity == null
                || identity.isBlank()
                || ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE.equalsIgnoreCase(identity);
    }

    /**
     * Catalog that contains exactly one processed measurement is enough to bind Analyze.
     * UUID vs file-name aliases of that same file must not disable the button.
     */
    private static Set<String> identitiesOf(
            List<SignalCatalogKey> keys,
            List<SignalCatalogEntry> entries,
            SignalSeriesStore store
    ) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (store instanceof ProjectMergedSignalStore merged) {
            for (SignalCatalogKey key : keys) {
                if (key == null) {
                    continue;
                }
                ids.addAll(merged.physicalIdentities(key));
            }
            ids.removeIf(AnalysisMeasurementResolver::isProjectScopeIdentity);
            if (!ids.isEmpty()) {
                return ids;
            }
        }
        for (String identity : MeasurementCatalogScope.identitiesOf(keys, entries)) {
            if (!isProjectScopeIdentity(identity)) {
                ids.add(identity);
            }
        }
        return ids;
    }

    /**
     * Prefer the selected measurement when it owns every workspace key; otherwise the first
     * processed file that does. Never concatenates series across files.
     */
    private static WorkspaceFile ownerOfAll(
            List<SignalCatalogKey> keys,
            WorkspaceFile active,
            List<WorkspaceFile> files,
            SignalSeriesStore store,
            List<SignalCatalogEntry> catalog
    ) {
        if (ownsAll(keys, active, store, catalog)) {
            return active;
        }
        if (active == null) {
            return null;
        }
        for (WorkspaceFile file : files) {
            if (file != null && file != active && ownsAll(keys, file, store, catalog)) {
                return file;
            }
        }
        return null;
    }

    static boolean ownsAll(
            List<SignalCatalogKey> keys,
            WorkspaceFile measurement,
            SignalSeriesStore store,
            List<SignalCatalogEntry> catalog
    ) {
        if (measurement == null || keys == null || keys.isEmpty()) {
            return false;
        }
        if (store instanceof ProjectMergedSignalStore merged) {
            for (SignalCatalogKey key : keys) {
                if (key == null) {
                    return false;
                }
                Set<String> owners = merged.physicalIdentities(key);
                if (owners.isEmpty()) {
                    return false;
                }
                boolean owned = owners.stream().anyMatch(id -> matchesFile(measurement, id));
                if (!owned) {
                    return false;
                }
            }
            return true;
        }
        List<SignalCatalogEntry> scoped = MeasurementCatalogScope.filter(
                catalog, measurement.id(), measurement.fileName());
        for (SignalCatalogKey key : keys) {
            if (key == null) {
                return false;
            }
            String source = key.sourceFile();
            if (!isProjectScopeIdentity(source) && !matchesFile(measurement, source)) {
                return false;
            }
            if (MeasurementCatalogScope.belongs(key, measurement.id(), measurement.fileName())) {
                continue;
            }
            if (MeasurementCatalogScope.remapToMeasurement(key, scoped) == null) {
                return false;
            }
        }
        return true;
    }

    private static String enabled(WorkspaceFile chosen, int keyCount) {
        return "Plot the signals in the active workspace. "
                + "canAnalyze=true sourceId=" + chosen.id()
                + " fileName=" + chosen.fileName()
                + " workspaceKeys=" + keyCount;
    }

    private static String blocked(
            String code,
            WorkspaceFile active,
            Set<String> identities,
            int keyCount,
            SignalSeriesStore store
    ) {
        String restored = active == null ? "none" : active.id() + "/" + active.fileName();
        String storeName = store == null ? "none" : store.getClass().getSimpleName();
        String diagnostic = "canAnalyze=false reason=" + code
                + " restoredSelected=" + restored
                + " identities=" + identities
                + " workspaceKeys=" + keyCount
                + " store=" + storeName;
        String human = switch (code) {
            case "CROSS_MEASUREMENT" ->
                    "Selected signals belong to more than one measurement. "
                            + "Choose one measurement in the browser, then Analyze.";
            case "NO_OWNING_MEASUREMENT" ->
                    "Choose one measurement in the browser. "
                            + "These signals are not bound to a single processed measurement.";
            case "BROWSER_SCOPE_UNRESOLVED" ->
                    "The selected measurement could not be resolved. "
                            + "Choose a processed measurement, then Analyze.";
            default -> "Analyze is unavailable for the signals in the active workspace.";
        };
        return human + " " + diagnostic;
    }

    private static WorkspaceFile soleCatalogFile(
            List<SignalCatalogEntry> entries,
            List<WorkspaceFile> files,
            WorkspaceFile active,
            SignalSeriesStore store
    ) {
        if (store instanceof ProjectMergedSignalStore merged && merged.memberStoreCount() > 1) {
            return null;
        }
        List<MeasurementCatalogScope.Ref> refs = MeasurementCatalogScope.measurements(entries);
        if (refs.size() != 1) {
            return null;
        }
        MeasurementCatalogScope.Ref only = refs.getFirst();
        WorkspaceFile chosen = chooseFile(only.identity(), null, files, entries);
        if (chosen == null) {
            chosen = chooseFile(only.displayName(), active, files, entries);
        }
        return chosen;
    }

    public static List<SignalCatalogKey> remapToCatalog(
            List<SignalCatalogKey> requested,
            List<SignalCatalogEntry> scopedCatalog
    ) {
        if (requested == null || requested.isEmpty() || scopedCatalog == null) {
            return List.of();
        }
        LinkedHashSet<SignalCatalogKey> out = new LinkedHashSet<>();
        for (SignalCatalogKey key : requested) {
            SignalCatalogEntry remapped = MeasurementCatalogScope.remapToMeasurement(key, scopedCatalog);
            if (remapped != null) {
                out.add(remapped.key());
            }
        }
        return List.copyOf(out);
    }

    private static List<SignalCatalogKey> remap(
            List<SignalCatalogKey> keys,
            List<SignalCatalogEntry> catalog,
            WorkspaceFile measurement
    ) {
        if (measurement == null) {
            return keys;
        }
        List<SignalCatalogEntry> scoped = MeasurementCatalogScope.filter(
                catalog, measurement.id(), measurement.fileName());
        List<SignalCatalogKey> remapped = remapToCatalog(keys, scoped);
        return remapped.isEmpty() ? keys.stream()
                .filter(k -> MeasurementCatalogScope.belongs(k, measurement.id(), measurement.fileName()))
                .toList() : remapped;
    }

    private static WorkspaceFile chooseFile(
            String identity,
            WorkspaceFile active,
            List<WorkspaceFile> files,
            List<SignalCatalogEntry> catalog
    ) {
        if (identity == null || identity.isBlank()) {
            return active;
        }
        for (WorkspaceFile file : files) {
            if (file != null && matchesFile(file, identity)) {
                return file;
            }
        }
        for (SignalCatalogEntry entry : catalog) {
            if (entry != null && MeasurementCatalogScope.belongs(entry, identity, identity)) {
                String display = MeasurementCatalogScope.displayOf(entry);
                for (WorkspaceFile file : files) {
                    if (file != null && matchesFile(file, display)) {
                        return file;
                    }
                }
                break;
            }
        }
        return null;
    }

    private static boolean matchesFile(WorkspaceFile file, String identityOrName) {
        if (file == null || identityOrName == null || identityOrName.isBlank()) {
            return false;
        }
        if (identityOrName.equalsIgnoreCase(file.id())
                || identityOrName.equalsIgnoreCase(file.fileName())) {
            return true;
        }
        String path = file.displayPath() == null ? "" : file.displayPath().replace('\\', '/');
        return path.toLowerCase(Locale.ROOT).endsWith("/" + identityOrName.toLowerCase(Locale.ROOT));
    }
}
