package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.MeasurementCatalogScope;
import com.stellar.platform.desktop.presentation.MeasurementCatalogScopeTest;
import com.stellar.platform.desktop.presentation.SignalBrowserViewMode;
import com.stellar.platform.desktop.presentation.SignalInspectorPresentation;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.catalog.CompositeSignalCatalogStore;
import com.stellar.platform.processing.map.GpsMappingScope;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-MF4 measurement UX: one active recording at a time, no reprocess, no sample merge.
 */
class MultiMeasurementUxTest {

    @TempDir
    Path tempDir;

    @Test
    void switchingMeasurementDoesNotStartProcessingAndKeepsSourceIdentity() throws Exception {
        DesktopAppService service = openFourFileProject();
        assertEquals(4, service.workspace().measurementSourceCount());
        service.selectMeasurement("src-A");
        assertFalse(service.compactProgress().running());
        var statusBefore = service.workspace().processingStatus();

        service.selectMeasurement("src-B");
        assertEquals("src-B", service.activeMeasurementSourceId());
        assertEquals("MF4_B.mf4", service.activeMeasurementFileName());
        assertFalse(service.compactProgress().running());
        assertEquals(statusBefore, service.workspace().processingStatus());

        service.selectMeasurement("src-A");
        GraphController graph = new GraphController();
        service.signalsModel().addToWorkspace(List.of(speedKey(service, "src-A")));
        graph.bindMembershipForTest(service);
        assertTrue(graph.graphCatalogForTest().stream()
                .allMatch(e -> "src-A".equals(e.key().sourceFile())));
        long generationA = graph.generationForTest();

        service.selectMeasurement("src-B");
        graph.applyActiveMeasurementCatalogForTest();
        assertTrue(graph.graphCatalogForTest().stream()
                .allMatch(e -> "src-B".equals(e.key().sourceFile())));
        assertTrue(graph.graphCatalogForTest().stream()
                .noneMatch(e -> "src-A".equals(e.key().sourceFile())));
        assertEquals("src-B", graph.mapRouteSourceFileForTest());
        assertTrue(graph.generationForTest() > generationA);
        assertTrue(graph.visibleKeysForTest().stream().allMatch(k -> "src-B".equals(k.sourceFile())));
        assertFalse(service.compactProgress().running());
        service.shutdown();
    }

    @Test
    void mapRouteAndSpeedCatalogFollowTheActiveMeasurement() throws Exception {
        DesktopAppService service = openFourFileProject();
        service.selectMeasurement("src-A");
        List<SignalCatalogEntry> catalog = service.signalsModel().mapRouteCatalog();
        List<SignalCatalogEntry> scopedA = GpsMappingScope.catalogFor(
                "src-A", MeasurementCatalogScope.filter(catalog, "src-A", "MF4_A.mf4"));
        List<SignalCatalogEntry> scopedB = GpsMappingScope.catalogFor(
                "src-B", MeasurementCatalogScope.filter(catalog, "src-B", "MF4_B.mf4"));
        assertTrue(scopedA.stream().allMatch(e -> "src-A".equals(e.key().sourceFile())));
        assertTrue(scopedB.stream().allMatch(e -> "src-B".equals(e.key().sourceFile())));
        assertTrue(scopedA.stream().anyMatch(e -> e.signalName().equals("Latitude")));
        assertTrue(scopedB.stream().anyMatch(e -> e.signalName().equals("Latitude")));
        assertTrue(scopedA.stream().anyMatch(e -> e.signalName().equals("VehicleSpeed")));
        assertTrue(scopedB.stream().anyMatch(e -> e.signalName().equals("VehicleSpeed")));
        assertTrue(scopedA.stream().noneMatch(e -> e.signalName().equals("UniqueB")));
        assertTrue(scopedB.stream().noneMatch(e -> e.signalName().equals("UniqueA")));
        service.shutdown();
    }

    @Test
    void hideDuplicatesDoesNotChangeMeasurementIdentity() throws Exception {
        DesktopAppService service = openFourFileProject();
        var model = service.signalsModel();
        List<SignalCatalogKey> before = model.catalog().entries().stream()
                .filter(e -> e.signalName().equals("VehicleSpeed"))
                .map(SignalCatalogEntry::key)
                .toList();
        model.setBrowserViewMode(SignalBrowserViewMode.UNIQUE_SIGNAL_NAMES);
        List<SignalCatalogEntry> shown = model.applyBrowserFilterNow();
        assertEquals(4, shown.stream().filter(e -> e.signalName().equals("VehicleSpeed")).count());
        List<SignalCatalogKey> after = shown.stream()
                .filter(e -> e.signalName().equals("VehicleSpeed"))
                .map(SignalCatalogEntry::key)
                .toList();
        assertEquals(Set.copyOf(before.stream().map(SignalCatalogKey::sourceFile).toList()).size(), 4);
        assertTrue(after.stream().map(SignalCatalogKey::sourceFile).toList().containsAll(List.of("src-A", "src-B", "src-C", "src-D")));
        service.shutdown();
    }

    @Test
    void inspectorCoverageUsesCatalogMetadataNotWaveforms() throws Exception {
        DesktopAppService service = openFourFileProject();
        SignalCatalogEntry speedA = service.signalsModel().catalog().entries().stream()
                .filter(e -> e.signalName().equals("VehicleSpeed") && "src-A".equals(e.key().sourceFile()))
                .findFirst().orElseThrow();
        List<SignalInspectorPresentation.Section> sections =
                SignalInspectorPresentation.sections(speedA, service.signalsModel().catalog().entries());
        SignalInspectorPresentation.Section coverage = sections.stream()
                .filter(s -> s.title().equals("Measurement Coverage"))
                .findFirst().orElseThrow();
        assertTrue(coverage.rows().stream().anyMatch(r -> r.label().equals("Present in")
                && r.value().contains("4 / 4 measurements")));
        assertTrue(coverage.rows().stream().anyMatch(r -> r.label().equals("MF4_A.mf4")));
        assertTrue(coverage.rows().stream().anyMatch(r -> r.label().equals("MF4_D.mf4")));
        service.shutdown();
    }

    @Test
    void staleAsyncGenerationFromACannotOverwriteB() throws Exception {
        DesktopAppService service = openFourFileProject();
        GraphController graph = new GraphController();
        graph.bindMembershipForTest(service);
        long generationA = graph.generationForTest();
        int session = graph.sessionGenerationForTest();
        service.selectMeasurement("src-B");
        graph.applyActiveMeasurementCatalogForTest();
        assertFalse(graph.acceptViewportResult(generationA, session));
        service.shutdown();
    }

    @Test
    void queryPageRemainsBounded() throws Exception {
        DesktopAppService service = openFourFileProject();
        SignalCatalogKey key = speedKey(service, "src-A");
        var page = service.signalsModel().catalogStore().querySamples(key, 0, 5);
        assertTrue(page.size() <= 5);
        service.shutdown();
    }

    private DesktopAppService openFourFileProject() throws Exception {
        System.setProperty("stellarai.home", tempDir.resolve("home").toString());
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()),
                new SqliteProjectMetadataStore(tempDir.resolve("meta.db")));
        service.newProject("Four MF4");
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
        CompositeSignalCatalogStore store = MeasurementCatalogScopeTest.fourFileStore();
        service.signalsModel().replaceCatalog(store);
        return service;
    }

    private static SignalCatalogKey speedKey(DesktopAppService service, String source) {
        return service.signalsModel().catalog().entries().stream()
                .filter(e -> e.signalName().equals("VehicleSpeed") && source.equals(e.key().sourceFile()))
                .findFirst().orElseThrow().key();
    }
}
