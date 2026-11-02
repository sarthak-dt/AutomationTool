package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.desktop.presentation.GpsCatalogWindow;
import com.stellar.platform.desktop.presentation.ProjectRouteReportLoader;
import com.stellar.platform.desktop.presentation.RouteMapReportContext;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.map.MapRoute;
import com.stellar.platform.processing.map.MapRouteBuilder;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Map Route "GPS samples" caption is the catalog-window route count, not the graph
 * viewport and not the decimated polyline.
 */
class MapRouteSampleCountTest {

    private static final long SECOND = 1_000_000_000L;
    private static final int KNOWN_FILE_SAMPLES = 6_114;

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    void catalogWindowIgnoresAShorterGraphSpan() {
        List<SignalCatalogEntry> catalog = List.of(entry("file-a", "Latitude", 0, 10 * SECOND));
        long[] window = GpsCatalogWindow.inclusive(catalog);
        assertEquals(0L, window[0]);
        assertEquals(10 * SECOND, window[1]);
        assertTrue(window[1] > 4 * SECOND);
    }

    @Test
    void singleMeasurementKeepsSamplesOutsideTheGraphWindow() throws Exception {
        WindowedStore store = new WindowedStore();
        List<SignalSamplePoint> latitude = samples(KNOWN_FILE_SAMPLES, 18.52, 0.00001);
        List<SignalSamplePoint> longitude = samples(KNOWN_FILE_SAMPLES, 73.85, 0.00001);
        SignalCatalogKey lat = key("file-a", "Latitude");
        SignalCatalogKey lon = key("file-a", "Longitude");
        store.put(lat, latitude);
        store.put(lon, longitude);
        long last = latitude.get(KNOWN_FILE_SAMPLES - 1).timestampNanos();
        long graphEnd = latitude.get(KNOWN_FILE_SAMPLES - 3).timestampNanos();
        List<SignalCatalogEntry> catalog = List.of(
                entry(lat, KNOWN_FILE_SAMPLES, 0, last),
                entry(lon, KNOWN_FILE_SAMPLES, 0, last));

        MapRoutePane pane = newPane();
        runOnFx(() -> pane.activate(new MapRouteRequest(
                store, catalog, "dat", "file-a", "known.dat", 1L, 0, 0L, graphEnd)));
        awaitFx(() -> pane.routeSizeForTest() > 0);

        assertEquals(last, store.endSeen(lat));
        assertTrue(store.endSeen(lat) > graphEnd);
        assertEquals(KNOWN_FILE_SAMPLES, pane.routeSizeForTest());
        String status = pane.statusTextForTest();
        if (status.contains("GPS samples")) {
            assertTrue(status.contains("6,114"), status);
            assertFalse(status.contains("6,112"), status);
        }

        MapRoute built = new MapRoute(MapRouteBuilder.build(latitude, longitude, null));
        int[] drawn = built.lod().indices(1.0);
        assertTrue(drawn.length < KNOWN_FILE_SAMPLES, "decimation must drop display points");
        assertEquals(KNOWN_FILE_SAMPLES, built.model().size());
        assertEquals(KNOWN_FILE_SAMPLES, pane.routeSizeForTest());
        runOnFx(pane::dispose);
    }

    @Test
    void allMeasurementsStatusMatchesTheReportSampleCount() throws Exception {
        WindowedStore store = new WindowedStore();
        List<SignalCatalogEntry> catalog = new ArrayList<>();
        addFile(store, catalog, "file-a", 4);
        addFile(store, catalog, "file-b", 3);
        long graphEnd = 2 * SECOND;

        RouteMapReportContext report = new ProjectRouteReportLoader().load(
                true, "", "All Measurements", 2, catalog, store, List.of(), List.of());
        assertNotNull(report);
        assertEquals(7L, report.route().gpsSampleCount());
        assertEquals(2, report.route().routeSegmentCount());

        MapRoutePane pane = newPane();
        runOnFx(() -> pane.activate(MapRouteRequest.projectWide(
                store, catalog, "dat", 2, 1L, 0, 0L, graphEnd)));
        awaitFx(() -> pane.projectRouteForTest() != null && pane.projectRouteForTest().gpsSampleCount() > 0);

        assertEquals(report.route().gpsSampleCount(), pane.projectRouteForTest().gpsSampleCount());
        assertEquals(7L, pane.projectRouteForTest().gpsSampleCount());
        String card = pane.mappingAltitudeForTest();
        assertTrue(card.contains("7 GPS samples"), card);
        assertFalse(card.contains("6 GPS samples"), card);
        String status = pane.statusTextForTest();
        if (status.contains("GPS samples")) {
            assertTrue(status.contains("7 GPS samples"), status);
        }
        runOnFx(pane::dispose);
    }

    private static void addFile(WindowedStore store, List<SignalCatalogEntry> catalog, String source, int count) {
        List<SignalSamplePoint> latitude = samples(count, 18.5, 0.001);
        List<SignalSamplePoint> longitude = samples(count, 73.8, 0.001);
        SignalCatalogKey lat = key(source, "Latitude");
        SignalCatalogKey lon = key(source, "Longitude");
        store.put(lat, latitude);
        store.put(lon, longitude);
        long last = latitude.get(count - 1).timestampNanos();
        catalog.add(entry(lat, count, 0, last));
        catalog.add(entry(lon, count, 0, last));
    }

    private static List<SignalSamplePoint> samples(int count, double origin, double step) {
        List<SignalSamplePoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(new SignalSamplePoint(i * SECOND, origin + i * step, null, null));
        }
        return points;
    }

    private static SignalCatalogKey key(String source, String name) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "GPS", 0, 0, source, "");
    }

    private static SignalCatalogEntry entry(String source, String name, long first, long last) {
        return entry(key(source, name), 1, first, last);
    }

    private static SignalCatalogEntry entry(SignalCatalogKey key, int samples, long first, long last) {
        return new SignalCatalogEntry(key, "deg", samples, first, last, null, null, key.sourceFile(), "");
    }

    private static final class WindowedStore implements GraphSeriesQuery {
        private final Map<SignalCatalogKey, List<SignalSamplePoint>> data = new HashMap<>();
        private final Map<SignalCatalogKey, Long> ends = new HashMap<>();

        void put(SignalCatalogKey key, List<SignalSamplePoint> samples) {
            data.put(key, samples);
        }

        long endSeen(SignalCatalogKey key) {
            return ends.getOrDefault(key, -1L);
        }

        @Override
        public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
            ends.put(key, endNanos);
            List<SignalSamplePoint> matched = new ArrayList<>();
            for (SignalSamplePoint point : data.getOrDefault(key, List.of())) {
                if (point.timestampNanos() >= startNanos && point.timestampNanos() <= endNanos) {
                    matched.add(point);
                }
            }
            return matched;
        }

        @Override
        public String unit(SignalCatalogKey key) {
            return "deg";
        }

        @Override
        public long sampleCount(SignalCatalogKey key) {
            return data.getOrDefault(key, List.of()).size();
        }
    }

    private static MapRoutePane newPane() throws Exception {
        AtomicReference<MapRoutePane> ref = new AtomicReference<>();
        runOnFx(() -> ref.set(new MapRoutePane()));
        return ref.get();
    }

    private static void awaitFx(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            AtomicBoolean satisfied = new AtomicBoolean();
            runOnFx(() -> satisfied.set(condition.getAsBoolean()));
            if (satisfied.get()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("condition not satisfied within timeout");
    }

    private static void runOnFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }
}
