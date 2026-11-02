package com.stellar.platform.processing.map;

/**
 * Prepared route for one measurement: the compact model plus its rendering and lookup indexes.
 *
 * <p>Indexes are built on first use so a route that is only drawn never pays for spatial indexing,
 * and a route that is only queried by time never pays for decimation.
 */
public final class MapRoute {

    private final MapRouteModel model;
    private volatile RouteLodIndex lodIndex;
    private volatile RouteSpatialIndex spatialIndex;

    public MapRoute(MapRouteModel model) {
        this.model = model == null ? MapRouteModel.empty() : model;
    }

    /** Placeholder used before a measurement is loaded, so callers never handle a null route. */
    public static MapRoute emptyRoute() {
        return new MapRoute(MapRouteModel.empty());
    }

    public MapRouteModel model() {
        return model;
    }

    public boolean isEmpty() {
        return model.isEmpty();
    }

    public RouteLodIndex lod() {
        RouteLodIndex local = lodIndex;
        if (local == null) {
            synchronized (this) {
                local = lodIndex;
                if (local == null) {
                    local = new RouteLodIndex(model);
                    lodIndex = local;
                }
            }
        }
        return local;
    }

    public RouteSpatialIndex spatial() {
        RouteSpatialIndex local = spatialIndex;
        if (local == null) {
            synchronized (this) {
                local = spatialIndex;
                if (local == null) {
                    local = new RouteSpatialIndex(model);
                    spatialIndex = local;
                }
            }
        }
        return local;
    }

    /** Releases derived indexes; the model itself stays valid. */
    public void releaseIndexes() {
        RouteLodIndex local = lodIndex;
        if (local != null) {
            local.clear();
        }
        lodIndex = null;
        spatialIndex = null;
    }
}
