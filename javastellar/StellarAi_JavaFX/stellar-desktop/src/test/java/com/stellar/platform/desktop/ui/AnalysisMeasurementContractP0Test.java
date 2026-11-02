package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.AnalysisMeasurementContext;
import com.stellar.platform.desktop.presentation.AnalysisMeasurementResolver;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.MeasurementCatalogScope;
import com.stellar.platform.desktop.presentation.SignalBrowserViewMode;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.ui.graph.map.MapRouteRequest;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.catalog.CompositeSignalCatalogStore;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.map.GpsMappingScope;
import com.stellar.platform.processing.map.GpsSignalMapping;
import com.stellar.platform.processing.map.GpsSignalRole;
import com.stellar.platform.processing.map.MapRouteCacheKey;
import com.stellar.platform.processing.map.MapRouteService;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Foundation contract: one analysis measurement identity and one unprojected engineering catalog.
 */
class AnalysisMeasurementContractP0Test {

    @TempDir Path tempDir;
    private DesktopAppService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void engineeringCatalogIsExactMeasurementAndIgnoresPresentation() throws Exception {
        service = openFourFileProject();
        service.selectMeasurement("src-A");
        List<SignalCatalogEntry> a = service.engineeringCatalog();
        assertFalse(a.isEmpty());
        assertTrue(a.stream().allMatch(e -> "src-A".equals(e.key().sourceFile())));
        assertTrue(a.stream().anyMatch(e -> e.signalName().equals("GPS_LAT_INFO")));
        assertTrue(a.stream().noneMatch(e -> e.signalName().equals("UniqueB")));

        service.selectMeasurement("src-B");
        List<SignalCatalogEntry> b = service.engineeringCatalog();
        assertTrue(b.stream().allMatch(e -> "src-B".equals(e.key().sourceFile())));
        assertTrue(b.stream().anyMatch(e -> e.signalName().equals("GPS_LONG_INFO")));
        assertTrue(b.stream().noneMatch(e -> e.signalName().equals("UniqueA")));
        assertNotEquals(
                latValue(a, "GPS_LAT_INFO"),
                latValue(b, "GPS_LAT_INFO"));

        int before = service.engineeringCatalog().size();
        service.signalsModel().setBrowserViewMode(SignalBrowserViewMode.UNIQUE_SIGNAL_NAMES);
        service.signalsModel().applyBrowserFilterNow();
        assertEquals(before, service.engineeringCatalog().size());
        assertEquals(before, service.engineeringCatalog(service.activeAnalysisMeasurement()).size());

        assertTrue(service.engineeringCatalog(AnalysisMeasurementContext.of("missing", "nope.mf4")).isEmpty());
        assertTrue(GpsMappingScope.catalogFor("missing", service.signalsModel().mapRouteCatalog()).isEmpty());

        service.workspace().selectMeasurement(null);
        assertFalse(service.activeAnalysisMeasurement().isPresent(),
                "multiple catalog identities without a selection fail closed");
        assertTrue(service.engineeringCatalog().isEmpty());
    }

    @Test
    void gpsScopeCannotFallBackToWholeProjectOrBorrowMapping() throws Exception {
        service = openFourFileProject();
        List<SignalCatalogEntry> catalog = service.signalsModel().mapRouteCatalog();
        String fpA = GpsMappingScope.fingerprintFor("src-A", catalog);
        List<GpsSignalMapping> persisted = List.of(
                mapping("src-A", fpA, GpsSignalRole.LATITUDE, "GPS_LAT_INFO"),
                mapping("src-A", fpA, GpsSignalRole.LONGITUDE, "GPS_LONG_INFO"));

        assertEquals("src-C", GpsMappingScope.resolveRouteSource("src-C", catalog, persisted));
        assertTrue(GpsMappingScope.mappingsForMeasurement("src-C", "MF4_C.mf4", persisted).isEmpty());
        assertEquals("", GpsMappingScope.resolveRouteSource("", catalog, persisted));
        assertTrue(GpsMappingScope.catalogFor("src-Z", catalog).isEmpty());
        assertTrue(GpsMappingScope.catalogFor("src-B", catalog).stream()
                .noneMatch(e -> "src-A".equals(e.key().sourceFile())));
    }

    @Test
    void mapSampleReadsUseCanonicalSourceId() throws Exception {
        service = openFourFileProject();
        service.selectMeasurement("src-B");
        AnalysisMeasurementContext ctx = service.activeAnalysisMeasurement();
        assertEquals("src-B", ctx.sourceId());
        assertEquals("MF4_B.mf4", ctx.fileName());

        List<String> scopes = new ArrayList<>();
        RecordingQuery query = new RecordingQuery(scopes);
        SignalCatalogKey lat = service.engineeringCatalog().stream()
                .filter(e -> e.signalName().equals("GPS_LAT_INFO"))
                .findFirst().orElseThrow().key();
        MapRouteRequest request = new MapRouteRequest(
                query,
                service.engineeringCatalog(),
                service.currentProjectId(),
                ctx.sourceId(),
                ctx.fileName(),
                1L,
                7,
                0,
                10);
        assertTrue(request.signature().contains(ctx.sourceId()));
        assertTrue(request.signature().contains("|7|"));
        MapRouteRequest movedWindow = new MapRouteRequest(
                query, request.catalog(), request.projectId(), request.fileId(), request.fileName(),
                request.processingRevision(), 7, 37_000_000_000L, 38_000_000_000L);
        assertEquals(request.signature(), movedWindow.signature());
        MapRouteRequest otherRev = new MapRouteRequest(
                query, request.catalog(), request.projectId(), request.fileId(), request.fileName(),
                request.processingRevision(), 8, request.dataStartNanos(), request.dataEndNanos());
        assertNotEquals(request.signature(), otherRev.signature());

        Map<GpsSignalRole, SignalCatalogKey> roles = Map.of(
                GpsSignalRole.LATITUDE, lat,
                GpsSignalRole.LONGITUDE, lat);
        new MapRouteService(query).prepare(
                new MapRouteCacheKey(request.projectId(), request.sourceId(), 1L, roles), 0, 10);
        assertTrue(scopes.contains("src-B"));
        assertFalse(scopes.contains("MF4_B.mf4"));
    }

    @Test
    void analyzeResolvesSpecificOrSingleMeasurementAndBlocksAmbiguousAll() throws Exception {
        service = openFourFileProject();
        List<SignalCatalogEntry> catalog = service.signalsModel().catalog().entries();
        SignalCatalogKey a = key(catalog, "src-A", "VehicleSpeed");
        SignalCatalogKey b = key(catalog, "src-B", "VehicleSpeed");
        WorkspaceFile fileA = file("src-A", "MF4_A.mf4");
        WorkspaceFile fileB = file("src-B", "MF4_B.mf4");

        AnalysisMeasurementResolver.Resolution specific = AnalysisMeasurementResolver.resolve(
                List.of(a),
                catalog,
                Optional.of(fileB),
                List.of(fileA, fileB),
                new MeasurementCatalogScope.Ref("src-A", "MF4_A.mf4", false, 4));
        assertTrue(specific.canAnalyze());
        assertEquals("src-A", specific.measurementId());

        AnalysisMeasurementResolver.Resolution fromAll = AnalysisMeasurementResolver.resolve(
                List.of(a), catalog, Optional.of(fileB), List.of(fileA, fileB),
                MeasurementCatalogScope.Ref.all(4));
        assertTrue(fromAll.canAnalyze());
        assertEquals("src-A", fromAll.measurementId());

        AnalysisMeasurementResolver.Resolution mixed = AnalysisMeasurementResolver.resolve(
                List.of(a, b), catalog, Optional.of(fileA), List.of(fileA, fileB),
                MeasurementCatalogScope.Ref.all(4));
        assertTrue(mixed.ambiguous());
        assertFalse(mixed.canAnalyze());
        assertTrue(mixed.explanation().contains("more than one measurement"));
    }

    @Test
    void graphLeftListAndExplorerAllUseEngineeringCatalog() throws Exception {
        service = openFourFileProject();
        service.selectMeasurement("src-B");
        service.signalsModel().addToWorkspace(List.of(key(service.engineeringCatalog(), "src-B", "VehicleSpeed")));
        GraphController graph = new GraphController();
        graph.bindMembershipForTest(service);
        assertEquals("ALL", graph.explorerFilterForTest());
        assertFalse(graph.graphCatalogForTest().isEmpty());
        assertTrue(graph.graphCatalogForTest().stream().allMatch(e -> "src-B".equals(e.key().sourceFile())));
        assertTrue(graph.graphCatalogForTest().size() >= 2,
                "left list is the engineering catalog, not only plotted series");

        service.selectMeasurement("src-C");
        graph.applyActiveMeasurementCatalogForTest();
        assertEquals("ALL", graph.explorerFilterForTest());
        assertTrue(graph.graphCatalogForTest().stream().allMatch(e -> "src-C".equals(e.key().sourceFile())));
        assertEquals("src-C", graph.mapRouteSourceFileForTest());

        service.selectMeasurement("src-A");
        graph.applyActiveMeasurementCatalogForTest();
        assertTrue(graph.graphCatalogForTest().stream().allMatch(e -> "src-A".equals(e.key().sourceFile())));
        long generationA = graph.generationForTest();
        int session = graph.sessionGenerationForTest();
        service.selectMeasurement("src-B");
        graph.applyActiveMeasurementCatalogForTest();
        assertFalse(graph.acceptViewportResult(generationA, session));
        assertFalse(service.compactProgress().running());
    }

    @Test
    void reopenRestoresCanonicalMeasurementWithoutReprocessing() throws Exception {
        service = openFourFileProject();
        service.selectMeasurement("src-B");
        String projectId = service.currentProjectId();
        assertEquals("src-B", service.activeAnalysisMeasurement().sourceId());
        service.saveProject();
        service.closeProject();
        DesktopAppService.ProjectOpenResult opened = service.openSavedProject(projectId);
        assertTrue(opened.opened(), String.join(",", opened.warnings()));
        assertEquals("src-B", service.activeAnalysisMeasurement().sourceId());
        assertEquals("MF4_B.mf4", service.activeAnalysisMeasurement().fileName());
        assertTrue(service.engineeringCatalog().stream().allMatch(e -> "src-B".equals(e.key().sourceFile())));
        assertFalse(service.compactProgress().running());
        assertFalse(service.isPlanExecutionActive());
    }

    private DesktopAppService openFourFileProject() throws Exception {
        System.setProperty("stellarai.home", tempDir.resolve("home").toAbsolutePath().toString());
        service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()),
                new SqliteProjectMetadataStore(tempDir.resolve("meta.db")));
        service.newProject("Contract Four");
        for (String name : List.of("MF4_A.mf4", "MF4_B.mf4", "MF4_C.mf4", "MF4_D.mf4")) {
            Files.writeString(tempDir.resolve(name), "stub");
        }
        service.workspace().addFile(WorkspaceFile.restore(
                "src-A", tempDir.resolve("MF4_A.mf4"), FileKind.MEASUREMENT_MF4, 1));
        service.workspace().addFile(WorkspaceFile.restore(
                "src-B", tempDir.resolve("MF4_B.mf4"), FileKind.MEASUREMENT_MF4, 1));
        service.workspace().addFile(WorkspaceFile.restore(
                "src-C", tempDir.resolve("MF4_C.mf4"), FileKind.MEASUREMENT_MF4, 1));
        service.workspace().addFile(WorkspaceFile.restore(
                "src-D", tempDir.resolve("MF4_D.mf4"), FileKind.MEASUREMENT_MF4, 1));
        service.selectMeasurement("src-A");
        service.signalsModel().replaceCatalog(fourFileGpsStore());
        return service;
    }

    private static CompositeSignalCatalogStore fourFileGpsStore() {
        return CompositeSignalCatalogStore.fromMembers(List.of(
                gpsMember("src-A", "MF4_A.mf4", 1_000L, 12.90, 77.50, "UniqueA"),
                gpsMember("src-B", "MF4_B.mf4", 10_000L, 13.04, 77.59, "UniqueB"),
                gpsMember("src-C", "MF4_C.mf4", 100_000L, 18.52, 73.85, "UniqueC"),
                gpsMember("src-D", "MF4_D.mf4", 1_000_000L, 19.07, 72.87, "UniqueD")
        ), "4 measurements", "none");
    }

    private static CompositeSignalCatalogStore.Member gpsMember(
            String identity, String display, long t0, double lat, double lon, String unique
    ) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(display, "", 50);
        store.accept(List.of(
                can("VehicleSpeed", t0, 40.0 + identity.hashCode() % 7),
                can("VehicleSpeed", t0 * 2, 50.0),
                nativeSig("GPS_LAT_INFO", t0, lat),
                nativeSig("GPS_LONG_INFO", t0, lon),
                can(unique, t0, 1.0)
        ));
        return new CompositeSignalCatalogStore.Member(store, identity, "_none_", display, "none");
    }

    private static CanonicalSignal can(String name, long ts, double value) {
        return new CanonicalSignal(
                name, "Chassis", ts, 0x200, 1, value, 1L, "km/h", null, SourceReference.ofLine(1));
    }

    private static CanonicalSignal nativeSig(String name, long ts, double value) {
        return new CanonicalSignal(
                name, InMemorySignalCatalogStore.MF4_MEASUREMENT_MESSAGE, ts, 0L, 0, value, 1L,
                "deg", null, SourceReference.ofLine(1));
    }

    private static SignalCatalogKey key(List<SignalCatalogEntry> catalog, String source, String name) {
        return catalog.stream()
                .filter(e -> name.equals(e.signalName()) && source.equals(e.key().sourceFile()))
                .findFirst().orElseThrow().key();
    }

    private static double latValue(List<SignalCatalogEntry> catalog, String name) {
        return catalog.stream()
                .filter(e -> name.equals(e.signalName()))
                .mapToDouble(e -> e.minPhysical() == null ? 0 : e.minPhysical())
                .findFirst().orElse(0);
    }

    private WorkspaceFile file(String id, String name) throws Exception {
        return WorkspaceFile.restore(id, tempDir.resolve(name), FileKind.MEASUREMENT_MF4, 1);
    }

    private static GpsSignalMapping mapping(String source, String fingerprint, GpsSignalRole role, String name) {
        SignalCatalogKey key = new SignalCatalogKey(
                name, com.stellar.platform.domain.catalog.SignalSourceType.MF4_NATIVE, "GPS", 0, 0, source, "");
        return new GpsSignalMapping("p1", source, fingerprint, role, key, true, Instant.now());
    }

    private static final class RecordingQuery implements GraphSeriesQuery {
        private final List<String> scopes;

        private RecordingQuery(List<String> scopes) {
            this.scopes = scopes;
        }

        @Override
        public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
            return List.of();
        }

        @Override
        public List<SignalSamplePoint> queryRange(
                SignalCatalogKey key, long startNanos, long endNanos, String sourceScope) {
            scopes.add(sourceScope);
            return List.of();
        }

        @Override
        public String unit(SignalCatalogKey key) {
            return "";
        }

        @Override
        public long sampleCount(SignalCatalogKey key) {
            return 0;
        }
    }
}
