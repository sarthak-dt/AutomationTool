package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.map.GpsSemanticResolver;
import com.stellar.platform.processing.map.GpsSignalMapping;
import com.stellar.platform.processing.map.GpsSignalRole;
import com.stellar.platform.processing.time.SharedTimeSelectionModel;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A GPS mapping edited in Configuration has to reach Map Route immediately.
 *
 * <p>{@code MapRoutePane.activate} deliberately short-circuits when the request signature is
 * unchanged, which is what keeps tab switching cheap. These tests pin the behaviour that makes that
 * safe: a changed mapping invalidates the resolved roles and the cached route, so the
 * short-circuit can never hand back a route built from the previous mapping.
 */
class MapRouteMappingRefreshTest {

    private static final long SECOND = 1_000_000_000L;
    private static final String FILE = "drive.mf4";

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
    @Timeout(90)
    void changingTheMappingRebuildsTheRouteEvenWhenTheRequestSignatureIsUnchanged() throws Exception {
        FakeStore store = store();
        List<SignalCatalogEntry> catalog = catalog();
        String fingerprint = GpsSemanticResolver.schemaFingerprint(catalog);
        MapRoutePane pane = newPane();
        MapRouteRequest request = request(store, catalog);

        onFx(() -> pane.activate(request));
        awaitFx(() -> pane.routeSizeForTest() == 60);
        assertEquals("Latitude", valueOf(pane::mappingLatitudeForTest),
                "the auto-detected coordinate should be in use first");

        // Re-activating with the same signature must not re-read the store.
        int readsAfterFirstRoute = store.reads.get();
        onFx(() -> pane.activate(request(store, catalog)));
        awaitFx(() -> pane.routeSizeForTest() == 60);
        assertEquals(readsAfterFirstRoute, store.reads.get(),
                "an unchanged activation must stay a short-circuit");

        // Configuration saves a different mapping; the very next activation uses identical inputs.
        onFx(() -> pane.setProjectMappings(List.of(
                mapping(fingerprint, GpsSignalRole.LATITUDE, "Pos01"),
                mapping(fingerprint, GpsSignalRole.LONGITUDE, "Pos02"))));
        awaitFx(() -> "Pos01".equals(pane.mappingLatitudeForTest()));

        onFx(() -> pane.activate(request(store, catalog)));
        awaitFx(() -> pane.routeSizeForTest() == 60);
        assertEquals("Pos01", valueOf(pane::mappingLatitudeForTest));
        assertEquals("Pos02", valueOf(() -> pane.resolvedGpsRoles()
                .get(GpsSignalRole.LONGITUDE).signalName()));
        assertTrue(store.reads.get() > readsAfterFirstRoute,
                "the route must be rebuilt from the newly mapped signals");
        // Pos01/Pos02 carry a different track, so the route really came from the new signals.
        assertTrue(valueOf(pane::routeSizeForTest) > 0);
        assertFalse(valueOf(pane::emptyStateVisibleForTest));
        onFx(pane::dispose);
    }

    @Test
    @Timeout(90)
    void anIdenticalMappingIsNotTreatedAsAChange() throws Exception {
        FakeStore store = store();
        List<SignalCatalogEntry> catalog = catalog();
        String fingerprint = GpsSemanticResolver.schemaFingerprint(catalog);
        List<GpsSignalMapping> saved = List.of(
                mapping(fingerprint, GpsSignalRole.LATITUDE, "Pos01"),
                mapping(fingerprint, GpsSignalRole.LONGITUDE, "Pos02"));

        MapRoutePane pane = newPane();
        onFx(() -> {
            pane.setProjectMappings(saved);
            pane.activate(request(store, catalog));
        });
        awaitFx(() -> pane.routeSizeForTest() == 60);
        int reads = store.reads.get();

        // Re-pushing the same rows with fresh timestamps must not invalidate anything.
        onFx(() -> pane.setProjectMappings(List.of(
                mapping(fingerprint, GpsSignalRole.LATITUDE, "Pos01"),
                mapping(fingerprint, GpsSignalRole.LONGITUDE, "Pos02"))));
        onFx(() -> pane.activate(request(store, catalog)));
        awaitFx(() -> pane.routeSizeForTest() == 60);
        assertEquals(reads, store.reads.get(), "an unchanged mapping must not rebuild the route");
        onFx(pane::dispose);
    }

    @Test
    @Timeout(90)
    void theRouteSurvivesBasemapFallbackAllTheWayToNeutral() throws Exception {
        FakeStore store = store();
        List<SignalCatalogEntry> catalog = catalog();
        MapRoutePane pane = englishBasemapPane();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        onFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(pane), 720, 520));
            stage.show();
            stageRef.set(stage);
            pane.activate(request(store, catalog));
            // Leaflet loads the fixture's test:// tiles on its own and reports tileerror.
            // That callback uses the same fallback entry as failBasemapModeForTest, so leaving
            // it attached makes the scripted call a second failure and correctly lands on NEUTRAL.
            // The view handler is detached for this test only. The scripted calls below invoke
            // onBasemapModeFailed directly and remain the only fallback transitions.
            LeafletBasemapView view = pane.basemapViewForTest();
            if (view == null) {
                fail("basemap view was not built; the controlled failure sequence cannot be isolated");
            }
            view.setModeFailedHandler(ignored -> { });
        });
        awaitFx(() -> pane.routeSizeForTest() == 60);

        String initialProvider = valueOf(pane::basemapProviderIdForTest);
        MapBasemapMode initialMode = valueOf(pane::basemapModeForTest);
        MapBasemapCompliance initialCompliance = valueOf(pane::basemapComplianceForTest);
        int initialRoute = valueOf(pane::routeSizeForTest);
        if (!"TEST_ENGLISH".equals(initialProvider)
                || initialMode != MapBasemapMode.ONLINE
                || initialCompliance != MapBasemapCompliance.ENGLISH_COMPLIANT
                || initialRoute != 60) {
            fail("English provider changed before the controlled failure. mode=" + initialMode
                    + " provider=" + initialProvider
                    + " compliance=" + initialCompliance
                    + " routeSamples=" + initialRoute
                    + ". An asynchronous tile failure advanced the fallback; this is not a pass.");
        }
        assertEquals(new TransparentMapBackground().displayName(),
                valueOf(pane::routeCanvasBackgroundForTest));
        assertFalse(valueOf(pane::emptyStateVisibleForTest));

        onFx(() -> pane.failBasemapModeForTest(MapBasemapMode.ONLINE));
        assertEquals(MapBasemapMode.ONLINE, valueOf(pane::basemapModeForTest),
                "one English failure must keep a usable OSM map");
        assertEquals(MapProviderIds.OSM_CARTO, valueOf(pane::basemapProviderIdForTest));
        assertEquals(MapBasemapCompliance.STANDARD_FALLBACK, valueOf(pane::basemapComplianceForTest));
        assertEquals(new TransparentMapBackground().displayName(),
                valueOf(pane::routeCanvasBackgroundForTest));
        assertEquals(60, valueOf(pane::routeSizeForTest));
        assertFalse(valueOf(pane::emptyStateVisibleForTest));

        onFx(() -> pane.failBasemapModeForTest(MapBasemapMode.ONLINE));
        assertEquals(MapBasemapMode.NEUTRAL, valueOf(pane::basemapModeForTest));
        assertEquals(MapBasemapCompliance.NEUTRAL, valueOf(pane::basemapComplianceForTest));
        assertEquals(new NeutralMapBackground().displayName(),
                valueOf(pane::routeCanvasBackgroundForTest),
                "a neutral basemap needs an opaque canvas backdrop again");
        assertEquals(60, valueOf(pane::routeSizeForTest),
                "the GPS route must remain usable when the basemap cannot load");
        assertFalse(valueOf(pane::emptyStateVisibleForTest));
        assertTrue(valueOf(pane::mapDiagnostics).contains("routeSamples=60"));

        onFx(() -> {
            pane.dispose();
            stageRef.get().close();
        });
    }

    // ------------------------------------------------------------------

    private static MapRoutePane newPane() throws Exception {
        AtomicReference<MapRoutePane> ref = new AtomicReference<>();
        onFx(() -> ref.set(new MapRoutePane()));
        return ref.get();
    }

    private static MapRoutePane englishBasemapPane() throws Exception {
        MapTileProvider english = new MapTileProvider() {
            @Override public String providerId() { return "TEST_ENGLISH"; }
            @Override public String displayName() { return "test-english"; }
            @Override public String tileUrlTemplate() { return "test://en/{z}/{x}/{y}.png"; }
            @Override public String attributionHtml() { return "© test"; }
            @Override public int maxZoom() { return 19; }
            @Override public boolean supportsEnglishLabels() { return true; }
        };
        MapProviderResolver resolver = new MapProviderResolver(
                MapBasemapSettings.defaults(),
                List.of(english),
                new OfflineMapPackRegistry(Path.of("no-stellar-english-packs")),
                true);
        AtomicReference<MapRoutePane> ref = new AtomicReference<>();
        onFx(() -> ref.set(new MapRoutePane(new SharedTimeSelectionModel(), resolver)));
        return ref.get();
    }

    private static GpsSignalMapping mapping(String fingerprint, GpsSignalRole role, String signal) {
        return new GpsSignalMapping("project-1", FILE, fingerprint, role, key(signal), true, Instant.now());
    }

    private static SignalCatalogKey key(String name) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "GPS", 0);
    }

    private static SignalCatalogEntry entry(String name, String unit, int samples) {
        return new SignalCatalogEntry(key(name), unit, samples, 0L, samples * SECOND, null, null, FILE, "");
    }

    private static List<SignalCatalogEntry> catalog() {
        return List.of(
                entry("Latitude", "deg", 60),
                entry("Longitude", "deg", 60),
                entry("Pos01", "deg", 60),
                entry("Pos02", "deg", 60));
    }

    private static FakeStore store() {
        FakeStore store = new FakeStore();
        List<SignalSamplePoint> lat = new ArrayList<>();
        List<SignalSamplePoint> lon = new ArrayList<>();
        List<SignalSamplePoint> altLat = new ArrayList<>();
        List<SignalSamplePoint> altLon = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            lat.add(new SignalSamplePoint(i * SECOND, 18.5 + i * 0.0002, null, null));
            lon.add(new SignalSamplePoint(i * SECOND, 73.7 + i * 0.0002, null, null));
            altLat.add(new SignalSamplePoint(i * SECOND, 51.4 + i * 0.0003, null, null));
            altLon.add(new SignalSamplePoint(i * SECOND, -0.12 + i * 0.0003, null, null));
        }
        store.put("Latitude", "deg", lat);
        store.put("Longitude", "deg", lon);
        store.put("Pos01", "deg", altLat);
        store.put("Pos02", "deg", altLon);
        return store;
    }

    private static MapRouteRequest request(FakeStore store, List<SignalCatalogEntry> catalog) {
        return new MapRouteRequest(store, catalog, "project-1", FILE, 1L, 0, 10_000 * SECOND);
    }

    /** Minimal signal store; counts reads so a cached route can be proven to re-query nothing. */
    private static final class FakeStore implements GraphSeriesQuery {
        private final Map<SignalCatalogKey, List<SignalSamplePoint>> data = new HashMap<>();
        private final Map<SignalCatalogKey, String> units = new HashMap<>();
        final AtomicInteger reads = new AtomicInteger();

        void put(String name, String unit, List<SignalSamplePoint> samples) {
            data.put(key(name), samples);
            units.put(key(name), unit);
        }

        @Override
        public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
            reads.incrementAndGet();
            return data.getOrDefault(key, List.of());
        }

        @Override
        public String unit(SignalCatalogKey key) {
            return units.getOrDefault(key, "");
        }

        @Override
        public long sampleCount(SignalCatalogKey key) {
            return data.getOrDefault(key, List.of()).size();
        }
    }

    private static void onFx(Runnable action) throws Exception {
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
        assertTrue(done.await(30, TimeUnit.SECONDS), "FX action timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static <T> T valueOf(Supplier<T> supplier) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        onFx(() -> out.set(supplier.get()));
        return out.get();
    }

    private static void awaitFx(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (valueOf(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(40);
        }
        throw new AssertionError("condition not met within 30s");
    }
}
