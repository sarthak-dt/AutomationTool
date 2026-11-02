package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.util.Collection;
import java.util.Map;

/**
 * Backward-compatible facade over {@link GpsSignalDiscoveryService}.
 *
 * @deprecated Prefer {@link GpsSignalDiscoveryService}.
 */
@Deprecated
public final class GpsCoordinateDetector {

    private final GpsSignalDiscoveryService discovery;

    public GpsCoordinateDetector() {
        this.discovery = new GpsSignalDiscoveryService();
    }

    public GpsCoordinateDetector(Map<GpsSignalRole, SignalCatalogKey> overrides) {
        this.discovery = new GpsSignalDiscoveryService(overrides);
    }

    public GpsDetectionResult detect(Collection<SignalCatalogEntry> catalog) {
        return discovery.discover(catalog);
    }

    public static Map<GpsSignalRole, SignalCatalogKey> toRoleMap(GpsDetectionResult result) {
        return GpsSignalDiscoveryService.toRoleMap(result);
    }
}
