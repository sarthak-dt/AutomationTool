package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.desktop.presentation.MeasurementCatalogScope;
import com.stellar.platform.desktop.ui.control.GpsSignalMappingModal;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.map.GpsSignalMapping;
import com.stellar.platform.processing.map.GpsSignalRole;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GPS Configuration must open for All Measurements. The dialog — not Map Route — chooses
 * the physical source. No first-file / whole-project catalog fallback.
 */
class MapRouteGpsConfigurationMultiMeasurementTest {

    private static final long SECOND = 1_000_000_000L;
    private static final int MEASUREMENT_COUNT = 20;
    private static final int MEASUREMENT_12 = 12;

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
    void singleMeasurementOpensWithThatSourcePreselected() throws Exception {
        List<SignalCatalogEntry> catalog = catalogForSources(1, 0);
        MapRoutePane pane = activateSingle(catalog, sourceId(0), fileName(0));
        runOnFx(pane::openMappingEditorForTest);
        assertTrue(pane.mappingEditorVisibleForTest());
        assertTrue(pane.mappingEditEnabledForTest());
        assertEquals(sourceId(0), pane.mappingEditorForTest().selectedMeasurementSourceIdForTest());
        assertEquals(1, pane.mappingEditorForTest().measurementSourceIdsForTest().size());
        disposeOnFx(pane);
    }

    @Test
    void twentyMeasurementsAllScopeOpensWithoutBorrowingAPhysicalSource() throws Exception {
        List<SignalCatalogEntry> catalog = catalogForSources(MEASUREMENT_COUNT, 0, 2, 11);
        assertEquals(MEASUREMENT_COUNT, MeasurementCatalogScope.measurements(catalog).size());
        MapRoutePane pane = activateAll(catalog);
        assertTrue(pane.mappingEditEnabledForTest(),
                "Change Mapping must stay enabled when All Measurements is the analysis scope");
        runOnFx(pane::openMappingEditorForTest);
        assertTrue(pane.mappingEditorVisibleForTest(),
                "GPS Configuration must open for All Measurements; the dialog chooses the physical source");
        GpsSignalMappingModal modal = pane.mappingEditorForTest();
        assertEquals(MEASUREMENT_COUNT, modal.measurementSourceIdsForTest().size());
        assertEquals("", modal.selectedMeasurementSourceIdForTest(),
                "All Measurements must not silently preselect a physical source");
        assertTrue(modal.catalogSignalNamesForTest().isEmpty(),
                "no physical catalog may be loaded before the user chooses a measurement");
        assertEquals("", modal.draftSourceIdForTest());
        disposeOnFx(pane);
    }

    @Test
    void twentyMeasurementsSpecificSourceOpensPreselected() throws Exception {
        List<SignalCatalogEntry> catalog = catalogForSources(MEASUREMENT_COUNT, MEASUREMENT_12);
        MapRoutePane pane = activateSingle(catalogForOne(catalog, sourceId(MEASUREMENT_12)),
                sourceId(MEASUREMENT_12), fileName(MEASUREMENT_12));
        runOnFx(pane::openMappingEditorForTest);
        assertTrue(pane.mappingEditorVisibleForTest());
        assertEquals(sourceId(MEASUREMENT_12), pane.mappingEditorForTest().selectedMeasurementSourceIdForTest());
        disposeOnFx(pane);
    }

    @Test
    void measurementWithoutGpsOpensWithoutBorrowingSiblingSignals() throws Exception {
        List<SignalCatalogEntry> catalog = catalogForSources(4, 0, 2);
        MapRoutePane pane = activateAll(catalog);
        runOnFx(pane::openMappingEditorForTest);
        runOnFx(() -> pane.mappingEditorForTest().selectMeasurementForTest(sourceId(1)));
        GpsSignalMappingModal modal = pane.mappingEditorForTest();
        assertTrue(pane.mappingEditorVisibleForTest());
        assertEquals(sourceId(1), modal.selectedMeasurementSourceIdForTest());
        assertFalse(modal.catalogSignalNamesForTest().contains("GPS_LAT_INFO"));
        assertFalse(modal.catalogSignalNamesForTest().contains("GPS_LONG_INFO"));
        assertTrue(modal.saveDisabledForTest());
        assertEquals("Route cannot be rendered", modal.bannerTitleForTest());
        disposeOnFx(pane);
    }

    @Test
    void mappingsStayOwnedByPhysicalSource() throws Exception {
        List<SignalCatalogEntry> catalog = catalogForSources(2, 0, 1);
        AtomicReference<List<GpsSignalMapping>> persisted = new AtomicReference<>(List.of());
        MapRoutePane pane = activateAll(catalog);
        runOnFx(() -> pane.setMappingPersistence(persistence(persisted)));
        runOnFx(pane::openMappingEditorForTest);
        runOnFx(() -> {
            GpsSignalMappingModal modal = pane.mappingEditorForTest();
            modal.selectMeasurementForTest(sourceId(0));
            modal.pickRoleForTest(GpsSignalRole.LATITUDE, "GPS_LAT_INFO");
            modal.pickRoleForTest(GpsSignalRole.LONGITUDE, "GPS_LONG_INFO");
            modal.saveForTest();
        });
        awaitFx(() -> !pane.mappingEditorVisibleForTest());
        runOnFx(pane::openMappingEditorForTest);
        runOnFx(() -> {
            GpsSignalMappingModal modal = pane.mappingEditorForTest();
            modal.selectMeasurementForTest(sourceId(1));
            modal.pickRoleForTest(GpsSignalRole.LATITUDE, "GPS_LAT_INFO");
            modal.pickRoleForTest(GpsSignalRole.LONGITUDE, "GPS_LONG_INFO");
            modal.saveForTest();
        });
        awaitFx(() -> !pane.mappingEditorVisibleForTest());
        List<GpsSignalMapping> stored = persisted.get();
        assertEquals(2, stored.stream().filter(m -> sourceId(0).equals(m.sourceId())).count());
        assertEquals(2, stored.stream().filter(m -> sourceId(1).equals(m.sourceId())).count());
        assertTrue(stored.stream()
                .filter(m -> sourceId(0).equals(m.sourceId()) && m.role() == GpsSignalRole.LATITUDE)
                .allMatch(m -> "GPS_LAT_INFO".equals(m.requestedSignalName())));
        runOnFx(pane::openMappingEditorForTest);
        runOnFx(() -> pane.mappingEditorForTest().selectMeasurementForTest(sourceId(0)));
        assertEquals("GPS_LAT_INFO", pane.mappingEditorForTest().assignedSignalForTest(GpsSignalRole.LATITUDE));
        assertEquals(sourceId(0), pane.mappingEditorForTest().draftSourceIdForTest());
        disposeOnFx(pane);
    }

    @Test
    void rapidMeasurementSwitchKeepsOnlyTheFinalSource() throws Exception {
        List<SignalCatalogEntry> catalog = catalogForSources(3, 0, 1, 2);
        MapRoutePane pane = activateAll(catalog);
        runOnFx(pane::openMappingEditorForTest);
        runOnFx(() -> {
            GpsSignalMappingModal modal = pane.mappingEditorForTest();
            modal.selectMeasurementForTest(sourceId(0));
            modal.selectMeasurementForTest(sourceId(1));
            modal.selectMeasurementForTest(sourceId(2));
            modal.selectMeasurementForTest(sourceId(0));
        });
        GpsSignalMappingModal modal = pane.mappingEditorForTest();
        assertEquals(sourceId(0), modal.selectedMeasurementSourceIdForTest());
        assertEquals(sourceId(0), modal.draftSourceIdForTest());
        assertTrue(modal.catalogSignalNamesForTest().stream().allMatch(name ->
                modal.catalogSourceIdsForTest().stream().allMatch(id -> id.equals(sourceId(0)))));
        assertFalse(modal.catalogSignalNamesForTest().isEmpty());
        disposeOnFx(pane);
    }

    @Test
    void saveCloseReopenAllScopeStillOpensWithOwnedMappings() throws Exception {
        List<SignalCatalogEntry> catalog = catalogForSources(3, 0, 1);
        AtomicReference<List<GpsSignalMapping>> persisted = new AtomicReference<>(List.of());
        MapRoutePane pane = activateAll(catalog);
        runOnFx(() -> pane.setMappingPersistence(persistence(persisted)));
        runOnFx(pane::openMappingEditorForTest);
        runOnFx(() -> {
            GpsSignalMappingModal modal = pane.mappingEditorForTest();
            modal.selectMeasurementForTest(sourceId(1));
            modal.pickRoleForTest(GpsSignalRole.LATITUDE, "GPS_LAT_INFO");
            modal.pickRoleForTest(GpsSignalRole.LONGITUDE, "GPS_LONG_INFO");
            modal.saveForTest();
        });
        awaitFx(() -> !pane.mappingEditorVisibleForTest());
        runOnFx(pane::closeMappingEditorForTest);
        runOnFx(pane::openMappingEditorForTest);
        assertTrue(pane.mappingEditorVisibleForTest());
        runOnFx(() -> pane.mappingEditorForTest().selectMeasurementForTest(sourceId(1)));
        assertEquals("GPS_LAT_INFO", pane.mappingEditorForTest().assignedSignalForTest(GpsSignalRole.LATITUDE));
        assertEquals(sourceId(1), pane.mappingEditorForTest().draftSourceIdForTest());
        assertEquals(2, persisted.get().stream().filter(m -> sourceId(1).equals(m.sourceId())).count());
        disposeOnFx(pane);
    }

    private static MapRoutePane activateAll(List<SignalCatalogEntry> catalog) throws Exception {
        MapRoutePane pane = newPane();
        FakeStore store = new FakeStore();
        runOnFx(() -> pane.activate(MapRouteRequest.projectWide(
                store, catalog, "project-1", MEASUREMENT_COUNT, 1L, 0, 0, 10_000 * SECOND)));
        awaitFx(() -> pane.projectRouteForTest() != null);
        return pane;
    }

    private static MapRoutePane activateSingle(
            List<SignalCatalogEntry> catalog,
            String sourceId,
            String fileName
    ) throws Exception {
        MapRoutePane pane = newPane();
        FakeStore store = new FakeStore();
        runOnFx(() -> pane.activate(new MapRouteRequest(
                store, catalog, "project-1", sourceId, fileName, 1L, 0, 0, 10_000 * SECOND)));
        pumpFx();
        return pane;
    }

    private static MapRoutePane newPane() throws Exception {
        AtomicReference<MapRoutePane> ref = new AtomicReference<>();
        runOnFx(() -> ref.set(new MapRoutePane()));
        return ref.get();
    }

    private static void disposeOnFx(MapRoutePane pane) throws Exception {
        runOnFx(pane::dispose);
    }

    private static MapRoutePane.RouteMappingPersistence persistence(
            AtomicReference<List<GpsSignalMapping>> persisted
    ) {
        return new MapRoutePane.RouteMappingPersistence() {
            @Override
            public List<GpsSignalMapping> loadProjectMappings() {
                return persisted.get();
            }

            @Override
            public List<GpsSignalMapping> loadDefaults() {
                return List.of();
            }

            @Override
            public boolean saveProjectMappings(String sourceId, String fingerprint, List<GpsSignalMapping> mappings) {
                List<GpsSignalMapping> next = new ArrayList<>();
                for (GpsSignalMapping row : persisted.get()) {
                    if (!sourceId.equals(row.sourceId())) {
                        next.add(row);
                    }
                }
                next.addAll(mappings);
                persisted.set(List.copyOf(next));
                return true;
            }
        };
    }

    private static List<SignalCatalogEntry> catalogForSources(int count, int... gpsIndexes) {
        boolean[] gps = new boolean[count];
        for (int index : gpsIndexes) {
            if (index >= 0 && index < count) {
                gps[index] = true;
            }
        }
        List<SignalCatalogEntry> catalog = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (gps[i]) {
                catalog.add(entry(sourceId(i), fileName(i), "GPS_LAT_INFO", "deg", 120, 12.9 + i * 0.01, 13.0 + i * 0.01));
                catalog.add(entry(sourceId(i), fileName(i), "GPS_LONG_INFO", "deg", 120, 77.5, 77.7));
            } else {
                catalog.add(entry(sourceId(i), fileName(i), "EngineSpeed", "rpm", 80, 700, 4200));
            }
        }
        return List.copyOf(catalog);
    }

    private static List<SignalCatalogEntry> catalogForOne(List<SignalCatalogEntry> catalog, String sourceId) {
        return catalog.stream()
                .filter(e -> sourceId.equals(e.key().sourceFile()))
                .toList();
    }

    private static SignalCatalogEntry entry(
            String sourceId,
            String fileName,
            String name,
            String unit,
            int samples,
            double min,
            double max
    ) {
        SignalCatalogKey key = new SignalCatalogKey(
                name, SignalSourceType.DBC_CAN, "GPS_COORDINATE_22C", 556, 3, sourceId, "veh.dbc");
        return new SignalCatalogEntry(key, unit, samples, 0L, samples * SECOND, min, max, fileName, "veh.dbc");
    }

    private static String sourceId(int index) {
        return "src-" + index;
    }

    private static String fileName(int index) {
        return "drive_" + String.format("%02d", index) + ".asc";
    }

    private static final class FakeStore implements GraphSeriesQuery {
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

    private static void pumpFx() throws Exception {
        for (int i = 0; i < 4; i++) {
            runOnFx(() -> { });
            Thread.sleep(15);
        }
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
