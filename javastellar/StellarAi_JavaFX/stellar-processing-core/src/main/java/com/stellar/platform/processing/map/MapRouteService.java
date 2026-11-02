package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.graph.GraphSeriesQuery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Prepares and caches routes from already-processed canonical signals.
 *
 * <p>This is the only entry point the map UI uses to obtain route data. It reads GPS signals from
 * the existing time-series store — it never opens or reparses a measurement file — and caches the
 * prepared route so reopening the Map Route tab is effectively free.
 *
 * <p>Callers must run {@link #prepare} off the JavaFX application thread.
 */
public final class MapRouteService {

    private static final Logger LOG = Logger.getLogger(MapRouteService.class.getName());

    /**
     * Bounded cache: enough for a typical All-Measurements project, never unbounded.
     * One prepared route per measurement; eviction drops spatial indexes.
     */
    static final int MAX_CACHED_ROUTES = 8;

    private final GraphSeriesQuery query;
    private final MapRouteBuildOptions options;
    private final Map<MapRouteCacheKey, MapRoute> cache = new LinkedHashMap<>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<MapRouteCacheKey, MapRoute> eldest) {
            if (size() > MAX_CACHED_ROUTES) {
                eldest.getValue().releaseIndexes();
                return true;
            }
            return false;
        }
    };

    public MapRouteService(GraphSeriesQuery query) {
        this(query, MapRouteBuildOptions.defaults());
    }

    public MapRouteService(GraphSeriesQuery query, MapRouteBuildOptions options) {
        this.query = Objects.requireNonNull(query, "query");
        this.options = options == null ? MapRouteBuildOptions.defaults() : options;
    }

    public MapRouteBuildOptions options() {
        return options;
    }

    /**
     * @param startNanos inclusive lower bound of the measurement window to read
     * @param endNanos   inclusive upper bound
     * @return the prepared route; empty when latitude/longitude are unresolved or unusable
     */
    public MapRoute prepare(MapRouteCacheKey cacheKey, long startNanos, long endNanos) {
        Objects.requireNonNull(cacheKey, "cacheKey");
        synchronized (cache) {
            MapRoute cached = cache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        Map<GpsSignalRole, SignalCatalogKey> roles = cacheKey.roles();
        MapRoute route;
        if (!GpsSignalRoleResolver.hasRoute(roles)) {
            route = new MapRoute(MapRouteModel.empty());
        } else {
            SignalCatalogKey latKey = roles.get(GpsSignalRole.LATITUDE);
            SignalCatalogKey lonKey = roles.get(GpsSignalRole.LONGITUDE);
            SignalCatalogKey altKey = roles.get(GpsSignalRole.ALTITUDE);
            List<SignalSamplePoint> latitude = read(latKey, startNanos, endNanos, cacheKey.fileId());
            List<SignalSamplePoint> longitude = read(lonKey, startNanos, endNanos, cacheKey.fileId());
            List<SignalSamplePoint> altitude = read(altKey, startNanos, endNanos, cacheKey.fileId());
            ValidatedRoute validated = MapRouteBuilder.validateAndBuild(latitude, longitude, altitude, options);
            route = new MapRoute(validated.model());
            LOG.info(() -> "GPS route prepared · source=" + cacheKey.fileId()
                    + " lat=" + name(latKey)
                    + " lon=" + name(lonKey)
                    + " alt=" + name(altKey)
                    + " latRead=" + latitude.size()
                    + " lonRead=" + longitude.size()
                    + " · " + validated.diagnostics().summary());
        }
        // Build the render ladder here, on this background thread, so the view never decimates
        // a large route on the JavaFX thread while zooming.
        route.lod().prepareLevels();

        synchronized (cache) {
            MapRoute raced = cache.putIfAbsent(cacheKey, route);
            return raced != null ? raced : route;
        }
    }

    /** Drops every prepared route; called on file/project change and when the workspace is disposed. */
    public void clear() {
        synchronized (cache) {
            cache.values().forEach(MapRoute::releaseIndexes);
            cache.clear();
        }
    }

    public int cachedRouteCount() {
        synchronized (cache) {
            return cache.size();
        }
    }

    private List<SignalSamplePoint> read(
            SignalCatalogKey key, long startNanos, long endNanos, String sourceScope) {
        if (key == null) {
            return List.of();
        }
        List<SignalSamplePoint> samples = query.queryRange(key, startNanos, endNanos, sourceScope);
        return samples == null ? List.of() : samples;
    }

    private static String name(SignalCatalogKey key) {
        return key == null ? "-" : key.signalName();
    }
}
