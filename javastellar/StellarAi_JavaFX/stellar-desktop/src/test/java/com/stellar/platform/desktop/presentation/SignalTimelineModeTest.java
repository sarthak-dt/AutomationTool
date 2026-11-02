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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Aligned timeline for signals on distinct clocks. Timestamps stay the original
 * measurement values. There is no second per-signal table mode.
 */
class SignalTimelineModeTest {

    private SignalsScreenModel model;

    @AfterEach
    void tearDown() {
        if (model != null) {
            model.shutdown();
        }
    }

    @Test
    @Timeout(20)
    void alignedUnionKeepsOriginalSamplesOnDistinctClocks() throws Exception {
        model = new SignalsScreenModel();
        model.setSamplePageSize(2);
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("sierra.mf4", "temp.dbc", 1_000);
        store.accept(List.of(
                can("CH01", 0xA, 84_940_000L, 25),
                can("CH01", 0xA, 184_950_000L, 26),
                can("CH01", 0xA, 284_950_000L, 27),
                can("CH05", 0xB, 85_130_000L, 79)
        ));
        model.replaceCatalog(store);
        SignalCatalogKey ch01 = find("CH01").key();
        SignalCatalogKey ch05 = find("CH05").key();
        model.addToWorkspace(List.of(ch01, ch05));
        model.setFocusedSignalKey(ch01);

        AlignedWorkspaceSnapshot aligned = awaitLoad();
        assertEquals(2, aligned.signals().size());
        assertEquals(4, aligned.totalAlignedRows());
        assertEquals(2, aligned.rows().size());
        assertEquals(0L, aligned.rows().get(0).index());
        assertEquals(84_940_000L, aligned.rows().get(0).timestampNanos());
        assertEquals(25.0, aligned.rows().get(0).physicalAt(0));
        assertNull(aligned.rows().get(0).physicalAt(1));
        assertEquals(1L, aligned.rows().get(1).index());
        assertEquals(85_130_000L, aligned.rows().get(1).timestampNanos());
        assertNull(aligned.rows().get(1).physicalAt(0));
        assertEquals(79.0, aligned.rows().get(1).physicalAt(1));

        AlignedWorkspaceSnapshot alignedPage2 = awaitNav(model::navigateNextWindowAsync);
        assertEquals(2, alignedPage2.windowStartIndex());
        assertEquals(184_950_000L, alignedPage2.rows().get(0).timestampNanos());
        assertEquals(26.0, alignedPage2.rows().get(0).physicalAt(0));
        assertEquals(27.0, alignedPage2.rows().get(1).physicalAt(0));
        assertEquals(284_950_000L, alignedPage2.rows().get(1).timestampNanos());
        assertEquals(25L, aligned.rows().get(0).rawAt(0));

        assertTrue(model.jumpToIndexAsync(4, ignored -> { }).isPresent());
        AlignedWorkspaceSnapshot jumped = awaitNav(callback ->
                assertTrue(model.jumpToIndexAsync(3, callback).isEmpty()));
        assertEquals(2, jumped.windowStartIndex());
        assertEquals(284_950_000L, jumped.rows().getLast().timestampNanos());

        int queriesBeforeFocus = model.workspaceAlignedQueryCountForTest();
        model.setFocusedSignalKey(ch05);
        assertEquals(queriesBeforeFocus, model.workspaceAlignedQueryCountForTest());
        assertEquals(4, model.reusableWorkspaceSnapshot().orElseThrow().totalAlignedRows());
        assertEquals(2, model.reusableWorkspaceSnapshot().orElseThrow().signals().size());
    }

    @Test
    @Timeout(20)
    void oneSelectedSignalUsesItsOwnSampleIndex() throws Exception {
        model = new SignalsScreenModel();
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("sierra.mf4", "temp.dbc", 1_000);
        store.accept(List.of(
                can("CH01", 0xA, 84_940_000L, 25),
                can("CH01", 0xA, 184_950_000L, 26)
        ));
        model.replaceCatalog(store);
        model.addToWorkspace(find("CH01").key());
        AlignedWorkspaceSnapshot snap = awaitLoad();
        assertEquals(1, snap.signals().size());
        assertEquals(2, snap.totalAlignedRows());
        assertEquals(0L, snap.rows().get(0).index());
        assertEquals(1L, snap.rows().get(1).index());
        assertEquals(84_940_000L, snap.rows().get(0).timestampNanos());
        assertEquals(184_950_000L, snap.rows().get(1).timestampNanos());
        assertEquals(26.0, snap.rows().get(1).physicalAt(0));
    }

    private AlignedWorkspaceSnapshot awaitLoad() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AlignedWorkspaceSnapshot> ref = new AtomicReference<>();
        model.loadWorkspaceAlignedAsync(true, snap -> {
            ref.set(snap);
            latch.countDown();
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        return ref.get();
    }

    private AlignedWorkspaceSnapshot awaitNav(java.util.function.Consumer<java.util.function.Consumer<AlignedWorkspaceSnapshot>> call)
            throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AlignedWorkspaceSnapshot> ref = new AtomicReference<>();
        call.accept(snap -> {
            ref.set(snap);
            latch.countDown();
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        return ref.get();
    }

    private SignalCatalogEntry find(String name) {
        return model.catalog().entries().stream()
                .filter(entry -> name.equals(entry.signalName()))
                .findFirst()
                .orElseThrow();
    }

    private static CanonicalSignal can(String name, long canId, long ts, double physical) {
        return new CanonicalSignal(
                name, "Msg", ts, canId, 2, physical, (long) physical, "°C",
                null, SourceReference.ofLine(1));
    }
}
