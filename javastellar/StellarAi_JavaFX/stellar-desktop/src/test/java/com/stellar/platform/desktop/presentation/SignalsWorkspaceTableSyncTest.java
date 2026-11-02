package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workspace membership is the single source of truth for chips, summary count, and table columns.
 */
class SignalsWorkspaceTableSyncTest {

    private final SignalsScreenModel model = new SignalsScreenModel();

    @AfterEach
    void tearDown() {
        model.shutdown();
    }

    @Test
    @Timeout(10)
    void singleWorkspaceSignalProducesTimestampAlignedSamples() throws Exception {
        InMemorySignalCatalogStore store = catalogWith(
                can("EngineRPM", "EngineData", 0x100, 570_140_000L, 1646.0, 1L),
                can("EngineRPM", "EngineData", 0x100, 669_310_000L, 1750.0, 2L)
        );
        model.replaceCatalog(store);
        SignalCatalogKey rpm = key("EngineRPM", "EngineData", 0x100);
        assertTrue(model.addToWorkspace(rpm));
        assertEquals(1, model.workspaceKeys().size());
        assertEquals(1, model.workspaceEntries().size());

        AlignedWorkspaceSnapshot snap = loadAligned();
        assertEquals(1, snap.signals().size());
        assertEquals(rpm, snap.signals().getFirst().key());
        assertFalse(snap.rows().isEmpty(), "sample rows must populate for a workspace signal with samples");
        assertNotNull(snap.rows().getFirst().physicalAt(0));
    }

    @Test
    @Timeout(10)
    void chipAndTableMembershipStaySynchronized() throws Exception {
        InMemorySignalCatalogStore store = catalogWith(
                can("A", "M", 0x1, 1_000L, 1.0, 1L),
                can("B", "M", 0x2, 1_000L, 2.0, 2L)
        );
        model.replaceCatalog(store);
        SignalCatalogKey a = key("A", "M", 0x1);
        SignalCatalogKey b = key("B", "M", 0x2);

        model.addToWorkspace(a);
        assertEquals(1, model.workspaceKeys().size());
        assertEquals(1, loadAligned().signals().size());

        model.addToWorkspace(b);
        assertEquals(2, model.workspaceKeys().size());
        assertEquals(2, loadAligned().signals().size());

        model.removeFromWorkspace(a);
        assertEquals(1, model.workspaceKeys().size());
        assertEquals(List.of(b), model.workspaceKeys());
        assertEquals(1, loadAligned().signals().size());

        model.clearWorkspace();
        assertEquals(0, model.workspaceKeys().size());
        assertTrue(loadAligned().signals().isEmpty());
    }

    @Test
    @Timeout(10)
    void projectExploreLogicalKeyResolvesSamplesWithoutReprocess() throws Exception {
        InMemorySignalCatalogStore physical = catalogWith(
                can("S", "OBD2", 0x7E8, 1_000L, 42.0, 1L)
        );
        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(physical, "srcA", "dbcA", "A.mf4", "veh.dbc")
        ), "project", "dbc");
        model.replaceCatalog(merged, "Explore", SignalsProcessingContext.quickDecode("3 Sources", "veh.dbc", "3 Channels"));

        SignalCatalogKey logical = merged.snapshot().entries().getFirst().key();
        assertEquals(ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE, logical.sourceFile());
        assertTrue(merged.snapshot().entries().getFirst().sampleCount() > 0);
        assertTrue(merged.hasResolvableSamples(logical), "merged route must resolve without reprocess");
        assertTrue(model.addToWorkspace(logical));

        AlignedWorkspaceSnapshot snap = loadAligned();
        assertEquals(1, snap.signals().size());
        assertFalse(snap.rows().isEmpty());
        assertFalse(merged.querySamples(logical, 0, 10).isEmpty());
        assertTrue(model.lastWorkspaceDataFeedback().isBlank());
    }

    @Test
    void chipCountEqualsWorkspaceColumnMembershipCount() {
        InMemorySignalCatalogStore store = catalogWith(
                can("A", "M", 0x1, 1_000L, 1.0, 1L),
                can("B", "M", 0x2, 1_000L, 2.0, 2L)
        );
        model.replaceCatalog(store);
        model.addToWorkspace(key("A", "M", 0x1));
        model.addToWorkspace(key("B", "M", 0x2));
        assertEquals(model.workspaceKeys().size(), model.workspaceEntries().size());
        assertEquals(2, model.workspaceEntries().size());
    }

    @Test
    @Timeout(10)
    void physicalKeyAgainstProjectStoreStillLoadsSamples() throws Exception {
        InMemorySignalCatalogStore physical = catalogWith(
                can("S", "OBD2", 0x7E8, 1_000L, 42.0, 1L)
        );
        SignalCatalogKey physicalKey = physical.snapshot().entries().getFirst().key();
        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(physical, "srcA", "dbcA", "A.mf4", "veh.dbc")
        ), "project", "dbc");
        model.replaceCatalog(merged, "Explore", SignalsProcessingContext.quickDecode("A", "veh.dbc", "1 Channel"));
        // Remap on add should prefer logical key; force physical by replacing after add path.
        assertTrue(model.addToWorkspace(physicalKey));
        assertEquals(1, model.workspaceKeys().size());
        assertEquals(1, model.workspaceEntries().size());

        AlignedWorkspaceSnapshot snap = loadAligned();
        assertEquals(1, snap.signals().size());
        assertFalse(snap.rows().isEmpty(), "project store must resolve physical keys without reprocess");
    }

    @Test
    void workspaceEntriesNeverDropsUnresolvedKeys() {
        InMemorySignalCatalogStore store = catalogWith(
                can("A", "M", 0x1, 1_000L, 1.0, 1L)
        );
        model.replaceCatalog(store);
        SignalCatalogKey orphan = key("Missing", "M", 0x99);
        assertTrue(model.addToWorkspace(orphan));
        assertEquals(1, model.workspaceKeys().size());
        assertEquals(1, model.workspaceEntries().size(), "membership chrome must stay 1:1 with keys");
        assertEquals(orphan, model.workspaceEntries().getFirst().key());
    }

    @Test
    void preserveWorkspaceRemapsPhysicalToLogicalAcrossCatalogSwap() {
        InMemorySignalCatalogStore physical = catalogWith(
                can("S", "OBD2", 0x7E8, 1_000L, 42.0, 1L)
        );
        model.replaceCatalog(physical, "View", SignalsProcessingContext.of("A.mf4", "veh.dbc", "Ch1"));
        SignalCatalogKey physicalKey = key("S", "OBD2", 0x7E8);
        assertTrue(model.addToWorkspace(physicalKey));

        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(physical, "srcA", "dbcA", "A.mf4", "veh.dbc")
        ), "project", "dbc");
        model.replaceCatalog(merged, "Explore", SignalsProcessingContext.quickDecode("A", "veh.dbc", "1 Channel"), true);

        assertEquals(1, model.workspaceKeys().size());
        assertEquals(ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE, model.workspaceKeys().getFirst().sourceFile());
    }

    private AlignedWorkspaceSnapshot loadAligned() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AlignedWorkspaceSnapshot> ref = new AtomicReference<>();
        model.loadWorkspaceAlignedAsync(true, snap -> {
            ref.set(snap);
            latch.countDown();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        return ref.get();
    }

    private static InMemorySignalCatalogStore catalogWith(CanonicalSignal... signals) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("demo.mf4", "demo.dbc", 1_000);
        store.accept(List.of(signals));
        return store;
    }

    private static CanonicalSignal can(
            String name, String message, long canId, long ts, double value, long index
    ) {
        return new CanonicalSignal(name, message, ts, canId, 0, value, index, "u", null, SourceReference.ofLine(1));
    }

    private static SignalCatalogKey key(String name, String message, long canId) {
        return new SignalCatalogKey(name, SignalSourceType.DBC_CAN, message, canId, "demo.mf4", "demo.dbc");
    }
}
