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
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Practical route performance measurements at automotive dataset sizes.
 *
 * <p>Runs entirely against in-memory sample lists: no network, no map tiles, no measurement files.
 * Thresholds are deliberately generous headroom over observed timings — they exist to catch an
 * algorithmic regression (an accidental O(n) scan per lookup, say), not to police CI jitter.
 */
class MapRoutePerformanceTest {

    private static final long SECOND = 1_000_000_000L;
    private static final int[] SIZES = {20_000, 100_000, 500_000, 1_000_000};

    private static final class MemoryQuery implements GraphSeriesQuery {
        private final Map<SignalCatalogKey, List<SignalSamplePoint>> data = new HashMap<>();

        @Override
        public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
            return data.getOrDefault(key, List.of());
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

    private static MemoryQuery store(int samples) {
        MemoryQuery query = new MemoryQuery();
        List<SignalSamplePoint> latitude = new ArrayList<>(samples);
        List<SignalSamplePoint> longitude = new ArrayList<>(samples);
        List<SignalSamplePoint> altitude = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
            double angle = i * 0.0005;
            long t = i * (SECOND / 10);
            latitude.add(new SignalSamplePoint(t, 18.5 + 0.05 * Math.sin(angle), null, null));
            longitude.add(new SignalSamplePoint(t, 73.7 + 0.05 * Math.cos(angle), null, null));
            altitude.add(new SignalSamplePoint(t, 560 + 20 * Math.sin(angle * 3), null, null));
        }
        query.data.put(key("Latitude"), latitude);
        query.data.put(key("Longitude"), longitude);
        query.data.put(key("Altitude"), altitude);
        return query;
    }

    private static MapRouteCacheKey cacheKey(String fileId) {
        Map<GpsSignalRole, SignalCatalogKey> roles = new EnumMap<>(GpsSignalRole.class);
        roles.put(GpsSignalRole.LATITUDE, key("Latitude"));
        roles.put(GpsSignalRole.LONGITUDE, key("Longitude"));
        roles.put(GpsSignalRole.ALTITUDE, key("Altitude"));
        return new MapRouteCacheKey("project-perf", fileId, 1L, roles);
    }

    @Test
    void routePreparationAndLookupsScaleAcrossDatasetSizes() {
        StringBuilder report = new StringBuilder("\nMap Route performance (single run, in-memory store)\n");
        report.append(String.format("%-10s %10s %10s %10s %10s %10s %10s%n",
                "samples", "prepare", "lod", "cached", "nearest", "timeAt", "metrics"));

        for (int size : SIZES) {
            MemoryQuery query = store(size);
            MapRouteService service = new MapRouteService(query);

            long t0 = System.nanoTime();
            MapRoute route = service.prepare(cacheKey("file-" + size), 0, Long.MAX_VALUE / 4);
            long prepareMillis = millisSince(t0);
            assertEquals(size, route.model().size());

            t0 = System.nanoTime();
            int[] decimated = route.lod().indices(1e-4);
            long lodMillis = millisSince(t0);
            assertTrue(decimated.length <= size);

            t0 = System.nanoTime();
            MapRoute cached = service.prepare(cacheKey("file-" + size), 0, Long.MAX_VALUE / 4);
            long cachedMillis = millisSince(t0);
            assertSame(route, cached, "second open must be served from cache");

            RouteSpatialIndex spatial = route.spatial();
            Random random = new Random(7);
            t0 = System.nanoTime();
            for (int i = 0; i < 10_000; i++) {
                int source = random.nextInt(size);
                spatial.nearest(route.model().latitude(source), route.model().longitude(source));
            }
            long nearestMicros = microsSince(t0) / 10_000;

            t0 = System.nanoTime();
            for (int i = 0; i < 100_000; i++) {
                route.model().nearestIndexByTime(random.nextInt(size) * (SECOND / 10));
            }
            long timeLookupNanos = (System.nanoTime() - t0) / 100_000;

            t0 = System.nanoTime();
            for (int i = 0; i < 1_000; i++) {
                RouteRangeMetrics.compute(route.model(), size / 4, size / 2, MapRouteBuildOptions.defaults());
            }
            long metricsMillis = millisSince(t0) / 1_000;

            report.append(String.format("%-10d %8dms %8dms %8dms %8dus %8dns %8dms%n",
                    size, prepareMillis, lodMillis, cachedMillis, nearestMicros, timeLookupNanos, metricsMillis));

            assertTrue(prepareMillis < 30_000, size + " sample route preparation took " + prepareMillis + "ms");
            assertTrue(cachedMillis < 50, "cached reopen must be near-instant, was " + cachedMillis + "ms");
            assertTrue(nearestMicros < 500,
                    "nearest-point lookup must stay bounded, was " + nearestMicros + "us at " + size);
            assertTrue(timeLookupNanos < 20_000,
                    "timestamp lookup must stay logarithmic, was " + timeLookupNanos + "ns at " + size);

            service.clear();
        }
        System.out.print(report);
    }

    @Test
    void routeMemoryStaysProportionalToSampleCount() {
        MemoryQuery query = store(1_000_000);
        MapRoute route = new MapRouteService(query).prepare(cacheKey("memory"), 0, Long.MAX_VALUE / 4);

        long bytes = route.model().approximateBytes();
        long perSample = bytes / route.model().size();

        System.out.printf("Route model footprint: %,d bytes for %,d samples (%d bytes/sample)%n",
                bytes, route.model().size(), perSample);
        assertTrue(perSample <= 48,
                "primitive-array route must stay compact, was " + perSample + " bytes/sample");
    }

    @Test
    void repeatedOpenCloseCyclesDoNotGrowTheCache() {
        MemoryQuery query = store(50_000);
        MapRouteService service = new MapRouteService(query);

        for (int cycle = 0; cycle < 50; cycle++) {
            service.prepare(cacheKey("file-" + (cycle % 4)), 0, Long.MAX_VALUE / 4);
        }

        assertTrue(service.cachedRouteCount() <= MapRouteService.MAX_CACHED_ROUTES,
                "route cache must stay bounded across repeated open/close and file switches");
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static long microsSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000L;
    }
}
