package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapRouteServiceTest {

    private static final long SECOND = 1_000_000_000L;

    private static final class CountingQuery implements GraphSeriesQuery {
        private final Map<SignalCatalogKey, List<SignalSamplePoint>> data = new HashMap<>();
        final AtomicInteger queries = new AtomicInteger();
        final AtomicInteger scopedQueries = new AtomicInteger();
        String lastScope;

        @Override
        public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
            queries.incrementAndGet();
            return data.getOrDefault(key, List.of());
        }

        @Override
        public List<SignalSamplePoint> queryRange(
                SignalCatalogKey key, long startNanos, long endNanos, String sourceScope) {
            scopedQueries.incrementAndGet();
            lastScope = sourceScope;
            return queryRange(key, startNanos, endNanos);
        }

        @Override
        public String unit(SignalCatalogKey key) {
            return "";
        }

        @Override
        public long sampleCount(SignalCatalogKey key) {
            return data.getOrDefault(key, List.of()).size();
        }
    }

    private static SignalCatalogKey key(String name) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "GPS", 0);
    }

    private static CountingQuery storeWith(int samples) {
        CountingQuery query = new CountingQuery();
        List<SignalSamplePoint> latitude = new ArrayList<>(samples);
        List<SignalSamplePoint> longitude = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
            latitude.add(new SignalSamplePoint(i * SECOND, 18.5 + i * 0.0001, null, null));
            longitude.add(new SignalSamplePoint(i * SECOND, 73.7 + i * 0.0001, null, null));
        }
        query.data.put(key("Latitude"), latitude);
        query.data.put(key("Longitude"), longitude);
        return query;
    }

    private static MapRouteCacheKey cacheKey(String fileId) {
        Map<GpsSignalRole, SignalCatalogKey> roles = new EnumMap<>(GpsSignalRole.class);
        roles.put(GpsSignalRole.LATITUDE, key("Latitude"));
        roles.put(GpsSignalRole.LONGITUDE, key("Longitude"));
        return new MapRouteCacheKey("project-1", fileId, 1L, roles);
    }

    @Test
    void preparesARouteFromTheExistingSignalStore() {
        CountingQuery query = storeWith(100);

        MapRoute route = new MapRouteService(query).prepare(cacheKey("file-a"), 0, 1_000 * SECOND);

        assertEquals(100, route.model().size());
        assertTrue(route.model().totalDistanceMeters() > 0);
        assertTrue(query.scopedQueries.get() >= 2);
        assertEquals("file-a", query.lastScope);
    }

    @Test
    void reopeningTheSameRouteReusesTheCacheWithoutRequeryingSignals() {
        CountingQuery query = storeWith(50);
        MapRouteService service = new MapRouteService(query);

        MapRoute first = service.prepare(cacheKey("file-a"), 0, 1_000 * SECOND);
        int afterFirst = query.queries.get();
        MapRoute second = service.prepare(cacheKey("file-a"), 0, 1_000 * SECOND);

        assertSame(first, second);
        assertEquals(afterFirst, query.queries.get(), "cached route must not re-read the signal store");
    }

    @Test
    void switchingFileProducesADistinctRoute() {
        CountingQuery query = storeWith(20);
        MapRouteService service = new MapRouteService(query);

        MapRoute a = service.prepare(cacheKey("file-a"), 0, 1_000 * SECOND);
        MapRoute b = service.prepare(cacheKey("file-b"), 0, 1_000 * SECOND);

        assertNotSame(a, b);
        assertEquals(2, service.cachedRouteCount());
    }

    @Test
    void routeCacheStaysBounded() {
        CountingQuery query = storeWith(10);
        MapRouteService service = new MapRouteService(query);

        for (int i = 0; i < 10; i++) {
            service.prepare(cacheKey("file-" + i), 0, 1_000 * SECOND);
        }

        assertTrue(service.cachedRouteCount() <= MapRouteService.MAX_CACHED_ROUTES);
    }

    @Test
    void clearDropsEveryPreparedRoute() {
        CountingQuery query = storeWith(10);
        MapRouteService service = new MapRouteService(query);
        service.prepare(cacheKey("file-a"), 0, 1_000 * SECOND);

        service.clear();

        assertEquals(0, service.cachedRouteCount());
    }

    @Test
    void unresolvedCoordinatesYieldAnEmptyRouteRatherThanAnError() {
        CountingQuery query = storeWith(10);
        MapRouteCacheKey noRoles = new MapRouteCacheKey("project-1", "file-a", 1L, Map.of());

        MapRoute route = new MapRouteService(query).prepare(noRoles, 0, 1_000 * SECOND);

        assertTrue(route.isEmpty());
    }

    @Test
    void indexesAreBuiltLazilyAndSharedPerRoute() {
        CountingQuery query = storeWith(200);
        MapRoute route = new MapRouteService(query).prepare(cacheKey("file-a"), 0, 1_000 * SECOND);

        assertSame(route.lod(), route.lod());
        assertSame(route.spatial(), route.spatial());
    }
}
