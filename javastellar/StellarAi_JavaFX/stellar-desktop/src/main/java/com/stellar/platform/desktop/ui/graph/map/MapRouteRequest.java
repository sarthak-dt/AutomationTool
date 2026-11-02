package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.processing.graph.GraphSeriesQuery;

import java.util.List;
import java.util.Objects;

/**
 * Everything Map Route needs to prepare a route, supplied by the Signal Plot workspace.
 *
 * <p>Only already-processed artifacts are passed in — a catalog and a time-series query. There is
 * no path from here back to a measurement file, which is what guarantees that opening the tab
 * cannot trigger MF4 parsing.
 *
 * <p>{@code fileId} is the canonical analysis source id. {@code fileName} is display/provenance
 * only. Sample reads and mapping lookup must use {@code fileId}.
 */
public record MapRouteRequest(
        GraphSeriesQuery query,
        List<SignalCatalogEntry> catalog,
        String projectId,
        String fileId,
        String fileName,
        long processingRevision,
        int mappingRevision,
        long dataStartNanos,
        long dataEndNanos,
        boolean projectWide
) {

    public MapRouteRequest {
        Objects.requireNonNull(query, "query");
        catalog = catalog == null ? List.of() : List.copyOf(catalog);
        projectId = projectId == null ? "" : projectId;
        fileId = fileId == null ? "" : fileId;
        fileName = fileName == null ? "" : fileName;
    }

    public MapRouteRequest(
            GraphSeriesQuery query,
            List<SignalCatalogEntry> catalog,
            String projectId,
            String fileId,
            String fileName,
            long processingRevision,
            int mappingRevision,
            long dataStartNanos,
            long dataEndNanos
    ) {
        this(query, catalog, projectId, fileId, fileName, processingRevision, mappingRevision,
                dataStartNanos, dataEndNanos, false);
    }

    public MapRouteRequest(
            GraphSeriesQuery query,
            List<SignalCatalogEntry> catalog,
            String projectId,
            String fileId,
            long processingRevision,
            long dataStartNanos,
            long dataEndNanos
    ) {
        this(query, catalog, projectId, fileId, "", processingRevision, 0, dataStartNanos, dataEndNanos, false);
    }

    public static MapRouteRequest projectWide(
            GraphSeriesQuery query,
            List<SignalCatalogEntry> catalog,
            String projectId,
            int measurementCount,
            long processingRevision,
            int mappingRevision,
            long dataStartNanos,
            long dataEndNanos
    ) {
        return new MapRouteRequest(
                query, catalog, projectId, "", "All Measurements (" + Math.max(0, measurementCount) + ")",
                processingRevision, mappingRevision, dataStartNanos, dataEndNanos, true);
    }

    public String sourceId() {
        return fileId;
    }

    /**
     * Identity of the GPS geometry, not the graph viewport.
     * Sample reads use the measurement catalog extent, so a pan, zoom, or file-focus
     * window must not discard routes that are already loaded.
     */
    public String signature() {
        return projectId + '|' + (projectWide ? "ALL" : "SINGLE") + '|' + fileId + '|' + fileName
                + '|' + processingRevision + '|' + mappingRevision
                + '|' + catalog.size();
    }
}
