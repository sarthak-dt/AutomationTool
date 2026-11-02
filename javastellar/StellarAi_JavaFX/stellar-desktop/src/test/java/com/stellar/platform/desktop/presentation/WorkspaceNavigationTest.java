package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceNavigationTest {

    private final SignalsScreenModel model = new SignalsScreenModel();

    @AfterEach
    void tearDown() {
        model.shutdown();
    }

    @Test
    @Timeout(15)
    void nextPreviousWindowPreservesGlobalIndexes() throws Exception {
        seedDenseSeries(12);
        model.setSamplePageSize(5);
        model.addToWorkspace(key("A"));

        AlignedWorkspaceSnapshot first = awaitLoad(true);
        assertEquals(0, first.windowStartIndex());
        assertEquals(0L, first.rows().getFirst().index());
        assertEquals(4L, first.rows().getLast().index());
        assertEquals(12, first.totalAlignedRows());
        assertTrue(first.hasNext());
        assertFalse(first.hasPrevious());

        AlignedWorkspaceSnapshot second = awaitNav(true);
        assertEquals(5, second.windowStartIndex());
        assertEquals(5L, second.rows().getFirst().index());
        assertEquals(9L, second.rows().getLast().index());
        assertTrue(second.hasPrevious());

        AlignedWorkspaceSnapshot back = awaitNav(false);
        assertEquals(0, back.windowStartIndex());
        assertEquals(0L, back.rows().getFirst().index());
    }

    @Test
    @Timeout(15)
    void jumpToIndexUsesAlignedRowWhenTimelinesDiffer() throws Exception {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("demo.mf4", "demo.dbc", 100);
        store.accept(List.of(
                can("A", 1_000L, 1.0),
                can("A", 3_000L, 3.0),
                can("A", 5_000L, 5.0),
                can("B", 2_000L, 2.0),
                can("B", 4_000L, 4.0),
                can("B", 6_000L, 6.0)
        ));
        model.replaceCatalog(store);
        model.setSamplePageSize(2);
        model.addToWorkspace(List.of(key("A"), key("B")));
        awaitLoad(true);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AlignedWorkspaceSnapshot> ref = new AtomicReference<>();
        Optional<String> err = model.jumpToIndexAsync(4, snap -> {
            ref.set(snap);
            done.countDown();
        });
        assertTrue(err.isEmpty());
        assertTrue(done.await(10, TimeUnit.SECONDS));
        AlignedSampleRow focused = ref.get().rows().get((int) ref.get().focusRowIndex().orElseThrow());
        assertEquals(4L, focused.index());
        assertEquals(5_000L, focused.timestampNanos());
        assertEquals(5.0, focused.physicalAt(0));
        assertNull(focused.physicalAt(1));
    }

    @Test
    @Timeout(15)
    void jumpToIndexFindsCorrectWindow() throws Exception {
        seedDenseSeries(20);
        model.setSamplePageSize(5);
        model.addToWorkspace(key("A"));
        awaitLoad(true);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AlignedWorkspaceSnapshot> ref = new AtomicReference<>();
        Optional<String> err = model.jumpToIndexAsync(14, snap -> {
            ref.set(snap);
            done.countDown();
        });
        assertTrue(err.isEmpty());
        assertTrue(done.await(10, TimeUnit.SECONDS));
        AlignedWorkspaceSnapshot snap = ref.get();
        assertEquals(10, snap.windowStartIndex());
        assertEquals(14L, snap.rows().get((int) snap.focusRowIndex().orElseThrow()).index());
    }

    @Test
    void negativeIndexRejected() {
        Optional<String> err = model.jumpToIndexAsync(-1, snap -> { });
        assertEquals("Invalid index", err.orElseThrow());
    }

    @Test
    @Timeout(15)
    void jumpToTimeUsesFirstAtOrAfter() throws Exception {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("demo.mf4", "demo.dbc", 1_000);
        store.accept(List.of(
                can("A", 100_000_000L, 1.0),
                can("A", 200_000_000L, 2.0),
                can("A", 300_000_000L, 3.0)
        ));
        model.replaceCatalog(store);
        model.setSamplePageSize(5);
        model.addToWorkspace(key("A"));
        awaitLoad(true);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AlignedWorkspaceSnapshot> ref = new AtomicReference<>();
        model.jumpToTimeSecondsAsync(0.15, snap -> {
            ref.set(snap);
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        AlignedWorkspaceSnapshot snap = ref.get();
        AlignedSampleRow focused = snap.rows().get((int) snap.focusRowIndex().orElseThrow());
        assertEquals(200_000_000L, focused.timestampNanos());
        assertEquals(2.0, focused.physicalAt(0));
    }

    @Test
    void precisionChangesFormattingOnly() {
        assertEquals("73.58", WorkspacePrecision.DEC_2.formatPhysical(73.5819025));
        assertEquals("73.5819025", WorkspacePrecision.FULL.formatPhysical(73.5819025));
        assertEquals("0.00", WorkspacePrecision.DEC_2.formatPhysical(0.0));
        assertEquals(AlignedSampleRow.MISSING, "\u2014");
    }

    @Test
    @Timeout(15)
    void zeroAndMissingSurviveNavigation() throws Exception {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("demo.mf4", "demo.dbc", 1_000);
        store.accept(List.of(
                can("A", 1_000L, 0.0),
                can("A", 3_000L, 5.0),
                can("B", 2_000L, 0.0),
                can("B", 3_000L, 9.0)
        ));
        model.replaceCatalog(store);
        model.addToWorkspace(List.of(key("A"), key("B")));
        AlignedWorkspaceSnapshot snap = awaitLoad(true);
        assertEquals(0.0, snap.rows().get(0).physicalAt(0));
        assertNull(snap.rows().get(0).physicalAt(1));
        assertNull(snap.rows().get(1).physicalAt(0));
        assertEquals(0.0, snap.rows().get(1).physicalAt(1));
    }

    @Test
    @Timeout(30)
    void pageLargerThanTheStoreCapAdvancesByTheSelectedPageSize() throws Exception {
        int total = 12_000;
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("demo.mf4", "demo.dbc", total);
        List<CanonicalSignal> batch = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            batch.add(can("A", (i + 1L) * 1_000_000L, i));
        }
        store.accept(batch);
        model.replaceCatalog(store);
        model.setSamplePageSize(10_000);
        model.addToWorkspace(key("A"));

        AlignedWorkspaceSnapshot first = awaitLoad(true);
        assertEquals(10_000, first.rows().size());
        assertEquals(0, first.windowStartIndex());
        assertEquals(0L, first.rows().getFirst().index());
        assertEquals(9_999L, first.rows().getLast().index());

        AlignedWorkspaceSnapshot second = awaitNav(true);
        assertEquals(10_000, second.windowStartIndex());
        assertEquals(10_000L, second.rows().getFirst().index());
        assertEquals(11_999L, second.rows().getLast().index());
        assertEquals(2_000, second.rows().size());
        assertFalse(second.hasNext());
    }

    @Test
    @Timeout(15)
    void navigationDoesNotChangeWorkspaceMembership() throws Exception {
        seedDenseSeries(12);
        model.setSamplePageSize(5);
        model.addToWorkspace(key("A"));
        List<SignalCatalogKey> before = model.workspaceKeys();
        awaitLoad(true);
        awaitNav(true);
        assertEquals(before, model.workspaceKeys());
    }

    private AlignedWorkspaceSnapshot awaitNav(boolean next) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AlignedWorkspaceSnapshot> ref = new AtomicReference<>();
        ConsumerSnap c = s -> {
            ref.set(s);
            done.countDown();
        };
        if (next) {
            model.navigateNextWindowAsync(c);
        } else {
            model.navigatePreviousWindowAsync(c);
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        return ref.get();
    }

    private AlignedWorkspaceSnapshot awaitLoad(boolean force) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AlignedWorkspaceSnapshot> ref = new AtomicReference<>();
        model.loadWorkspaceAlignedAsync(force, s -> {
            ref.set(s);
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        return ref.get();
    }

    private void seedDenseSeries(int count) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("demo.mf4", "demo.dbc", 1_000);
        List<CanonicalSignal> batch = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            batch.add(can("A", (i + 1) * 1_000_000L, i));
        }
        store.accept(batch);
        model.replaceCatalog(store);
    }

    private static SignalCatalogKey key(String name) {
        return new SignalCatalogKey(name, SignalSourceType.DBC_CAN, "MSG", 1L, "demo.mf4", "demo.dbc");
    }

    private static CanonicalSignal can(String name, long ts, double phys) {
        return new CanonicalSignal(name, "MSG", ts, 1L, 0, phys, (long) phys, "u", null, SourceReference.ofLine(1));
    }

    @FunctionalInterface
    private interface ConsumerSnap extends java.util.function.Consumer<AlignedWorkspaceSnapshot> {
    }
}
