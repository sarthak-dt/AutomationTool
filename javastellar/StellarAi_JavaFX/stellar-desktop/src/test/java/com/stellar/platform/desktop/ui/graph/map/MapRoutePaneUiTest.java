package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.drive.DriveSegmentAnalysisService;
import com.stellar.platform.processing.drive.DriveSegmentSelection;
import com.stellar.platform.processing.drive.DriveSegmentSummary;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.map.GpsSignalMapping;
import com.stellar.platform.processing.map.GpsSignalRole;
import com.stellar.platform.processing.map.GpsSemanticResolver;
import com.stellar.platform.desktop.ui.control.GpsSignalMappingModal;
import com.stellar.platform.processing.time.SharedTimeSelectionModel;
import com.stellar.platform.processing.time.TimeSelection;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Map Route tab behaviour: lazy initialisation, selection modes, shared-filter publication and
 * file-change safety. Nothing here touches a measurement file — the pane is fed a catalog and a
 * time-series query, which is exactly what the workspace hands it at runtime.
 */
class MapRoutePaneUiTest {

    private static final long SECOND = 1_000_000_000L;

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

    // ------------------------------------------------------------------
    // Test doubles
    // ------------------------------------------------------------------

    /** Minimal signal store; counts reads so tests can prove a cached route re-queries nothing. */
    private static final class FakeStore implements GraphSeriesQuery {
        private final Map<SignalCatalogKey, List<SignalSamplePoint>> data = new HashMap<>();
        private final Map<SignalCatalogKey, String> units = new HashMap<>();
        final AtomicInteger reads = new AtomicInteger();

        void put(String name, String unit, List<SignalSamplePoint> samples) {
            put(key(name), unit, samples);
        }

        void put(SignalCatalogKey key, String unit, List<SignalSamplePoint> samples) {
            data.put(key, samples);
            units.put(key, unit);
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

    private static SignalCatalogKey key(String name) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "GPS", 0);
    }

    private static SignalCatalogKey dbcKey(String name, String message, long canId) {
        return new SignalCatalogKey(name, SignalSourceType.DBC_CAN, message, canId, 3, "bengaluru.mf4", "3.dbc");
    }

    private static SignalCatalogEntry entry(String name, String unit, int samples) {
        return new SignalCatalogEntry(key(name), unit, samples, 0L, samples * SECOND, null, null, "drive.mf4", "");
    }

    private static SignalCatalogEntry dbcEntry(SignalCatalogKey key, String unit, int samples, Double min, Double max) {
        return new SignalCatalogEntry(key, unit, samples, 0L, samples * SECOND, min, max, key.sourceFile(), key.sourceDatabase());
    }

    private static FakeStore gpsStore(int samples) {
        FakeStore store = new FakeStore();
        List<SignalSamplePoint> latitude = new ArrayList<>(samples);
        List<SignalSamplePoint> longitude = new ArrayList<>(samples);
        List<SignalSamplePoint> altitude = new ArrayList<>(samples);
        List<SignalSamplePoint> speed = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
            latitude.add(new SignalSamplePoint(i * SECOND, 18.5 + i * 0.0002, null, null));
            longitude.add(new SignalSamplePoint(i * SECOND, 73.7 + i * 0.0002, null, null));
            altitude.add(new SignalSamplePoint(i * SECOND, 560.0 + (i % 20), null, null));
            speed.add(new SignalSamplePoint(i * SECOND, 40.5 + (i % 15), null, null));
        }
        store.put("Latitude", "deg", latitude);
        store.put("Longitude", "deg", longitude);
        store.put("Altitude", "m", altitude);
        store.put("GPS_Speed", "km/h", speed);
        return store;
    }

    private static List<SignalCatalogEntry> gpsCatalog(int samples) {
        return List.of(
                entry("Latitude", "deg", samples),
                entry("Longitude", "deg", samples),
                entry("Altitude", "m", samples),
                entry("GPS_Speed", "km/h", samples));
    }

    private static MapRouteRequest request(FakeStore store, List<SignalCatalogEntry> catalog, String fileId) {
        return new MapRouteRequest(store, catalog, "project-1", fileId, 1L, 0, 10_000 * SECOND);
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    void constructionDoesNotBuildTheHeavyUi() throws Exception {
        AtomicBoolean built = new AtomicBoolean(true);
        AtomicInteger reads = new AtomicInteger(-1);
        FakeStore store = gpsStore(50);
        runOnFx(() -> {
            MapRoutePane pane = new MapRoutePane();
            built.set(pane.uiBuiltForTest());
            reads.set(store.reads.get());
        });
        assertFalse(built.get(), "an unopened Map Route tab must not build its UI");
        assertEquals(0, reads.get(), "an unopened Map Route tab must not read signals");
    }

    @Test
    void mapPanelHasNoSecondMeasurementChooser() throws Exception {
        FakeStore store = gpsStore(20);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(20), "file-a");
        AtomicInteger combos = new AtomicInteger(-1);
        runOnFx(() -> combos.set(pane.lookupAll(".combo-box").size()));
        assertEquals(0, combos.get(), "Active Measurement is the only measurement selector");
        disposeOnFx(pane);
    }

    @Test
    void activationBuildsARouteFromTheExistingSignalStore() throws Exception {
        FakeStore store = gpsStore(500);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(500), "file-a");

        AtomicInteger size = new AtomicInteger();
        AtomicBoolean empty = new AtomicBoolean(true);
        runOnFx(() -> {
            size.set(pane.routeSizeForTest());
            empty.set(pane.emptyStateVisibleForTest());
        });

        assertEquals(500, size.get());
        assertFalse(empty.get());
        disposeOnFx(pane);
    }

    @Test
    void leavingTheMapTabDropsAnInFlightRouteAndTheNextOpenLoadsIt() throws Exception {
        FakeStore store = gpsStore(200);
        MapRoutePane pane = newPane();
        runOnFx(() -> {
            for (int i = 0; i < 8; i++) {
                pane.activate(request(store, gpsCatalog(200), "file-a"));
                pane.deactivate();
                assertTrue(pane.routeCanvasSuspendedForTest(), "hidden map must not keep painting");
            }
        });
        pumpFx();
        runOnFx(() -> assertEquals(0, pane.routeSizeForTest(), "a deactivated prepare must not paint"));
        runOnFx(() -> pane.activate(request(store, gpsCatalog(200), "file-a")));
        awaitFx(() -> pane.routeSizeForTest() > 0);
        disposeOnFx(pane);
    }

    @Test
    void disposeWhileGpsPreparationIsActiveDoesNotApplyTheRoute() throws Exception {
        FakeStore store = gpsStore(400);
        MapRoutePane pane = newPane();
        runOnFx(() -> {
            pane.activate(request(store, gpsCatalog(400), "file-a"));
            pane.dispose();
            pane.deactivate();
        });
        pumpFx();
        runOnFx(() -> assertEquals(0, pane.routeSizeForTest()));
    }

    @Test
    void reactivationReusesThePreparedRouteWithoutRereadingSignals() throws Exception {
        FakeStore store = gpsStore(200);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(200), "file-a");
        int afterFirst = store.reads.get();

        runOnFx(() -> pane.activate(request(store, gpsCatalog(200), "file-a")));
        pumpFx();

        assertEquals(afterFirst, store.reads.get(), "reopening Map Route must not re-read the signal store");
        AtomicReference<LeafletBasemapView> first = new AtomicReference<>();
        AtomicReference<LeafletBasemapView> second = new AtomicReference<>();
        AtomicInteger genBefore = new AtomicInteger();
        AtomicInteger genAfter = new AtomicInteger();
        runOnFx(() -> {
            first.set(pane.basemapViewForTest());
            genBefore.set(first.get().resizeGenerationForTest());
            pane.notifyLayoutChanged("tab-switch");
            pane.activate(request(store, gpsCatalog(200), "file-a"));
            second.set(pane.basemapViewForTest());
            genAfter.set(second.get().resizeGenerationForTest());
        });
        assertTrue(first.get() == second.get() && first.get() != null,
                "tab switch must not recreate the WebView/Leaflet map");
        assertTrue(genAfter.get() > genBefore.get(), "tab switch must request a coalesced invalidateSize");
        disposeOnFx(pane);
    }

    @Test
    void singlePointSelectionPublishesAPointFilter() throws Exception {
        FakeStore store = gpsStore(300);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(300), "file-a");
        List<TimeSelection> published = new ArrayList<>();
        runOnFx(() -> pane.sharedSelection().addListener(published::add));

        runOnFx(() -> {
            pane.applyModeForTest(MapSelectionMode.SINGLE_POINT);
            pane.pickForTest(120);
        });
        pumpFx();

        assertEquals(120, pane.selectedIndexForTest());
        TimeSelection last = pane.sharedSelection().selection();
        assertEquals(TimeSelection.Kind.POINT, last.kind());
        assertEquals(120 * SECOND, last.startNanos());
        assertEquals(TimeSelection.Origin.MAP, last.origin());
        TimeSelection range = pane.sharedSelection().analysisRange();
        assertEquals(TimeSelection.Kind.RANGE, range.kind());
        assertEquals(0L, range.startNanos());
        assertEquals(299 * SECOND, range.endNanos());
        assertFalse(published.isEmpty());
        disposeOnFx(pane);
    }

    @Test
    void twoClicksInRangeModePublishAnInclusiveInterval() throws Exception {
        FakeStore store = gpsStore(300);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(300), "file-a");

        runOnFx(() -> {
            pane.applyModeForTest(MapSelectionMode.A_B_RANGE);
            pane.pickForTest(40);
            pane.pickForTest(200);
        });
        pumpFx();

        assertEquals(40, pane.rangeIndicesForTest()[0]);
        assertEquals(200, pane.rangeIndicesForTest()[1]);
        TimeSelection selection = pane.sharedSelection().analysisRange();
        assertEquals(TimeSelection.Kind.RANGE, selection.kind());
        assertEquals(40 * SECOND, selection.startNanos());
        assertEquals(200 * SECOND, selection.endNanos());
        assertEquals(TimeSelection.Origin.MAP_SEGMENT, selection.origin());
        disposeOnFx(pane);
    }

    @Test
    void sample100AndSample500PublishPositiveDurationAndServiceTimestamps() throws Exception {
        FakeStore store = gpsStore(600);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(600), "file-a");
        AtomicInteger rangePublications = new AtomicInteger();
        runOnFx(() -> pane.sharedSelection().addListener(selection -> {
            if (selection.kind() == TimeSelection.Kind.RANGE
                    && selection.origin() == TimeSelection.Origin.MAP_SEGMENT
                    && selection.startNanos() == 100 * SECOND) {
                rangePublications.incrementAndGet();
            }
        }));

        runOnFx(() -> {
            pane.applyModeForTest(MapSelectionMode.A_B_RANGE);
            pane.pickForTest(100);
        });
        pumpFx();
        assertEquals(DriveSegmentSelection.Phase.WAITING_FOR_B, pane.segmentPhaseForTest());
        assertEquals(-1, pane.rangeIndicesForTest()[1]);
        assertEquals(0, rangePublications.get());

        runOnFx(() -> pane.pickForTest(500));
        pumpFx();

        assertEquals(100, pane.rangeIndicesForTest()[0]);
        assertEquals(500, pane.rangeIndicesForTest()[1]);
        assertEquals(100, pane.canvasRangeIndicesForTest()[0]);
        assertEquals(500, pane.canvasRangeIndicesForTest()[1]);
        assertEquals(DriveSegmentSelection.Phase.SEGMENT_SELECTED, pane.segmentPhaseForTest());
        TimeSelection range = pane.sharedSelection().analysisRange();
        assertEquals(100 * SECOND, range.startNanos());
        assertEquals(500 * SECOND, range.endNanos());
        assertTrue(range.durationNanos() > 0);
        assertEquals(1, rangePublications.get(), "range must be published once when B is committed");

        DriveSegmentAnalysisService.DriveSegmentAnalysisRequest request = pane.lastAnalysisRequestForTest();
        assertNotNull(request);
        assertEquals(100 * SECOND, request.startTimestampNanos());
        assertEquals(500 * SECOND, request.endTimestampNanos());
        assertEquals(100, request.indexA());
        assertEquals(500, request.indexB());

        awaitFx(() -> pane.segmentSummaryForTest().motion().range().durationNanos() > 0);
        assertEquals(400 * SECOND, pane.segmentSummaryForTest().motion().range().durationNanos());
        assertTrue(pane.segmentSummaryForTest().motion().range().distanceMeters() > 0);
        assertNotNull(pane.segmentSummaryForTest().motion().range().averageSpeedKph());
        assertNotNull(pane.segmentSummaryForTest().motion().range().maxSpeedKph());
        assertTrue(pane.segmentHeadlineForTest().contains("Duration"));
        assertTrue(pane.segmentHeadlineForTest().contains("6m"), pane.segmentHeadlineForTest());
        disposeOnFx(pane);
    }

    @Test
    void segmentDistanceUsesCanonicalRouteEvenWhenAsyncSummaryDoesNotMatch() throws Exception {
        FakeStore store = gpsStore(80);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(80), "file-a");

        runOnFx(() -> pane.renderSegmentSummaryForTest(DriveSegmentSummary.empty()));
        pumpFx();

        String body = pane.segmentBodyTextForTest();
        assertTrue(body.contains("Distance"), body);
        assertTrue(body.contains("km"), "geometry and analysis must share the canonical route distance:\n" + body);
        assertFalse(body.contains("Distance\n—") || body.matches("(?s).*Distance\\s+—.*"),
                "Distance must not be independently unavailable:\n" + body);
        disposeOnFx(pane);
    }

    @Test
    void clickingBBeforeAStillProducesAnOrderedRange() throws Exception {
        FakeStore store = gpsStore(300);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(300), "file-a");

        runOnFx(() -> {
            pane.applyModeForTest(MapSelectionMode.A_B_RANGE);
            pane.pickForTest(210);
            pane.pickForTest(60);
        });
        pumpFx();

        TimeSelection selection = pane.sharedSelection().analysisRange();
        assertEquals(60 * SECOND, selection.startNanos());
        assertEquals(210 * SECOND, selection.endNanos());
        assertEquals(60, pane.rangeIndicesForTest()[0]);
        assertEquals(210, pane.rangeIndicesForTest()[1]);
        DriveSegmentAnalysisService.DriveSegmentAnalysisRequest request = pane.lastAnalysisRequestForTest();
        assertNotNull(request);
        assertEquals(60 * SECOND, request.startTimestampNanos());
        assertEquals(210 * SECOND, request.endTimestampNanos());
        disposeOnFx(pane);
    }

    @Test
    void ordinarySelectedPointAfterAbLeavesActiveSegmentUnchanged() throws Exception {
        FakeStore store = gpsStore(400);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(400), "file-a");
        runOnFx(() -> {
            pane.applyModeForTest(MapSelectionMode.A_B_RANGE);
            pane.pickForTest(40);
            pane.pickForTest(200);
            pane.pickForTest(80);
        });
        pumpFx();

        assertEquals(80, pane.selectedIndexForTest());
        assertEquals(40, pane.rangeIndicesForTest()[0]);
        assertEquals(200, pane.rangeIndicesForTest()[1]);
        assertEquals(40, pane.canvasRangeIndicesForTest()[0]);
        assertEquals(200, pane.canvasRangeIndicesForTest()[1]);
        TimeSelection range = pane.sharedSelection().analysisRange();
        assertEquals(40 * SECOND, range.startNanos());
        assertEquals(200 * SECOND, range.endNanos());
        assertEquals(DriveSegmentSelection.Phase.SEGMENT_SELECTED, pane.segmentPhaseForTest());
        DriveSegmentAnalysisService.DriveSegmentAnalysisRequest request = pane.lastAnalysisRequestForTest();
        assertNotNull(request);
        assertEquals(40 * SECOND, request.startTimestampNanos());
        assertEquals(200 * SECOND, request.endTimestampNanos());
        disposeOnFx(pane);
    }

    @Test
    void waveformPointSyncDoesNotCollapseActiveSegment() throws Exception {
        FakeStore store = gpsStore(400);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(400), "file-a");
        runOnFx(() -> {
            pane.applyModeForTest(MapSelectionMode.A_B_RANGE);
            pane.pickForTest(40);
            pane.pickForTest(200);
        });
        pumpFx();

        runOnFx(() -> pane.sharedSelection().selectPoint(80 * SECOND, TimeSelection.Origin.WAVEFORM));
        pumpFx();

        assertEquals(80, pane.selectedIndexForTest());
        assertEquals(40, pane.rangeIndicesForTest()[0]);
        assertEquals(200, pane.rangeIndicesForTest()[1]);
        TimeSelection range = pane.sharedSelection().analysisRange();
        assertEquals(40 * SECOND, range.startNanos());
        assertEquals(200 * SECOND, range.endNanos());
        assertEquals(DriveSegmentSelection.Phase.SEGMENT_SELECTED, pane.segmentPhaseForTest());
        disposeOnFx(pane);
    }

    @Test
    void sameSampleForBStaysWaitingAndDoesNotCompleteZeroDuration() throws Exception {
        FakeStore store = gpsStore(200);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(200), "file-a");
        TimeSelection fullDrive = pane.sharedSelection().analysisRange();

        runOnFx(() -> {
            pane.applyModeForTest(MapSelectionMode.A_B_RANGE);
            pane.pickForTest(40);
            pane.pickForTest(40);
        });
        pumpFx();

        assertEquals(DriveSegmentSelection.Phase.WAITING_FOR_B, pane.segmentPhaseForTest());
        assertEquals(40, pane.rangeIndicesForTest()[0]);
        assertEquals(-1, pane.rangeIndicesForTest()[1]);
        assertEquals(fullDrive.startNanos(), pane.sharedSelection().analysisRange().startNanos());
        assertEquals(fullDrive.endNanos(), pane.sharedSelection().analysisRange().endNanos());
        assertTrue(pane.statusTextForTest().contains("Select a different end point"), pane.statusTextForTest());
        assertTrue(pane.segmentHeadlineForTest().contains("B  —"), pane.segmentHeadlineForTest());
        disposeOnFx(pane);
    }

    @Test
    void markersMatchCommittedActiveSegmentEndpoints() throws Exception {
        FakeStore store = gpsStore(300);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(300), "file-a");
        runOnFx(() -> {
            pane.applyModeForTest(MapSelectionMode.A_B_RANGE);
            pane.pickForTest(25);
            pane.pickForTest(175);
        });
        pumpFx();

        long[] committed = pane.committedTimestampsForTest();
        assertEquals(25 * SECOND, committed[0]);
        assertEquals(175 * SECOND, committed[1]);
        assertEquals(25, pane.rangeIndicesForTest()[0]);
        assertEquals(175, pane.rangeIndicesForTest()[1]);
        assertEquals(pane.rangeIndicesForTest()[0], pane.canvasRangeIndicesForTest()[0]);
        assertEquals(pane.rangeIndicesForTest()[1], pane.canvasRangeIndicesForTest()[1]);
        DriveSegmentAnalysisService.DriveSegmentAnalysisRequest request = pane.lastAnalysisRequestForTest();
        assertEquals(committed[0], request.startTimestampNanos());
        assertEquals(committed[1], request.endTimestampNanos());
        disposeOnFx(pane);
    }

    @Test
    void defaultMapRouteUsesFirstToLastGpsSample() throws Exception {
        FakeStore store = gpsStore(200);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(200), "file-a");
        pumpFx();

        assertEquals(0, pane.rangeIndicesForTest()[0]);
        assertEquals(199, pane.rangeIndicesForTest()[1]);
        TimeSelection range = pane.sharedSelection().analysisRange();
        assertEquals(TimeSelection.Kind.RANGE, range.kind());
        assertEquals(0L, range.startNanos());
        assertEquals(199 * SECOND, range.endNanos());
        assertEquals(TimeSelection.Origin.MAP_SEGMENT, range.origin());
        disposeOnFx(pane);
    }

    @Test
    void allRouteModeClearsTheMapDerivedFilter() throws Exception {
        FakeStore store = gpsStore(200);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(200), "file-a");

        runOnFx(() -> {
            pane.applyModeForTest(MapSelectionMode.A_B_RANGE);
            pane.pickForTest(10);
            pane.pickForTest(100);
            pane.applyModeForTest(MapSelectionMode.ALL_ROUTE);
        });
        pumpFx();

        assertEquals(TimeSelection.Kind.RANGE, pane.sharedSelection().analysisRange().kind());
        assertEquals(0L, pane.sharedSelection().analysisRange().startNanos());
        assertEquals(199 * SECOND, pane.sharedSelection().analysisRange().endNanos());
        assertEquals(0, pane.rangeIndicesForTest()[0]);
        assertEquals(199, pane.rangeIndicesForTest()[1]);
        disposeOnFx(pane);
    }

    @Test
    void waveformOriginSelectionMovesTheMapWithoutEchoing() throws Exception {
        FakeStore store = gpsStore(400);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(400), "file-a");
        AtomicInteger notifications = new AtomicInteger();
        runOnFx(() -> pane.sharedSelection().addListener(s -> notifications.incrementAndGet()));

        runOnFx(() -> pane.sharedSelection().selectPoint(250 * SECOND, TimeSelection.Origin.WAVEFORM));
        pumpFx();

        assertEquals(250, pane.selectedIndexForTest(), "map indicator must follow the waveform cursor");
        assertEquals(0, pane.rangeIndicesForTest()[0], "inspecting a waveform point must not destroy A→B");
        assertEquals(399, pane.rangeIndicesForTest()[1]);
        assertEquals(1, notifications.get(), "a synchronised map must not re-publish the same selection");
        disposeOnFx(pane);
    }

    @Test
    void waveformSelectionWithoutGpsKeepsTimeAndShowsUnavailable() throws Exception {
        FakeStore store = new FakeStore();
        store.put("VehicleSpeed", "km/h", List.of(new SignalSamplePoint(0, 10.0, null, null)));
        MapRoutePane pane = newPane();
        runOnFx(() -> pane.activate(request(store, List.of(entry("VehicleSpeed", "km/h", 1)), "no-gps")));
        awaitFx(pane::emptyStateVisibleForTest);

        runOnFx(() -> pane.sharedSelection().selectPoint(14_321_142_000_000L, TimeSelection.Origin.WAVEFORM));
        pumpFx();

        assertEquals(TimeSelection.Kind.POINT, pane.sharedSelection().selection().kind());
        assertEquals(14_321_142_000_000L, pane.sharedSelection().selection().startNanos());
        assertTrue(pane.statusTextForTest().contains("No GPS sample available at this time"),
                pane.statusTextForTest());
        disposeOnFx(pane);
    }

    @Test
    void autoSyncOffKeepsTheMapSelectionLocal() throws Exception {
        FakeStore store = gpsStore(200);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(200), "file-a");

        runOnFx(() -> {
            pane.setAutoSyncForTest(false);
            pane.applyModeForTest(MapSelectionMode.SINGLE_POINT);
            pane.pickForTest(80);
        });
        pumpFx();

        assertEquals(80, pane.selectedIndexForTest());
        assertEquals(TimeSelection.Kind.RANGE, pane.sharedSelection().analysisRange().kind());
        assertEquals(TimeSelection.Kind.RANGE, pane.sharedSelection().selection().kind());
        disposeOnFx(pane);
    }

    @Test
    void measurementWithoutGpsShowsTheEmptyState() throws Exception {
        FakeStore store = new FakeStore();
        store.put("VehicleSpeed", "km/h", List.of(new SignalSamplePoint(0, 10.0, null, null)));
        List<SignalCatalogEntry> catalog = List.of(entry("VehicleSpeed", "km/h", 1));

        MapRoutePane pane = newPane();
        runOnFx(() -> pane.activate(request(store, catalog, "no-gps")));
        awaitFx(pane::emptyStateVisibleForTest);

        assertEquals(0, pane.routeSizeForTest());
        assertTrue(pane.emptyStateVisibleForTest());
        AtomicReference<String> headline = new AtomicReference<>();
        runOnFx(() -> headline.set(pane.emptyHeadlineForTest()));
        assertTrue(headline.get().contains("GPS") || headline.get().contains("mapping")
                || headline.get().contains("No valid"), headline.get());
        disposeOnFx(pane);
    }

    @Test
    void gpsLatInfoCatalogAutoDetectsWithoutManualMapping() throws Exception {
        int samples = 40;
        SignalCatalogKey lat = dbcKey("GPS_LAT_INFO", "GPS_COORDINATE_22C", 556);
        SignalCatalogKey lon = dbcKey("GPS_LONG_INFO", "GPS_COORDINATE_22C", 556);
        SignalCatalogKey alt = dbcKey("GPS_ALTITUDE", "GPS_COORDINATE_22D", 557);
        SignalCatalogKey accuracy = dbcKey("GPS_ACCURACY_LAT_LONG", "GPS_STATUS", 100);
        FakeStore store = new FakeStore();
        List<SignalSamplePoint> latSamples = new ArrayList<>();
        List<SignalSamplePoint> lonSamples = new ArrayList<>();
        List<SignalSamplePoint> altSamples = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            latSamples.add(new SignalSamplePoint(i * SECOND, 13.04 + i * 0.0001, null, null));
            lonSamples.add(new SignalSamplePoint(i * SECOND, 77.60 + i * 0.0001, null, null));
            altSamples.add(new SignalSamplePoint(i * SECOND, 820.0 + i, null, null));
        }
        store.put(lat, "Decimal Degrees", latSamples);
        store.put(lon, "", lonSamples);
        store.put(alt, "metres", altSamples);
        List<SignalCatalogEntry> catalog = List.of(
                dbcEntry(accuracy, "m", samples, 0.4, 1.8),
                dbcEntry(lat, "Decimal Degrees", samples, 13.04, 13.21),
                dbcEntry(lon, "", samples, 77.59, 77.65),
                dbcEntry(alt, "metres", samples, 805.0, 860.0),
                dbcEntry(dbcKey("GPS_DOP", "GPS_STATUS", 101), "", samples, 0.8, 2.1),
                dbcEntry(dbcKey("GPS_FIX_TYPE", "GPS_STATUS", 102), "", samples, 0.0, 3.0),
                dbcEntry(dbcKey("GPS_ACCURACY_SPEED", "GPS_STATUS", 103), "m/s", samples, 0.1, 1.2),
                entry("EngineRPM", "rpm", samples));

        MapRoutePane pane = newPane();
        runOnFx(() -> pane.activate(request(store, catalog, "bengaluru.mf4")));
        awaitFx(() -> pane.routeSizeForTest() == samples);

        assertEquals("GPS_LAT_INFO", pane.mappingLatitudeForTest());
        assertEquals("GPS_LONG_INFO", pane.mappingLongitudeForTest());
        assertEquals("GPS_ALTITUDE", pane.mappingAltitudeForTest());
        assertEquals("Location detected", pane.mappingStatusForTest());
        assertFalse(pane.emptyStateVisibleForTest());
        assertFalse(pane.emptyHeadlineForTest().contains("GPS setup required"));
        disposeOnFx(pane);
    }

    @Test
    void manualPosSignalsCanBuildARouteWhenNamesAreUnrelated() throws Exception {
        FakeStore store = new FakeStore();
        int samples = 40;
        List<SignalSamplePoint> lat = new ArrayList<>();
        List<SignalSamplePoint> lon = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            lat.add(new SignalSamplePoint(i * SECOND, 18.5 + i * 0.0001, null, null));
            lon.add(new SignalSamplePoint(i * SECOND, 73.7 + i * 0.0001, null, null));
        }
        store.put("Pos01", "", lat);
        store.put("Pos02", "", lon);
        List<SignalCatalogEntry> catalog = List.of(entry("Pos01", "", samples), entry("Pos02", "", samples));
        String fingerprint = GpsSemanticResolver.schemaFingerprint(catalog);
        MapRoutePane pane = newPane();
        runOnFx(() -> pane.activate(request(store, catalog, "odd.mf4")));
        awaitFx(pane::emptyStateVisibleForTest);
        assertEquals(0, pane.routeSizeForTest());

        // Same path Configuration uses: persist a mapping, then push it into the pane.
        runOnFx(() -> pane.setProjectMappings(List.of(
                new GpsSignalMapping("project-1", "odd.mf4", fingerprint, GpsSignalRole.LATITUDE,
                        key("Pos01"), true, java.time.Instant.now()),
                new GpsSignalMapping("project-1", "odd.mf4", fingerprint, GpsSignalRole.LONGITUDE,
                        key("Pos02"), true, java.time.Instant.now()))));
        awaitFx(() -> pane.routeSizeForTest() == samples);
        assertEquals(samples, pane.routeSizeForTest());
        AtomicBoolean empty = new AtomicBoolean(true);
        runOnFx(() -> empty.set(pane.emptyStateVisibleForTest()));
        assertFalse(empty.get());
        disposeOnFx(pane);
    }

    @Test
    void persistedUserMappingRestoresTheRouteWithoutAutoNames() throws Exception {
        FakeStore store = new FakeStore();
        int samples = 30;
        List<SignalSamplePoint> lat = new ArrayList<>();
        List<SignalSamplePoint> lon = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            lat.add(new SignalSamplePoint(i * SECOND, 51.0 + i * 0.0001, null, null));
            lon.add(new SignalSamplePoint(i * SECOND, -0.1 - i * 0.0001, null, null));
        }
        store.put("Pos01", "", lat);
        store.put("Pos02", "", lon);
        List<SignalCatalogEntry> catalog = List.of(entry("Pos01", "", samples), entry("Pos02", "", samples));
        String fingerprint = GpsSemanticResolver.schemaFingerprint(catalog);
        MapRoutePane pane = newPane();
        runOnFx(() -> {
            pane.setProjectMappings(List.of(
                    new GpsSignalMapping("project-1", "odd.mf4", fingerprint, GpsSignalRole.LATITUDE,
                            key("Pos01"), true, java.time.Instant.now()),
                    new GpsSignalMapping("project-1", "odd.mf4", fingerprint, GpsSignalRole.LONGITUDE,
                            key("Pos02"), true, java.time.Instant.now())));
            pane.activate(request(store, catalog, "odd.mf4"));
        });
        awaitFx(() -> pane.routeSizeForTest() == samples);
        assertEquals(samples, pane.routeSizeForTest());
        disposeOnFx(pane);
    }

    @Test
    void fileChangeDropsRouteAndSelectionState() throws Exception {
        FakeStore store = gpsStore(200);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(200), "file-a");
        runOnFx(() -> {
            pane.applyModeForTest(MapSelectionMode.SINGLE_POINT);
            pane.pickForTest(50);
        });
        pumpFx();
        assertEquals(50, pane.selectedIndexForTest());

        runOnFx(pane::resetForFileChange);
        pumpFx();

        assertEquals(0, pane.routeSizeForTest(), "old route must not survive a file change");
        assertEquals(-1, pane.selectedIndexForTest());
        assertTrue(pane.emptyStateVisibleForTest());
        disposeOnFx(pane);
    }

    @Test
    void persistedStateRestoresAgainstARebuiltRoute() throws Exception {
        FakeStore store = gpsStore(300);
        MapRoutePane first = paneWithRoute(store, gpsCatalog(300), "file-a");
        runOnFx(() -> {
            first.applyModeForTest(MapSelectionMode.A_B_RANGE);
            first.pickForTest(25);
            first.pickForTest(175);
        });
        pumpFx();
        AtomicReference<MapRouteState> saved = new AtomicReference<>();
        runOnFx(() -> saved.set(first.captureState()));
        disposeOnFx(first);

        assertNotNull(saved.get());
        assertEquals(25 * SECOND, saved.get().aTimestampNanos());

        MapRoutePane reopened = paneWithRoute(gpsStore(300), gpsCatalog(300), "file-a");
        runOnFx(() -> reopened.restoreState(saved.get()));
        pumpFx();

        assertEquals(MapSelectionMode.A_B_RANGE, reopened.modeForTest());
        assertEquals(25, reopened.rangeIndicesForTest()[0]);
        assertEquals(175, reopened.rangeIndicesForTest()[1]);
        disposeOnFx(reopened);
    }

    @Test
    void restoreRequestedBeforeTheRouteIsReadyIsReplayedOncePrepared() throws Exception {
        MapRoutePane pane = new MapRoutePane();
        MapRouteState saved = new MapRouteState(
                MapSelectionMode.SINGLE_POINT, 90 * SECOND, null, null, true);

        // Restore lands first: route preparation is asynchronous and has not produced coordinates yet.
        runOnFx(() -> pane.restoreState(saved));
        pumpFx();
        assertEquals(-1, pane.selectedIndexForTest());

        runOnFx(() -> pane.activate(request(gpsStore(300), gpsCatalog(300), "file-a")));
        awaitFx(() -> pane.selectedIndexForTest() >= 0);

        assertEquals(MapSelectionMode.SINGLE_POINT, pane.modeForTest());
        assertEquals(90, pane.selectedIndexForTest());
        disposeOnFx(pane);
    }

    @Test
    void aFileChangeDropsAPendingRestoreSoItCannotLandOnTheNewMeasurement() throws Exception {
        MapRoutePane pane = new MapRoutePane();
        runOnFx(() -> {
            pane.restoreState(new MapRouteState(MapSelectionMode.SINGLE_POINT, 90 * SECOND, null, null, true));
            pane.resetForFileChange();
        });
        pumpFx();

        runOnFx(() -> pane.activate(request(gpsStore(300), gpsCatalog(300), "file-b")));
        awaitFx(() -> pane.routeSizeForTest() > 0);
        pumpFx();

        assertEquals(-1, pane.selectedIndexForTest(), "stale restore must not select on the new file");
        disposeOnFx(pane);
    }

    @Test
    void repeatedOpenAndCloseCyclesDoNotAccumulateListeners() throws Exception {
        FakeStore store = gpsStore(100);
        SharedTimeSelectionModel shared = new SharedTimeSelectionModel();
        for (int i = 0; i < 15; i++) {
            MapRoutePane pane = new MapRoutePane(shared);
            runOnFx(() -> pane.activate(request(store, gpsCatalog(100), "file-a")));
            awaitFx(() -> pane.routeSizeForTest() > 0);
            runOnFx(pane::deactivate);
            disposeOnFx(pane);
        }
        assertEquals(0, shared.listenerCount(), "each disposed pane must unsubscribe from the shared selection");
    }

    @Test
    void paneStaysWithinItsBoundsWhenTheWorkspaceIsResized() throws Exception {
        FakeStore store = gpsStore(400);
        MapRoutePane pane = newPane();
        AtomicReference<StackPane> root = new AtomicReference<>();
        runOnFx(() -> {
            StackPane container = new StackPane(pane);
            new Scene(container, 1280, 720);
            root.set(container);
            pane.activate(request(store, gpsCatalog(400), "file-a"));
        });
        awaitFx(() -> pane.routeSizeForTest() > 0);

        for (double width : new double[]{1280, 1024, 860}) {
            AtomicReference<double[]> measured = new AtomicReference<>();
            runOnFx(() -> {
                root.get().resize(width, 700);
                root.get().applyCss();
                root.get().layout();
                SplitPane center = pane.workspaceSplitForTest();
                Node mapColumn = center.getItems().get(0);
                Node rail = center.getItems().get(1);
                measured.set(new double[]{
                        pane.getWidth(),
                        mapColumn.getLayoutBounds().getWidth(),
                        rail.getBoundsInParent().getMaxX()});
            });
            double[] values = measured.get();
            assertTrue(values[1] > 0, "map column must keep usable width at " + width + "px");
            assertTrue(values[2] <= values[0] + 1,
                    "detail rail must not overflow the pane at " + width + "px");
        }
        disposeOnFx(pane);
    }

    @Test
    void mappingEditorOpensFromMapRouteAndSavesWithoutRereadingTheMeasurement() throws Exception {
        FakeStore store = gpsStore(40);
        List<SignalCatalogEntry> catalog = gpsCatalog(40);
        MapRoutePane pane = paneWithRoute(store, catalog, "drive.mf4");
        AtomicReference<List<GpsSignalMapping>> persisted = new AtomicReference<>(List.of());

        runOnFx(() -> pane.setMappingPersistence(new MapRoutePane.RouteMappingPersistence() {
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
                persisted.set(List.copyOf(mappings));
                return true;
            }
        }));
        runOnFx(pane::openMappingEditorForTest);
        assertTrue(pane.mappingEditorVisibleForTest());
        assertTrue(pane.mappingEditorForTest().requiredRolesRenderedForTest());

        runOnFx(() -> pane.mappingEditorForTest().pickRoleForTest(GpsSignalRole.LATITUDE, "Latitude"));
        runOnFx(() -> pane.mappingEditorForTest().pickRoleForTest(GpsSignalRole.LONGITUDE, "Longitude"));
        assertFalse(pane.mappingEditorForTest().saveDisabledForTest());
        int readsBeforeSave = store.reads.get();
        runOnFx(() -> pane.mappingEditorForTest().saveForTest());
        awaitFx(() -> !pane.mappingEditorVisibleForTest());
        assertEquals(2, persisted.get().size());
        awaitFx(() -> pane.routeSizeForTest() > 0);
        assertFalse(pane.emptyStateVisibleForTest());
        pumpFx();
        int readsAfterSave = store.reads.get();
        assertTrue(readsAfterSave >= readsBeforeSave);
        pumpFx();
        assertEquals(readsAfterSave, store.reads.get(), "a successful save must refresh the route once");

        runOnFx(pane::openMappingEditorForTest);
        runOnFx(() -> pane.mappingEditorForTest().typeRoleForTest(GpsSignalRole.ALTITUDE, "Altitude"));
        runOnFx(() -> pane.mappingEditorForTest().cancelForTest());
        assertFalse(pane.mappingEditorVisibleForTest());
        assertTrue(persisted.get().stream().noneMatch(m -> m.role() == GpsSignalRole.ALTITUDE),
                "Cancel must not persist the typed altitude");
        disposeOnFx(pane);
    }

    @Test
    void failedMappingSaveKeepsTheEditorOpen() throws Exception {
        FakeStore store = gpsStore(40);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(40), "drive.mf4");
        AtomicInteger saves = new AtomicInteger();
        runOnFx(() -> pane.setMappingPersistence(new MapRoutePane.RouteMappingPersistence() {
            @Override
            public List<GpsSignalMapping> loadProjectMappings() {
                return List.of();
            }

            @Override
            public List<GpsSignalMapping> loadDefaults() {
                return List.of();
            }

            @Override
            public boolean saveProjectMappings(String sourceId, String fingerprint, List<GpsSignalMapping> mappings) {
                saves.incrementAndGet();
                return false;
            }
        }));
        runOnFx(pane::openMappingEditorForTest);
        runOnFx(() -> pane.mappingEditorForTest().pickRoleForTest(GpsSignalRole.LATITUDE, "Latitude"));
        runOnFx(() -> pane.mappingEditorForTest().pickRoleForTest(GpsSignalRole.LONGITUDE, "Longitude"));
        runOnFx(() -> pane.mappingEditorForTest().saveForTest());
        pumpFx();
        assertTrue(pane.mappingEditorVisibleForTest(), "failed save must not dismiss the editor");
        assertEquals(1, saves.get());
        disposeOnFx(pane);
    }

    @Test
    void duplicateSaveIsIgnoredAndSuccessfulSaveClosesOnce() throws Exception {
        FakeStore store = gpsStore(40);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(40), "drive.mf4");
        AtomicInteger saves = new AtomicInteger();
        AtomicReference<List<GpsSignalMapping>> persisted = new AtomicReference<>(List.of());
        runOnFx(() -> pane.setMappingPersistence(new MapRoutePane.RouteMappingPersistence() {
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
                saves.incrementAndGet();
                pane.mappingEditorForTest().saveForTest();
                persisted.set(List.copyOf(mappings));
                return true;
            }
        }));
        runOnFx(pane::openMappingEditorForTest);
        runOnFx(() -> pane.mappingEditorForTest().pickRoleForTest(GpsSignalRole.LATITUDE, "Latitude"));
        runOnFx(() -> pane.mappingEditorForTest().pickRoleForTest(GpsSignalRole.LONGITUDE, "Longitude"));
        runOnFx(() -> pane.mappingEditorForTest().saveForTest());
        awaitFx(() -> !pane.mappingEditorVisibleForTest());
        assertEquals(1, saves.get(), "re-entrant Save must not persist twice");
        assertEquals(2, persisted.get().size());
        disposeOnFx(pane);
    }

    @Test
    void mappingEditorMountsOnSceneRootNotTheWorkspaceSplit() throws Exception {
        FakeStore store = gpsStore(40);
        MapRoutePane pane = newPane();
        AtomicReference<StackPane> rootRef = new AtomicReference<>();
        runOnFx(() -> {
            Label signals = new Label("Signals");
            signals.setMinWidth(200);
            signals.setPrefWidth(220);
            Label inspector = new Label("Inspector");
            inspector.setMinWidth(240);
            inspector.setPrefWidth(280);
            SplitPane graphs = new SplitPane(signals, pane, inspector);
            StackPane root = new StackPane(graphs);
            new Scene(root, 1366, 768);
            root.applyCss();
            root.layout();
            rootRef.set(root);
            pane.activate(request(store, gpsCatalog(40), "drive.mf4"));
        });
        awaitFx(() -> pane.routeSizeForTest() > 0);
        runOnFx(pane::openMappingEditorForTest);
        runOnFx(() -> {
            rootRef.get().applyCss();
            rootRef.get().layout();
            GpsSignalMappingModal modal = pane.mappingEditorForTest();
            assertTrue(modal.isVisible());
            assertEquals(rootRef.get(), modal.getParent(), "GPS mapping must overlay the application root");
            assertFalse(pane.workspaceSplitForTest().getItems().contains(modal));
            BorderPane dialog = modal.dialogForTest();
            Bounds dialogInScene = dialog.localToScene(dialog.getBoundsInLocal());
            double sceneW = rootRef.get().getScene().getWidth();
            double sceneH = rootRef.get().getScene().getHeight();
            assertTrue(dialogInScene.getMinX() >= -1, "dialog must not clip on the left");
            assertTrue(dialogInScene.getMinY() >= -1, "dialog must not clip on the top");
            assertTrue(dialogInScene.getMaxX() <= sceneW + 1.0, "dialog must stay inside the scene");
            assertTrue(dialogInScene.getMaxY() <= sceneH + 1.0, "footer must stay inside the scene");
            assertTrue(modal.footerOutsideScrollForTest());
            assertTrue(modal.bodyVerticalScrollOnlyForTest());
        });
        disposeOnFx(pane);
    }

    @Test
    void mappingEditorOpenCloseDoesNotRecreateLeafletAndResyncsSize() throws Exception {
        FakeStore store = gpsStore(40);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(40), "drive.mf4");
        LeafletBasemapView view = pane.basemapViewForTest();
        int inits = view.leafletInitCountForTest();
        int resizeGen = view.resizeGenerationForTest();
        runOnFx(pane::openMappingEditorForTest);
        assertTrue(pane.mappingEditorVisibleForTest());
        runOnFx(pane::closeMappingEditorForTest);
        pumpFx();
        pumpFx();
        assertFalse(pane.mappingEditorVisibleForTest());
        assertEquals(inits, view.leafletInitCountForTest(), "editor chrome must not rebuild Leaflet");
        assertTrue(view.resizeGenerationForTest() > resizeGen,
                "closing the editor must request a layout-aware Leaflet resync");
        disposeOnFx(pane);
    }

    @Test
    void repeatedMappingEditorCyclesKeepOneLeafletInstance() throws Exception {
        FakeStore store = gpsStore(40);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(40), "drive.mf4");
        LeafletBasemapView view = pane.basemapViewForTest();
        int inits = view.leafletInitCountForTest();
        for (int i = 0; i < 6; i++) {
            runOnFx(pane::openMappingEditorForTest);
            runOnFx(pane::closeMappingEditorForTest);
            pumpFx();
        }
        assertEquals(inits, view.leafletInitCountForTest(), "editor cycles must not recreate Leaflet");
        assertTrue(view == pane.basemapViewForTest());
        disposeOnFx(pane);
    }

    @Test
    void focusModeExitAndTabRevisitResynchronizeLeafletWithoutRecreatingIt() throws Exception {
        FakeStore store = gpsStore(40);
        MapRoutePane pane = paneWithRoute(store, gpsCatalog(40), "drive.mf4");
        LeafletBasemapView view = pane.basemapViewForTest();
        int inits = view.leafletInitCountForTest();
        int resizeGen = view.resizeGenerationForTest();
        runOnFx(() -> pane.notifyLayoutChanged("fullscreen-exit"));
        pumpFx();
        runOnFx(() -> pane.notifyLayoutChanged("tab-reactivate"));
        pumpFx();
        assertEquals(inits, view.leafletInitCountForTest());
        assertTrue(view.resizeGenerationForTest() > resizeGen);
        disposeOnFx(pane);
    }

    @Test
    void webViewAndRouteCanvasDimensionsConvergeAfterEditorClose() throws Exception {
        FakeStore store = gpsStore(40);
        MapRoutePane pane = newPane();
        AtomicReference<StackPane> root = new AtomicReference<>();
        runOnFx(() -> {
            StackPane container = new StackPane(pane);
            new Scene(container, 1280, 720);
            root.set(container);
            pane.activate(request(store, gpsCatalog(40), "drive.mf4"));
        });
        awaitFx(() -> pane.routeSizeForTest() > 0);
        runOnFx(() -> {
            root.get().resize(1280, 720);
            root.get().applyCss();
            root.get().layout();
        });
        runOnFx(pane::openMappingEditorForTest);
        runOnFx(pane::closeMappingEditorForTest);
        pumpFx();
        runOnFx(() -> {
            root.get().applyCss();
            root.get().layout();
        });
        pumpFx();
        AtomicReference<double[]> sizes = new AtomicReference<>();
        runOnFx(() -> sizes.set(pane.mapSurfaceSizesForTest()));
        double[] measured = sizes.get();
        assertTrue(measured[0] > 100 && measured[1] > 100, "route canvas must have a usable layout");
        assertEquals(measured[0], measured[4], 2.0, "WebView width must match RouteCanvas");
        assertEquals(measured[1], measured[5], 2.0, "WebView height must match RouteCanvas");
        disposeOnFx(pane);
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private static MapRoutePane newPane() throws Exception {
        AtomicReference<MapRoutePane> ref = new AtomicReference<>();
        runOnFx(() -> ref.set(new MapRoutePane()));
        return ref.get();
    }

    private static MapRoutePane paneWithRoute(FakeStore store, List<SignalCatalogEntry> catalog, String fileId)
            throws Exception {
        MapRoutePane pane = newPane();
        runOnFx(() -> pane.activate(request(store, catalog, fileId)));
        awaitFx(() -> pane.routeSizeForTest() > 0);
        return pane;
    }

    private static void disposeOnFx(MapRoutePane pane) throws Exception {
        runOnFx(pane::dispose);
    }

    /** Route preparation is asynchronous by design; poll rather than sleep a fixed interval. */
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

    /** Flushes queued FX work so results posted from the map worker have been applied. */
    private static void pumpFx() throws Exception {
        for (int i = 0; i < 4; i++) {
            runOnFx(() -> { });
            Thread.sleep(15);
        }
        runOnFx(() -> { });
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
