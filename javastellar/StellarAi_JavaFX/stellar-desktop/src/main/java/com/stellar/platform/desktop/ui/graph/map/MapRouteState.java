package com.stellar.platform.desktop.ui.graph.map;

/**
 * Lightweight, persistable Map Route state.
 *
 * <p>Deliberately holds timestamps rather than route arrays: coordinates are reconstructed from the
 * already-persisted processed signals on reload, so saving a project does not grow the database
 * with duplicated GPS data.
 */
public record MapRouteState(
        MapSelectionMode mode,
        Long pointTimestampNanos,
        Long aTimestampNanos,
        Long bTimestampNanos,
        boolean autoSyncWithTimeline
) {

    public MapRouteState {
        mode = mode == null ? MapSelectionMode.ALL_ROUTE : mode;
    }

    public static MapRouteState initial() {
        return new MapRouteState(MapSelectionMode.ALL_ROUTE, null, null, null, true);
    }

    public boolean hasSelection() {
        return pointTimestampNanos != null || (aTimestampNanos != null && bTimestampNanos != null);
    }
}
