package com.stellar.platform.storage.api;

import com.stellar.platform.storage.metadata.MapRouteStateMetadata;

import java.util.Optional;

/** Map Route view state — lightweight per-measurement selection, no route geometry. */
public interface MapRouteStateRepository {
    default void upsertMapRouteState(MapRouteStateMetadata state) {
    }

    default Optional<MapRouteStateMetadata> findMapRouteState(String projectId, String sourceId) {
        return Optional.empty();
    }

    default void deleteMapRouteState(String projectId, String sourceId) {
    }
}
