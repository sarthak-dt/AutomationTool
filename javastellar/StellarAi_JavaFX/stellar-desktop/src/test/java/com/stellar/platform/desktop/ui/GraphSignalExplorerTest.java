package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Graph Signal Explorer: complete catalog, checkbox membership, focus vs selection.
 */
class GraphSignalExplorerTest {

    @TempDir Path temp;
    private DesktopAppService service;
    private GraphController graph;

    @AfterEach
    void tearDown() {
        if (graph != null) {
            graph.dispose();
        }
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void completeCatalogShowsUnselectedSignals() {
        bind(dbc("EngineRPM", "OBD2", "rpm", 1), dbc("VehicleSpeed", "OBD2", "km/h", 2));
        service.signalsModel().addToWorkspace(key("EngineRPM", SignalSourceType.DBC_CAN, "OBD2", 1));
        graph.applyMembershipFromWorkspaceForTest();
        List<String> names = graph.explorerRowsForTest().stream().map(SignalCatalogEntry::signalName).toList();
        assertTrue(names.contains("EngineRPM"));
        assertTrue(names.contains("VehicleSpeed"), "unselected catalog signals remain visible");
        assertEquals(2, graph.explorerRowsForTest().size());
    }

    @Test
    void secondAddCommandDoesNotDuplicateMembership() {
        bind(dbc("EngineRPM", "OBD2", "rpm", 1), dbc("VehicleSpeed", "OBD2", "km/h", 2));
        SignalCatalogKey rpm = key("EngineRPM", SignalSourceType.DBC_CAN, "OBD2", 1);
        graph.resetMembershipCommandCountForTest();
        graph.toggleMembershipForTest(rpm, true);
        graph.toggleMembershipForTest(rpm, true);
        assertEquals(2, graph.membershipCommandCountForTest());
        assertEquals(List.of(rpm), graph.selectedMembershipForTest());
    }

    @Test
    void checkboxAddsAndRemovesAnalysisMembershipWithoutDeletingCatalog() {
        bind(dbc("EngineRPM", "OBD2", "rpm", 1), dbc("VehicleSpeed", "OBD2", "km/h", 2));
        SignalCatalogKey rpm = key("EngineRPM", SignalSourceType.DBC_CAN, "OBD2", 1);
        graph.toggleMembershipForTest(rpm, true);
        assertEquals(List.of(rpm), service.signalsModel().workspaceKeys());
        graph.toggleMembershipForTest(rpm, true);
        assertEquals(1, service.signalsModel().workspaceKeys().size(), "already present is not duplicated");
        graph.toggleMembershipForTest(rpm, false);
        assertTrue(service.signalsModel().workspaceKeys().isEmpty());
        assertEquals(2, service.signalsModel().catalog().entries().size());
        assertTrue(graph.explorerRowsForTest().stream().anyMatch(e -> e.key().equals(rpm)));
    }

    @Test
    void singleClickFocusesWithoutTogglingMembership() {
        bind(dbc("EngineRPM", "OBD2", "rpm", 1), nativeSig("Latitude", "deg", 3));
        SignalCatalogKey lat = key("Latitude", SignalSourceType.MF4_NATIVE, InMemorySignalCatalogStore.MF4_MEASUREMENT_MESSAGE, 3);
        graph.singleClickExplorerForTest(lat);
        assertEquals(lat, graph.focusedExplorerKeyForTest());
        assertTrue(service.signalsModel().workspaceKeys().isEmpty());
    }

    @Test
    void focusAfterMultiSelectKeepsAllVisibleKeys() {
        bind(dbc("EngineRPM", "OBD2", "rpm", 1), dbc("VehicleSpeed", "OBD2", "km/h", 2), dbc("CoolantTemp", "OBD2", "degC", 3));
        SignalCatalogKey rpm = key("EngineRPM", SignalSourceType.DBC_CAN, "OBD2", 1);
        SignalCatalogKey speed = key("VehicleSpeed", SignalSourceType.DBC_CAN, "OBD2", 2);
        SignalCatalogKey temp = key("CoolantTemp", SignalSourceType.DBC_CAN, "OBD2", 3);
        graph.toggleMembershipForTest(rpm, true);
        graph.toggleMembershipForTest(speed, true);
        graph.toggleMembershipForTest(temp, true);
        assertEquals(3, graph.visibleKeysForTest().size());
        graph.singleClickExplorerForTest(speed);
        assertEquals(speed, graph.focusedExplorerKeyForTest());
        assertEquals(3, graph.visibleKeysForTest().size());
        graph.singleClickExplorerForTest(temp);
        assertEquals(3, graph.visibleKeysForTest().size());
    }

    @Test
    void doubleClickSelectsUnselectedAndDoesNotDeselectSelected() {
        bind(dbc("EngineRPM", "OBD2", "rpm", 1), dbc("VehicleSpeed", "OBD2", "km/h", 2));
        SignalCatalogKey rpm = key("EngineRPM", SignalSourceType.DBC_CAN, "OBD2", 1);
        SignalCatalogKey speed = key("VehicleSpeed", SignalSourceType.DBC_CAN, "OBD2", 2);
        graph.doubleClickExplorerForTest(rpm);
        assertEquals(List.of(rpm), service.signalsModel().workspaceKeys());
        assertEquals(rpm, graph.focusedExplorerKeyForTest());
        graph.doubleClickExplorerForTest(rpm);
        assertEquals(List.of(rpm), service.signalsModel().workspaceKeys());
        graph.doubleClickExplorerForTest(speed);
        assertEquals(List.of(rpm, speed), service.signalsModel().workspaceKeys());
        graph.singleClickExplorerForTest(rpm);
        assertEquals(rpm, graph.focusedExplorerKeyForTest());
        assertEquals(List.of(rpm, speed), service.signalsModel().workspaceKeys());
    }

    @Test
    void membershipToggleDoesNotReorderExplorerRows() {
        bind(
                dbc("AlphaSig", "OBD2", "u", 1),
                dbc("BravoSig", "OBD2", "u", 2),
                dbc("CharlieSig", "OBD2", "u", 3),
                dbc("DeltaSig", "OBD2", "u", 4)
        );
        List<String> before = graph.explorerRowsForTest().stream().map(SignalCatalogEntry::signalName).toList();
        assertEquals(List.of("AlphaSig", "BravoSig", "CharlieSig", "DeltaSig"), before);

        SignalCatalogKey charlie = key("CharlieSig", SignalSourceType.DBC_CAN, "OBD2", 3);
        SignalCatalogKey alpha = key("AlphaSig", SignalSourceType.DBC_CAN, "OBD2", 1);
        SignalCatalogKey delta = key("DeltaSig", SignalSourceType.DBC_CAN, "OBD2", 4);

        graph.toggleMembershipForTest(charlie, true);
        assertEquals(before, graph.explorerRowsForTest().stream().map(SignalCatalogEntry::signalName).toList());
        graph.toggleMembershipForTest(charlie, false);
        assertEquals(before, graph.explorerRowsForTest().stream().map(SignalCatalogEntry::signalName).toList());
        graph.toggleMembershipForTest(alpha, true);
        graph.toggleMembershipForTest(delta, true);
        assertEquals(before, graph.explorerRowsForTest().stream().map(SignalCatalogEntry::signalName).toList());
        graph.singleClickExplorerForTest(charlie);
        assertEquals(before, graph.explorerRowsForTest().stream().map(SignalCatalogEntry::signalName).toList());
    }

    @Test
    void selectingSignalFitsCollapsedViewportSoGraphCanLoad() {
        bind(
                dbc("VehicleSpeed", "OBD2", "km/h", 1),
                dbc("EngineRPM", "OBD2", "rpm", 2)
        );
        SignalCatalogKey speed = key("VehicleSpeed", SignalSourceType.DBC_CAN, "OBD2", 1);
        // Simulate a collapsed/tiny viewport left by a prior zoom/cursor (matches product bug).
        graph.setViewportForTest(502_298_000_000L, 502_298_000_500L);
        assertTrue(graph.viewportEndForTest() - graph.viewportStartForTest() < 1_000_000L);

        graph.toggleMembershipForTest(speed, true);

        assertEquals(List.of(speed), service.signalsModel().workspaceKeys());
        assertTrue(graph.viewportEndForTest() > graph.viewportStartForTest());
        // Fixture timestamps are ~1µs; a successful fit must leave the collapsed 502s window.
        assertTrue(graph.viewportStartForTest() < 1_000_000_000L,
                "selection must refit away from the collapsed 502.298s viewport");
        assertTrue(graph.viewportEndForTest() < 1_000_000_000L);
    }

    @Test
    void deselectingLastSignalClearsAnalysisMembership() {
        bind(dbc("VehicleSpeed", "OBD2", "km/h", 1));
        SignalCatalogKey speed = key("VehicleSpeed", SignalSourceType.DBC_CAN, "OBD2", 1);
        graph.toggleMembershipForTest(speed, true);
        assertEquals(1, service.signalsModel().workspaceKeys().size());
        graph.toggleMembershipForTest(speed, false);
        assertTrue(service.signalsModel().workspaceKeys().isEmpty());
    }

    @Test
    void selectedFilterUsesWorkspaceMembership() {
        bind(dbc("EngineRPM", "OBD2", "rpm", 1), dbc("VehicleSpeed", "OBD2", "km/h", 2));
        SignalCatalogKey rpm = key("EngineRPM", SignalSourceType.DBC_CAN, "OBD2", 1);
        graph.toggleMembershipForTest(rpm, true);
        graph.setExplorerFilterForTest("selected");
        assertEquals(1, graph.explorerRowsForTest().size());
        assertEquals(rpm, graph.explorerRowsForTest().getFirst().key());
        graph.setExplorerFilterForTest("all");
        assertEquals(2, graph.explorerRowsForTest().size());
    }


    @Test
    void sourceBadgesAndTooltipsAreCompleteWords() {
        bind(dbc("EngineRPM", "OBD2", "rpm", 1), nativeSig("Latitude", "deg", 3));
        SignalCatalogEntry dbc = graph.explorerRowsForTest().stream()
                .filter(e -> e.sourceType() == SignalSourceType.DBC_CAN).findFirst().orElseThrow();
        SignalCatalogEntry nativeEntry = graph.explorerRowsForTest().stream()
                .filter(e -> e.sourceType() == SignalSourceType.MF4_NATIVE).findFirst().orElseThrow();
        assertEquals("DBC", graph.sourceBadgeForTest(dbc));
        assertEquals("MF4", graph.sourceBadgeForTest(nativeEntry));
        assertFalse(graph.sourceBadgeForTest(dbc).contains("..."));
        assertEquals("Source: DBC_CAN", graph.sourceTooltipForTest(dbc));
        assertEquals("Source: MF4_NATIVE", graph.sourceTooltipForTest(nativeEntry));
        assertTrue(dbc.signalName().equals("EngineRPM"));
        assertFalse(graph.addSignalsPopupRequiredForTest());
    }

    @Test
    void searchUsesSharedCatalogFilterAndLongNameTooltipIsComplete() {
        String longName = "VeryLongEngineeringSignalName_EngineRPM_Bank1";
        bind(dbc(longName, "OBD2", "rpm", 1), dbc("VehicleSpeed", "OBD2", "km/h", 2));
        graph.searchExplorerForTest("Engine");
        assertEquals(1, graph.explorerRowsForTest().size());
        SignalCatalogEntry match = graph.explorerRowsForTest().getFirst();
        assertEquals(longName, match.signalName());
        assertEquals(longName, graph.nameTooltipForTest(match));
        assertFalse(graph.nameTooltipForTest(match).contains("..."));
        graph.searchExplorerForTest("");
        assertEquals(2, graph.explorerRowsForTest().size());
    }

    @Test
    void dbcAndNativeFiltersUseExistingClassifications() {
        bind(dbc("EngineRPM", "OBD2", "rpm", 1), nativeSig("Latitude", "deg", 3));
        graph.setExplorerFilterForTest("dbc");
        assertEquals(1, graph.explorerRowsForTest().size());
        assertEquals(SignalSourceType.DBC_CAN, graph.explorerRowsForTest().getFirst().sourceType());
        graph.setExplorerFilterForTest("native");
        assertEquals(1, graph.explorerRowsForTest().size());
        assertEquals(SignalSourceType.MF4_NATIVE, graph.explorerRowsForTest().getFirst().sourceType());
    }

    @Test
    void retiredRecommendedAndNativeFiltersRestoreToAll() {
        bind(dbc("EngineRPM", "OBD2", "rpm", 1), nativeSig("Latitude", "deg", 3));
        graph.restoreExplorerFilterForTest("recommended");
        assertEquals("ALL", graph.explorerFilterForTest());
        assertEquals(2, graph.explorerRowsForTest().size());
        graph.restoreExplorerFilterForTest("native");
        assertEquals("ALL", graph.explorerFilterForTest());
        assertEquals(2, graph.explorerRowsForTest().size());
        graph.setExplorerFilterForTest("native");
        assertEquals("NATIVE", graph.explorerFilterForTest());
        assertEquals(1, graph.explorerRowsForTest().size());
        graph.setExplorerFilterForTest("recommended");
        assertEquals("RECOMMENDED", graph.explorerFilterForTest());
    }

    private void bind(CanonicalSignal... signals) {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        service.newProject("Explorer");
        SignalsScreenModel model = service.signalsModel();
        model.replaceCatalog(catalog(signals));
        graph = new GraphController();
        graph.bindMembershipForTest(service);
    }

    private static InMemorySignalCatalogStore catalog(CanonicalSignal... signals) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 1_000);
        store.accept(List.of(signals));
        return store;
    }

    private static CanonicalSignal dbc(String name, String message, String unit, int channel) {
        return new CanonicalSignal(
                name, message, 1_000L * channel, 0x100, channel, 10.0, 10L, unit, null,
                SourceReference.ofLine(channel));
    }

    private static CanonicalSignal nativeSig(String name, String unit, int channel) {
        return new CanonicalSignal(
                name, InMemorySignalCatalogStore.MF4_MEASUREMENT_MESSAGE, 2_000L * channel, 0L, channel, 1.0, 1L, unit, null,
                SourceReference.ofLine(channel));
    }

    private static SignalCatalogKey key(String name, SignalSourceType type, String message, int channel) {
        return new SignalCatalogKey(name, type, message, type == SignalSourceType.DBC_CAN ? 0x100 : 0L,
                channel, "run.mf4", "veh.dbc");
    }
}
