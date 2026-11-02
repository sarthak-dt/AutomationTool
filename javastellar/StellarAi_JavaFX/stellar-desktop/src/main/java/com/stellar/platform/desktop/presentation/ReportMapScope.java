package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.PathSanitizer;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.storage.metadata.AnalysisScopeRecord;
import com.stellar.platform.storage.metadata.ProjectMetadataStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Chooses the route that belongs to an investigation.
 * One measurement source on the saved signals keeps that route.
 * A project-wide signal, or more than one measurement, keeps the aggregate route.
 */
public final class ReportMapScope {

    public record Decision(boolean projectWide, String sourceId, String fileName) {
        public Decision {
            sourceId = sourceId == null ? "" : sourceId;
            fileName = fileName == null || fileName.isBlank()
                    ? (projectWide ? "All Measurements" : sourceId)
                    : fileName;
        }

        static Decision all() {
            return new Decision(true, "", "All Measurements");
        }

        static Decision single(String sourceId, String fileName) {
            return new Decision(false, sourceId, fileName);
        }
    }

    private ReportMapScope() {
    }

    /**
     * Durable {@link AnalysisScopeRecord} wins over signal source files and the open graph.
     * A missing record keeps the previous key-based rule and never picks a catalog row.
     */
    public static Decision decide(
            EngineeringInvestigation investigation,
            AnalysisScopeRecord durable,
            boolean liveProjectWide,
            String liveSourceId,
            String liveFileName,
            boolean persistedOnly
    ) {
        if (durable != null) {
            return fromDurable(durable);
        }
        return decide(investigation, liveProjectWide, liveSourceId, liveFileName, persistedOnly);
    }

    public static Decision decide(
            EngineeringInvestigation investigation,
            boolean liveProjectWide,
            String liveSourceId,
            String liveFileName,
            boolean persistedOnly
    ) {
        String saved = singleMeasurementSource(investigation);
        if (saved != null) {
            return Decision.single(saved, "");
        }
        if (!keys(investigation).isEmpty()) {
            return Decision.all();
        }
        if (!persistedOnly && !liveProjectWide && liveSourceId != null && !liveSourceId.isBlank()) {
            return Decision.single(liveSourceId, liveFileName);
        }
        return Decision.all();
    }

    public static AnalysisScopeRecord durableScope(ProjectMetadataStore store, String projectId) {
        if (store == null || projectId == null || projectId.isBlank()) {
            return null;
        }
        try {
            return store.findAnalysisScope(projectId).orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Measurement column for every signal in the report. Project scope is never a
     * physical catalog source id.
     */
    public static String measurementLabel(
            EngineeringInvestigation investigation,
            AnalysisScopeRecord durable,
            Map<String, String> fileNameById
    ) {
        if (durable != null) {
            if (durable.allMeasurements() || durable.sourceId().isBlank()) {
                return "All Measurements";
            }
            return named(durable.fileName(), fileNameById == null ? "" : fileNameById.getOrDefault(durable.sourceId(), ""));
        }
        String saved = singleMeasurementSource(investigation);
        if (saved == null) {
            return "All Measurements";
        }
        return named("", fileNameById == null ? "" : fileNameById.getOrDefault(saved, saved));
    }

    /** Samples for a project-wide report stay merged. A single scope uses that source id only. */
    public static String seriesScope(Decision decision) {
        if (decision == null || decision.projectWide()) {
            return "";
        }
        return decision.sourceId();
    }

    private static Decision fromDurable(AnalysisScopeRecord durable) {
        if (durable.allMeasurements() || durable.sourceId().isBlank()) {
            return Decision.all();
        }
        return Decision.single(durable.sourceId(), durable.fileName());
    }

    private static String named(String preferred, String fallback) {
        String name = preferred == null || preferred.isBlank() ? fallback : preferred;
        name = PathSanitizer.displayName(name);
        return name == null || name.isBlank() ? "All Measurements" : name;
    }

    /**
     * The one measurement id stored on every selected signal, or null when the
     * investigation is project-wide or has no saved signals.
     */
    public static String singleMeasurementSource(EngineeringInvestigation investigation) {
        List<SignalCatalogKey> keys = keys(investigation);
        if (keys.isEmpty()) {
            return null;
        }
        String only = null;
        for (SignalCatalogKey key : keys) {
            String source = key.sourceFile() == null ? "" : key.sourceFile().trim();
            if (source.isEmpty()
                    || ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE.equalsIgnoreCase(source)) {
                return null;
            }
            if (only == null) {
                only = source;
            } else if (!only.equalsIgnoreCase(source)) {
                return null;
            }
        }
        return only;
    }

    public static List<SignalCatalogKey> keys(EngineeringInvestigation investigation) {
        List<SignalCatalogKey> keys = new ArrayList<>();
        if (investigation == null) {
            return keys;
        }
        List<GraphInvestigationSnapshot.Trace> traces =
                GraphInvestigationCodec.decodeTraces(investigation.graphTracesBlob());
        if (!traces.isEmpty()) {
            for (GraphInvestigationSnapshot.Trace trace : traces) {
                if (trace != null && !trace.hidden() && trace.key() != null) {
                    keys.add(trace.key());
                }
            }
            return keys;
        }
        for (String blob : investigation.signalKeyBlobs()) {
            SignalCatalogKey key = GraphInvestigationCodec.decodeKey(blob);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }
}
