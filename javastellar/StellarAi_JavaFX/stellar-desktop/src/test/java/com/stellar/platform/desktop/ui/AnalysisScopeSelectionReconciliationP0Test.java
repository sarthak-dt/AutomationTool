package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.AnalysisScope;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Merged scope reconciliation uses one logical PROJECT key in All Measurements and the
 * selected measurement's physical series in Single Measurement. All Measurements does not
 * expose one row per physical source.
 */
class AnalysisScopeSelectionReconciliationP0Test {

    private static final String UNIQUE = "UniqueOnly";

    @TempDir Path tempDir;
    private DesktopAppService service;
    private GraphController graph;
    private ProjectMergedSignalStore retainedStore;
    private Stage stage;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        JavaFxTestSupport.runOnFx("dispose graph", () -> {
            if (graph != null) {
                graph.dispose();
                graph = null;
            }
            if (stage != null) {
                stage.close();
                stage = null;
            }
        });
        if (service != null) {
            service.shutdown();
            service = null;
        }
        JavaFxTestSupport.drainFxPulse();
    }

    @Test
    void allToSingleSrcAResolvesPhysicalSeries() throws Exception {
        openMergedProject(2, 42);
        GraphController g = bindGraph();
        useAll(g);
        assertLogicalCatalog(g, 42);
        SignalCatalogKey logical = logical("S01PID");
        g.toggleMembershipForTest(logical, true);

        useSingle(g, "src-A");
        SignalCatalogKey physical = physical("src-A", "S01PID");
        assertEquals(List.of(physical), g.selectedChipKeysForTest());
        assertTrue(g.isSelectedForTest(physical));
        assertEquals(1, g.checkedExplorerRowCountForTest());
        assertFalse(g.selectedChipKeysForTest().stream().anyMatch(k -> "src-B".equals(k.sourceFile())));
        assertFalse(g.selectedChipKeysForTest().stream().anyMatch(this::isLogical));
    }

    @Test
    void allToSingleSrcBResolvesPhysicalSeries() throws Exception {
        openMergedProject(2, 42);
        GraphController g = bindGraph();
        useAll(g);
        SignalCatalogKey logical = logical("S");
        g.toggleMembershipForTest(logical, true);

        useSingle(g, "src-B");
        SignalCatalogKey physical = physical("src-B", "S");
        assertEquals(List.of(physical), g.selectedChipKeysForTest());
        assertTrue(g.isSelectedForTest(physical));
        assertEquals(1, g.checkedExplorerRowCountForTest());
        assertTrue(g.graphCatalogForTest().stream().allMatch(e -> "src-B".equals(e.key().sourceFile())));
    }

    @Test
    void singleSrcAReturnsToLogicalProjectKey() throws Exception {
        openMergedProject(2, 42);
        GraphController g = bindGraph();
        useSingle(g, "src-A");
        SignalCatalogKey physical = physical("src-A", "S01PID");
        g.toggleMembershipForTest(physical, true);

        useAll(g);
        assertLogicalCatalog(g, 42);
        assertEquals(84, retainedStore.physicalCatalogAll().size());
        SignalCatalogKey logical = logical("S01PID");
        assertEquals(ProjectMergedSignalStore.logicalKey(physical, "veh.dbc", "u"), logical);
        assertEquals(List.of(logical), g.selectedChipKeysForTest());
        assertTrue(g.isSelectedForTest(logical));
        assertEquals(1, g.selectedSignalCountForTest());
        assertEquals(1, g.membershipCountForKeyForTest(logical));
        assertFalse(g.isSelectedForTest(physical));
    }

    @Test
    void singleSrcBReturnsToLogicalProjectKey() throws Exception {
        openMergedProject(2, 42);
        GraphController g = bindGraph();
        useSingle(g, "src-B");
        SignalCatalogKey physical = physical("src-B", "EngineLike");
        g.toggleMembershipForTest(physical, true);

        useAll(g);
        SignalCatalogKey logical = logical("EngineLike");
        assertEquals(List.of(logical), g.selectedChipKeysForTest());
        assertEquals(ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE, logical.sourceFile());
        assertFalse(g.selectedMembershipForTest().stream().anyMatch(k -> "src-B".equals(k.sourceFile())));
    }

    @Test
    void singleSrcAThenSrcBResolvesTargetPhysicalSeries() throws Exception {
        openMergedProject(2, 42);
        GraphController g = bindGraph();
        useSingle(g, "src-A");
        g.toggleMembershipForTest(physical("src-A", "S01PID"), true);

        useSingle(g, "src-B");
        SignalCatalogKey onB = physical("src-B", "S01PID");
        assertEquals(List.of(onB), g.selectedChipKeysForTest());
        assertTrue(g.isSelectedForTest(onB));
        assertEquals(1, g.checkedExplorerRowCountForTest());
        assertTrue(g.graphCatalogForTest().stream().noneMatch(e -> "src-A".equals(e.key().sourceFile())));
    }

    @Test
    void unavailableLogicalSignalIsNotSubstitutedAndReturnsOnAll() throws Exception {
        openMergedProject(2, 42, true, false);
        GraphController g = bindGraph();
        useAll(g);
        SignalCatalogKey logical = logical(UNIQUE);
        assertEquals(1, g.graphCatalogForTest().stream().filter(e -> UNIQUE.equals(e.signalName())).count());
        g.toggleMembershipForTest(logical, true);

        useSingle(g, "src-B");
        assertTrue(g.graphCatalogForTest().stream().noneMatch(e -> UNIQUE.equals(e.signalName())));
        assertEquals(0, g.checkedExplorerRowCountForTest());
        assertTrue(g.selectedChipKeysForTest().isEmpty());
        assertTrue(g.selectedMembershipForTest().contains(logical));
        assertEquals(1, g.selectedSignalCountForTest());

        useAll(g);
        assertEquals(List.of(logical), g.selectedChipKeysForTest());
        assertEquals(1, g.checkedExplorerRowCountForTest());
    }

    @Test
    void multipleLogicalSignalsStayDistinctAcrossScopes() throws Exception {
        openMergedProject(2, 42);
        GraphController g = bindGraph();
        useAll(g);
        SignalCatalogKey speed = logical("S");
        SignalCatalogKey pid = logical("S01PID");
        g.toggleMembershipForTest(speed, true);
        g.toggleMembershipForTest(pid, true);
        assertDistinctSelection(g, 2);

        useSingle(g, "src-A");
        assertDistinctSelection(g, 2);
        assertTrue(g.selectedChipKeysForTest().stream().allMatch(k -> "src-A".equals(k.sourceFile())));
        assertTrue(g.isSelectedForTest(physical("src-A", "S")));
        assertTrue(g.isSelectedForTest(physical("src-A", "S01PID")));

        useSingle(g, "src-B");
        assertDistinctSelection(g, 2);
        assertTrue(g.isSelectedForTest(physical("src-B", "S")));
        assertTrue(g.isSelectedForTest(physical("src-B", "S01PID")));

        useAll(g);
        assertEquals(Set.of(speed, pid), Set.copyOf(g.selectedChipKeysForTest()));
        assertDistinctSelection(g, 2);
        assertTrue(g.selectedChipKeysForTest().stream().allMatch(this::isLogical));
    }

    @Test
    void repeatedScopeSwitchesDoNotDuplicateSelection() throws Exception {
        openMergedProject(2, 42);
        GraphController g = bindGraph();
        useSingle(g, "src-A");
        SignalCatalogKey physical = physical("src-A", "S01PID");
        g.toggleMembershipForTest(physical, true);
        g.toggleMembershipForTest(physical, true);

        useAll(g);
        SignalCatalogKey logical = logical("S01PID");
        g.toggleMembershipForTest(logical, true);
        useSingle(g, "src-B");
        useSingle(g, "src-A");
        useAll(g);
        useSingle(g, "src-B");
        useAll(g);

        assertEquals(1, g.selectedSignalCountForTest());
        assertEquals(1, g.membershipCountForKeyForTest(logical));
        assertEquals(1, g.selectedChipKeysForTest().size());
        assertEquals(1, service.signalsModel().workspaceKeys().stream().filter(logical::equals).count());
        assertEquals(1, new LinkedHashSet<>(g.selectedMembershipForTest()).size());
    }

    @Test
    void reloadRestoresLogicalSelectionOnce() throws Exception {
        openMergedProject(2, 42);
        GraphController g = bindGraph();
        useSingle(g, "src-A");
        g.toggleMembershipForTest(physical("src-A", "S01PID"), true);
        useAll(g);
        SignalCatalogKey logical = logical("S01PID");
        assertTrue(g.isSelectedForTest(logical));

        String projectId = service.currentProjectId();
        service.persistGraphInvestigation();
        assertTrue(service.saveProject().saved());
        service.closeProject();
        assertTrue(service.openSavedProject(projectId).opened());
        service.signalsModel().replaceCatalog(retainedStore);
        service.restoreGraphInvestigation();

        GraphController resumed = new GraphController();
        resumed.bindMembershipForTest(service);
        assertTrue(service.isProjectWideAnalysis());
        assertLogicalCatalog(resumed, 42);
        assertTrue(resumed.isSelectedForTest(logical));
        assertEquals(1, resumed.selectedSignalCountForTest());
        assertEquals(1, resumed.membershipCountForKeyForTest(logical));
        resumed.dispose();
    }

    @Test
    void reconciliationIgnoresMemberOrder() throws Exception {
        openMergedProject(2, 42, false, true);
        GraphController g = bindGraph();
        useSingle(g, "src-B");
        SignalCatalogKey fromB = physical("src-B", "S01PID");
        g.toggleMembershipForTest(fromB, true);
        useAll(g);
        SignalCatalogKey logicalFromB = logical("S01PID");
        g.toggleMembershipForTest(logicalFromB, false);

        useSingle(g, "src-A");
        SignalCatalogKey fromA = physical("src-A", "S01PID");
        g.toggleMembershipForTest(fromA, true);
        useAll(g);
        SignalCatalogKey logicalFromA = logical("S01PID");

        assertEquals(logicalFromB, logicalFromA);
        assertEquals(ProjectMergedSignalStore.logicalKey(fromA, "veh.dbc", "u"), logicalFromA);
        assertEquals(ProjectMergedSignalStore.logicalKey(fromB, "veh.dbc", "u"), logicalFromB);
        assertEquals(List.of(logicalFromA), g.selectedChipKeysForTest());
    }

    @Test
    void lateSingleGenerationDoesNotReapplyAfterAll() throws Exception {
        openMergedProject(2, 42);
        GraphController g = bindGraph();
        useSingle(g, "src-A");
        long singleGeneration = g.generationForTest();
        SignalCatalogKey physical = physical("src-A", "S01PID");
        g.toggleMembershipForTest(physical, true);

        useAll(g);
        assertTrue(g.generationForTest() > singleGeneration);
        assertFalse(g.acceptViewportResult(singleGeneration, g.sessionGenerationForTest()));
        SignalCatalogKey logical = logical("S01PID");
        assertEquals(1, g.selectedSignalCountForTest());
        assertTrue(g.isSelectedForTest(logical));
        assertFalse(g.isSelectedForTest(physical));
    }

    @Test
    @Timeout(60)
    void explorerCellReuseKeepsCheckboxBoundToLogicalMembership() throws Exception {
        openMergedProject(2, 42);
        useSingle(null, "src-A");
        SignalCatalogKey physical = physical("src-A", "S01PID");
        JavaFxTestSupport.runOnFx("load graph fxml", () -> {
            FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
            Parent root = loader.load();
            graph = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, 1366, 768));
            stage.show();
            graph.bindMembershipForTest(service);
            graph.toggleMembershipForTest(physical, true);
            layoutExplorer();
        });
        JavaFxTestSupport.drainFxPulse();

        JavaFxTestSupport.runOnFx("switch ALL and scroll", () -> {
            service.selectAnalysisScope(AnalysisScope.allMeasurements(2));
            graph.applyActiveMeasurementCatalogForTest();
            layoutExplorer();
            ListView<SignalCatalogEntry> list = graph.signalListForTest();
            list.scrollTo(0);
            list.layout();
            list.scrollTo(list.getItems().size() - 1);
            list.layout();
            list.scrollTo(0);
            list.layout();
        });
        JavaFxTestSupport.drainFxPulse();

        SignalCatalogKey logical = logical("S01PID");
        assertTrue(graph.isSelectedForTest(logical));
        assertEquals(1, graph.selectedSignalCountForTest());
        JavaFxTestSupport.runOnFx("assert checkbox after reuse", () -> {
            layoutExplorer();
            assertTrue(graph.explorerCheckboxSelectedForTest(logical));
            graph.fireExplorerCheckboxForTest(logical);
        });
        JavaFxTestSupport.drainFxPulse();
        assertFalse(graph.isSelectedForTest(logical));
        assertEquals(0, graph.selectedSignalCountForTest());
    }

    private void assertLogicalCatalog(GraphController g, int logicalCount) {
        List<SignalCatalogEntry> catalog = g.graphCatalogForTest();
        assertEquals(logicalCount, catalog.size());
        assertEquals(logicalCount, catalog.stream().map(SignalCatalogEntry::signalName).distinct().count());
        assertTrue(catalog.stream().allMatch(e -> isLogical(e.key())));
        assertFalse(catalog.stream().anyMatch(e -> "src-A".equals(e.key().sourceFile())
                || "src-B".equals(e.key().sourceFile())));
    }

    private void assertDistinctSelection(GraphController g, int count) {
        assertEquals(count, g.selectedSignalCountForTest());
        assertEquals(count, g.checkedExplorerRowCountForTest());
        assertEquals(count, g.selectedChipKeysForTest().size());
        assertEquals(count, Set.copyOf(g.selectedChipKeysForTest()).size());
        assertEquals(count, new LinkedHashSet<>(g.selectedMembershipForTest()).size());
    }

    private void useAll(GraphController g) {
        service.selectAnalysisScope(AnalysisScope.allMeasurements(service.workspace().measurementFiles().size()));
        if (g != null) {
            g.applyActiveMeasurementCatalogForTest();
        }
    }

    private void useSingle(GraphController g, String sourceId) {
        service.selectMeasurement(sourceId);
        if (g != null) {
            g.applyActiveMeasurementCatalogForTest();
        }
    }

    private boolean isLogical(SignalCatalogKey key) {
        return key != null && ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE.equals(key.sourceFile());
    }

    private SignalCatalogKey logical(String name) {
        return service.engineeringCatalog().stream()
                .filter(e -> name.equals(e.signalName()) && isLogical(e.key()))
                .findFirst()
                .orElseThrow()
                .key();
    }

    private SignalCatalogKey physical(String source, String name) {
        return service.engineeringCatalog().stream()
                .filter(e -> name.equals(e.signalName()) && source.equals(e.key().sourceFile()))
                .findFirst()
                .orElseThrow()
                .key();
    }

    private GraphController bindGraph() {
        GraphController g = new GraphController();
        g.bindMembershipForTest(service);
        return g;
    }

    private void layoutExplorer() {
        ListView<SignalCatalogEntry> list = graph.signalListForTest();
        if (list != null) {
            list.applyCss();
            list.layout();
            if (list.getScene() != null && list.getScene().getRoot() != null) {
                list.getScene().getRoot().applyCss();
                list.getScene().getRoot().layout();
            }
        }
    }

    private void openMergedProject(int measurements, int signalsPerFile) throws Exception {
        openMergedProject(measurements, signalsPerFile, false, false);
    }

    private void openMergedProject(
            int measurements,
            int signalsPerFile,
            boolean uniqueOnFirst,
            boolean reverseMembers
    ) throws Exception {
        System.setProperty("stellarai.home", tempDir.resolve("home").toAbsolutePath().toString());
        service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()),
                new SqliteProjectMetadataStore(tempDir.resolve("scope-select.db")));
        service.newProject("Scope Selection " + measurements);
        List<ProjectMergedSignalStore.Member> members = new ArrayList<>();
        String[] ids = {"src-A", "src-B", "src-C", "src-D"};
        for (int i = 0; i < measurements; i++) {
            String id = ids[i];
            String file = "MF4_" + (char) ('A' + i) + ".mf4";
            Files.writeString(tempDir.resolve(file), "stub");
            service.workspace().addFile(WorkspaceFile.restore(
                    id, tempDir.resolve(file), FileKind.MEASUREMENT_MF4, 1));
            members.add(mergedMember(id, file, 1_000L * (long) Math.pow(10, i), signalsPerFile, uniqueOnFirst && i == 0));
        }
        if (reverseMembers) {
            Collections.reverse(members);
        }
        retainedStore = ProjectMergedSignalStore.fromMembers(members, measurements + " measurements", "veh.dbc");
        service.selectMeasurement("src-A");
        service.signalsModel().replaceCatalog(retainedStore);
    }

    private static ProjectMergedSignalStore.Member mergedMember(
            String identity, String display, long t0, int signalCount, boolean includeUnique
    ) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(display, "veh.dbc", 200);
        List<CanonicalSignal> rows = new ArrayList<>();
        for (int i = 0; i < signalCount; i++) {
            String name = i == 0 ? "S" : i == 1 ? "S01PID" : i == 2 ? "EngineLike" : "Sig" + String.format("%02d", i);
            rows.add(new CanonicalSignal(
                    name, "OBD2", t0 + i, 0x100 + i, i + 1, i + 1.0, 1L, "u", null,
                    SourceReference.ofLine(i + 1)));
        }
        if (includeUnique) {
            rows.add(new CanonicalSignal(
                    UNIQUE, "OBD2", t0 + signalCount, 0x7FF, 9, 1.0, 1L, "u", null,
                    SourceReference.ofLine(signalCount + 1)));
        }
        store.accept(rows);
        return new ProjectMergedSignalStore.Member(store, identity, "veh.dbc", display, "veh.dbc");
    }
}
