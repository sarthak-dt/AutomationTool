package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.AnalysisMeasurementContext;
import com.stellar.platform.desktop.presentation.AnalysisScope;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.catalog.CompositeSignalCatalogStore;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisScopeP0Test {

    @TempDir Path tempDir;
    private DesktopAppService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void scopeIsTypeSafeAndSelectorListsAllFirst() throws Exception {
        service = openFourFileProject();
        List<AnalysisScope> choices = service.analysisScopeChoices();
        assertEquals("All Measurements (4)", choices.getFirst().comboLabel());
        assertTrue(choices.getFirst().isAll());
        assertEquals(4, choices.stream().filter(AnalysisScope::isSingle).count());
        assertThrows(IllegalArgumentException.class,
                () -> AnalysisScope.single(AnalysisMeasurementContext.none()));
        assertFalse(service.isProjectWideAnalysis());
        service.selectAnalysisScope(choices.getFirst());
        assertTrue(service.isProjectWideAnalysis());
        assertEquals("ALL|4", service.analysisScopeToken());
        assertTrue(service.analysisScope().isAll());
    }

    @Test
    void singleAAndBStayIsolatedAndSwitchCoordinated() throws Exception {
        service = openFourFileProject();
        service.selectMeasurement("src-A");
        List<SignalCatalogEntry> a = service.engineeringCatalog();
        assertTrue(a.stream().allMatch(e -> "src-A".equals(e.key().sourceFile())));
        assertTrue(a.stream().anyMatch(e -> e.signalName().equals("UniqueA")));
        assertTrue(a.stream().noneMatch(e -> e.signalName().equals("UniqueB")));

        service.selectMeasurement("src-B");
        List<SignalCatalogEntry> b = service.engineeringCatalog();
        assertTrue(b.stream().allMatch(e -> "src-B".equals(e.key().sourceFile())));
        assertTrue(b.stream().anyMatch(e -> e.signalName().equals("UniqueB")));
        assertTrue(b.stream().noneMatch(e -> e.signalName().equals("UniqueA")));
        assertFalse(service.isProjectWideAnalysis());
        assertEquals("src-B", service.activeAnalysisMeasurement().sourceId());
    }

    @Test
    void allExposesFourPhysicalCatalogsAndSameNameRpmStaysTwoIdentities() throws Exception {
        service = openFourFileProject();
        service.selectAnalysisScope(AnalysisScope.allMeasurements(4));
        List<SignalCatalogEntry> all = service.engineeringCatalog();
        assertEquals(4, all.stream().map(e -> e.key().sourceFile()).distinct().count());
        List<SignalCatalogEntry> rpms = all.stream()
                .filter(e -> e.signalName().equals("VehicleSpeed"))
                .toList();
        assertEquals(4, rpms.size());
        assertEquals(4, rpms.stream().map(e -> e.key().sourceFile()).distinct().count());

        SignalCatalogKey a = key(all, "src-A", "VehicleSpeed");
        SignalCatalogKey b = key(all, "src-B", "VehicleSpeed");
        assertNotEquals(a, b);
        var store = service.signalsModel().catalogStore();
        assertEquals(40.0 + "src-A".hashCode() % 7,
                store.querySamples(a, 0, 10).getFirst().physicalValue(), 1e-9);
        assertEquals(40.0 + "src-B".hashCode() % 7,
                store.querySamples(b, 0, 10).getFirst().physicalValue(), 1e-9);
        assertNotEquals(
                store.querySamples(a, 0, 10).getFirst().physicalValue(),
                store.querySamples(b, 0, 10).getFirst().physicalValue());
    }

    @Test
    void transitionsKeepCatalogAlignedWithScope() throws Exception {
        service = openFourFileProject();
        GraphController graph = new GraphController();
        service.selectMeasurement("src-A");
        graph.bindMembershipForTest(service);
        assertTrue(graph.graphCatalogForTest().stream().allMatch(e -> "src-A".equals(e.key().sourceFile())));

        service.selectMeasurement("src-B");
        graph.applyActiveMeasurementCatalogForTest();
        assertTrue(graph.graphCatalogForTest().stream().allMatch(e -> "src-B".equals(e.key().sourceFile())));
        long generationB = graph.generationForTest();

        service.selectAnalysisScope(AnalysisScope.allMeasurements(4));
        graph.applyActiveMeasurementCatalogForTest();
        assertEquals(4, graph.graphCatalogForTest().stream().map(e -> e.key().sourceFile()).distinct().count());
        assertNotEquals(generationB, graph.generationForTest());
        assertFalse(graph.acceptViewportResult(generationB, graph.sessionGenerationForTest()));

        service.selectMeasurement("src-A");
        graph.applyActiveMeasurementCatalogForTest();
        assertTrue(graph.graphCatalogForTest().stream().allMatch(e -> "src-A".equals(e.key().sourceFile())));

        service.selectAnalysisScope(AnalysisScope.allMeasurements(4));
        graph.applyActiveMeasurementCatalogForTest();
        assertTrue(service.isProjectWideAnalysis());

        service.selectMeasurement("src-B");
        graph.applyActiveMeasurementCatalogForTest();
        assertTrue(graph.graphCatalogForTest().stream().allMatch(e -> "src-B".equals(e.key().sourceFile())));
        assertFalse(service.isProjectWideAnalysis());
    }

    @Test
    void allThenSingleCThenAllAgain() throws Exception {
        service = openFourFileProject();
        service.selectAnalysisScope(AnalysisScope.allMeasurements(4));
        assertEquals(4, service.engineeringCatalog().stream().map(e -> e.key().sourceFile()).distinct().count());
        service.selectMeasurement("src-C");
        assertTrue(service.engineeringCatalog().stream().allMatch(e -> "src-C".equals(e.key().sourceFile())));
        service.selectAnalysisScope(AnalysisScope.allMeasurements(4));
        assertTrue(service.isProjectWideAnalysis());
        assertEquals(4, service.engineeringCatalog().stream().map(e -> e.key().sourceFile()).distinct().count());
    }

    @Test
    void saveResumeAllThenSingle() throws Exception {
        service = openFourFileProject();
        service.selectAnalysisScope(AnalysisScope.allMeasurements(4));
        String projectId = service.currentProjectId();
        service.persistGraphInvestigation();
        assertTrue(service.saveProject().saved());
        service.closeProject();
        assertFalse(service.isProjectWideAnalysis());
        assertTrue(service.openSavedProject(projectId).opened());
        assertTrue(service.isProjectWideAnalysis());

        service.selectMeasurement("src-B");
        service.persistGraphInvestigation();
        assertTrue(service.saveProject().saved());
        service.closeProject();
        assertTrue(service.openSavedProject(projectId).opened());
        assertFalse(service.isProjectWideAnalysis());
        assertEquals("src-B", service.activeAnalysisMeasurement().sourceId());
    }

    @Test
    void projectIsolationDoesNotLeakForeignMeasurements() throws Exception {
        service = openFourFileProject();
        service.selectAnalysisScope(AnalysisScope.allMeasurements(4));
        assertTrue(service.engineeringCatalog().stream().noneMatch(e -> "foreign".equals(e.key().sourceFile())));
        assertTrue(service.engineeringCatalog().stream()
                .map(e -> e.key().sourceFile())
                .allMatch(id -> id.startsWith("src-")));
    }

    private DesktopAppService openFourFileProject() throws Exception {
        System.setProperty("stellarai.home", tempDir.resolve("home").toAbsolutePath().toString());
        service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()),
                new SqliteProjectMetadataStore(tempDir.resolve("meta.db")));
        service.newProject("Scope Four");
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
}
