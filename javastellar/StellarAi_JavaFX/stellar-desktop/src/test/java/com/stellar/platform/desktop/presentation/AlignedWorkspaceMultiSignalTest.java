package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-signal Active Signal Workspace: identity, remove/clear, paging, Index/Time Go.
 * Uses inventedsynthetic names; does not reparse measurement sources.
 */
class AlignedWorkspaceMultiSignalTest {

    private SignalsScreenModel model;

    @AfterEach
    void tearDown() {
        if (model != null) {
            model.shutdown();
        }
    }

    @Test
    @Timeout(20)
    void sameNameDifferentChannelsRemainDistinctColumns() throws Exception {
        model = new SignalsScreenModel();
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("meas.mf4", "def.dbc", 1_000);
        store.accept(List.of(
                can("VehicleSpeed", "SpeedMsg", 0x100, 1, 1_000_000_000L, 10),
                can("VehicleSpeed", "SpeedMsg", 0x100, 3, 1_000_000_000L, 99),
                can("VehicleSpeed", "SpeedMsg", 0x100, 1, 2_000_000_000L, 20),
                can("VehicleSpeed", "SpeedMsg", 0x100, 3, 3_000_000_000L, 88)
        ));
        model.replaceCatalog(store);

        SignalCatalogKey ch1 = find(model, "VehicleSpeed", 1).key();
        SignalCatalogKey ch3 = find(model, "VehicleSpeed", 3).key();
        assertNotEquals(ch1, ch3);

        model.addToWorkspace(List.of(ch1, ch3));
        AlignedWorkspaceSnapshot snap = awaitAligned(model);
        assertEquals(2, snap.signals().size());
        assertEquals(1, snap.signals().get(0).key().channel());
        assertEquals(3, snap.signals().get(1).key().channel());
        assertEquals(3, snap.totalAlignedRows());
        assertEquals(10.0, snap.rows().get(0).physicalAt(0));
        assertEquals(99.0, snap.rows().get(0).physicalAt(1));
        assertNull(snap.rows().get(1).physicalAt(1));
        assertNull(snap.rows().get(2).physicalAt(0));
    }

    @Test
    @Timeout(20)
    void removeMiddleSignalAndClearAll() throws Exception {
        model = new SignalsScreenModel();
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("meas.mf4", "def.dbc", 1_000);
        store.accept(List.of(
                can("EngineRPM", "E", 1, 1, 1_000_000_000L, 1000),
                can("VehicleSpeed", "V", 2, 1, 1_000_000_000L, 50),
                can("CoolantTemp", "C", 3, 1, 2_000_000_000L, 90)
        ));
        model.replaceCatalog(store);
        SignalCatalogKey a = find(model, "EngineRPM", 1).key();
        SignalCatalogKey b = find(model, "VehicleSpeed", 1).key();
        SignalCatalogKey c = find(model, "CoolantTemp", 1).key();
        model.addToWorkspace(List.of(a, b, c));
        AlignedWorkspaceSnapshot three = awaitAligned(model);
        assertEquals(3, three.signals().size());
        assertEquals(2, three.totalAlignedRows());

        model.removeFromWorkspace(b);
        AlignedWorkspaceSnapshot two = awaitAligned(model);
        assertEquals(2, two.signals().size());
        assertEquals("EngineRPM", two.signals().get(0).signalName());
        assertEquals("CoolantTemp", two.signals().get(1).signalName());
        assertEquals(3, model.catalog().size()); // remove does not touch catalog
        assertEquals(3, store.snapshot().size());

        model.clearWorkspace();
        AlignedWorkspaceSnapshot empty = awaitAligned(model);
        assertTrue(empty.rows().isEmpty());
        assertTrue(model.workspaceKeys().isEmpty());
        assertEquals(3, model.catalog().size()); // Clear All does not clear browser catalog
    }

    @Test
    @Timeout(20)
    void alignedPagingAndIndexTimeGo() throws Exception {
        model = new SignalsScreenModel();
        model.setSamplePageSize(2);
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("meas.mf4", "def.dbc", 1_000);
        store.accept(List.of(
                can("AlphaSig", "M", 1, 1, 1_000_000_000L, 1),
                can("AlphaSig", "M", 1, 1, 2_000_000_000L, 2),
                can("AlphaSig", "M", 1, 1, 3_000_000_000L, 3),
                can("AlphaSig", "M", 1, 1, 4_000_000_000L, 4),
                can("BetaSig", "M", 2, 1, 2_000_000_000L, 20),
                can("BetaSig", "M", 2, 1, 4_000_000_000L, 40)
        ));
        model.replaceCatalog(store);
        model.addToWorkspace(List.of(find(model, "AlphaSig", 1).key(), find(model, "BetaSig", 1).key()));

        AlignedWorkspaceSnapshot page0 = awaitAligned(model);
        assertEquals(4, page0.totalAlignedRows());
        assertEquals(2, page0.rows().size());
        assertEquals(0L, page0.rows().get(0).index());

        CountDownLatch nextLatch = new CountDownLatch(1);
        AtomicReference<AlignedWorkspaceSnapshot> page1 = new AtomicReference<>();
        model.navigateNextWindowAsync(snap -> {
            page1.set(snap);
            nextLatch.countDown();
        });
        assertTrue(nextLatch.await(10, TimeUnit.SECONDS));
        assertEquals(2L, page1.get().rows().get(0).index());
        assertEquals(4, page1.get().totalAlignedRows());

        CountDownLatch idxLatch = new CountDownLatch(1);
        AtomicReference<AlignedWorkspaceSnapshot> jumped = new AtomicReference<>();
        assertTrue(model.jumpToIndexAsync(3, snap -> {
            jumped.set(snap);
            idxLatch.countDown();
        }).isEmpty());
        assertTrue(idxLatch.await(10, TimeUnit.SECONDS));
        assertTrue(jumped.get().focusRowIndex().isPresent());
        assertEquals(3L, jumped.get().rows().get((int) jumped.get().focusRowIndex().getAsLong()).index());

        CountDownLatch timeLatch = new CountDownLatch(1);
        AtomicReference<AlignedWorkspaceSnapshot> timeJump = new AtomicReference<>();
        assertTrue(model.jumpToTimeSecondsAsync(2.5, snap -> {
            timeJump.set(snap);
            timeLatch.countDown();
        }).isEmpty());
        assertTrue(timeLatch.await(10, TimeUnit.SECONDS));
        // at-or-after 2.5s → aligned row at 3.0s
        long focused = timeJump.get().rows().get((int) timeJump.get().focusRowIndex().orElseThrow()).timestampNanos();
        assertEquals(3_000_000_000L, focused);
    }

    @Test
    @Timeout(20)
    void workspaceLimitRejectsWithFeedback_notSilent() {
        model = new SignalsScreenModel();
        model.setMaxWorkspaceSignals(2);
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("meas.mf4", "def.dbc", 100);
        store.accept(List.of(
                can("SigOne", "M", 1, 1, 1L, 1),
                can("SigTwo", "M", 2, 1, 1L, 2),
                can("SigThree", "M", 3, 1, 1L, 3)
        ));
        model.replaceCatalog(store);
        int added = model.addToWorkspace(List.of(
                find(model, "SigOne", 1).key(),
                find(model, "SigTwo", 1).key(),
                find(model, "SigThree", 1).key()
        ));
        assertEquals(2, added);
        assertEquals(2, model.workspaceKeys().size());
        assertFalse(model.lastWorkspaceAddFeedback().isBlank());
    }

    @Test
    @Timeout(20)
    void singleSignalBaseline_unchangedValues() throws Exception {
        model = new SignalsScreenModel();
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("meas.mf4", "def.dbc", 1_000);
        store.accept(List.of(
                can("SoloSig", "M", 0x10, 3, 1_000_000_000L, 11),
                can("SoloSig", "M", 0x10, 3, 2_000_000_000L, 22)
        ));
        model.replaceCatalog(store);
        model.addToWorkspace(find(model, "SoloSig", 3).key());
        AlignedWorkspaceSnapshot snap = awaitAligned(model);
        assertEquals(2, snap.totalAlignedRows());
        assertEquals(11.0, snap.rows().get(0).physicalAt(0));
        assertEquals(22.0, snap.rows().get(1).physicalAt(0));
        assertEquals(1_000_000_000L, snap.rows().get(0).timestampNanos());
        assertEquals(3, snap.signals().getFirst().key().channel());
    }

    private static SignalCatalogEntry find(SignalsScreenModel model, String name, int channel) {
        return model.catalog().entries().stream()
                .filter(e -> name.equals(e.signalName()) && e.key().channel() == channel)
                .findFirst()
                .orElseThrow();
    }

    private static AlignedWorkspaceSnapshot awaitAligned(SignalsScreenModel model) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AlignedWorkspaceSnapshot> ref = new AtomicReference<>();
        model.loadWorkspaceAlignedAsync(true, snap -> {
            ref.set(snap);
            latch.countDown();
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        return ref.get();
    }

    private static CanonicalSignal can(
            String name,
            String message,
            long canId,
            int channel,
            long ts,
            double phys
    ) {
        return new CanonicalSignal(
                name,
                message,
                ts,
                canId,
                channel,
                phys,
                (long) phys,
                "u",
                null,
                SourceReference.ofLine(1)
        );
    }
}
