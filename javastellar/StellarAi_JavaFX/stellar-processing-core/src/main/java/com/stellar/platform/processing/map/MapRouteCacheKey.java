package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.util.EnumMap;
import java.util.Map;

/**
 * Identity of a prepared route.
 *
 * <p>Includes the resolved GPS role mapping so that re-mapping latitude/longitude produces a new
 * route rather than silently reusing a stale one, and the processing revision so reprocessing the
 * same file invalidates the cache.
 */
public record MapRouteCacheKey(
        String projectId,
        String fileId,
        long processingRevision,
        Map<GpsSignalRole, SignalCatalogKey> roles
) {

    public MapRouteCacheKey {
        projectId = projectId == null ? "" : projectId;
        fileId = fileId == null ? "" : fileId;
        if (roles == null || roles.isEmpty()) {
            roles = Map.of();
        } else {
            EnumMap<GpsSignalRole, SignalCatalogKey> ordered = new EnumMap<>(GpsSignalRole.class);
            ordered.putAll(roles);
            roles = Map.copyOf(ordered);
        }
    }
}
