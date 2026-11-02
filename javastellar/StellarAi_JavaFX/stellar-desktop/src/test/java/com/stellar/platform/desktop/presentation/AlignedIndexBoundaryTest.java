package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Aligned index jumps use the timestamp-union count. Overlapping timestamps must not
 * extend the valid range to the sum of the sample counts.
 */
class AlignedIndexBoundaryTest {

    private SignalsScreenModel model;

    @AfterEach
    void tearDown() {
        if (model != null) {
            model.shutdown();
        }
    }

    @Test
    @Timeout(20)
    void identicalTimestampsUseTheUnionNotTheSum() throws Exception {
        model = new SignalsScreenModel();
        model.setSamplePageSize(2);
        load("same.mf4", List.of(
                sample("A", 10, 1),
                sample("A", 20, 2),
                sample("A", 30, 3),
                sample("B", 10, 4),
                sample("B", 20, 5),
                sample("B", 30, 6)
        ), "A", "B");

        AlignedWorkspaceSnapshot first = awaitLoad();
        assertEquals(3, first.totalAlignedRows());
        assertEquals(2, pages(first));
        assertEquals(2, first.rows().size());
        assertEquals(1.0, first.rows().get(0).physicalAt(0));
        assertEquals(4.0, first.rows().get(0).physicalAt(1));
        assertTrue(first.hasNext());
        assertFalse(first.hasPrevious());
        int scans = model.alignedUnionComputationsForTest();
        assertEquals(1, scans);

        AlignedWorkspaceSnapshot last = awaitNav(model::navigateNextWindowAsync);
        assertEquals(2, last.windowStartIndex());
        assertEquals(1, last.rows().size());
        assertEquals(2L, last.rows().getFirst().index());
        assertEquals(30L, last.rows().getFirst().timestampNanos());
        assertEquals(3.0, last.rows().getFirst().physicalAt(0));
        assertEquals(6.0, last.rows().getFirst().physicalAt(1));
        assertFalse(last.hasNext());
        assertTrue(last.hasPrevious());
        assertEquals(2, pages(last));
        assertEquals(scans, model.alignedUnionComputationsForTest());

        assertRejected(3, 2, "Valid aligned indexes are 0 to 2.");
        assertEquals(scans, model.alignedUnionComputationsForTest());

        AlignedWorkspaceSnapshot jumped = awaitJump(2);
        assertEquals(30L, jumped.rows().getFirst().timestampNanos());
        assertFalse(jumped.hasNext());
        assertEquals(scans, model.alignedUnionComputationsForTest());
    }

    @Test
    @Timeout(20)
    void disjointTimestampsKeepEverySampleAndStillStopAtTheUnion() throws Exception {
        model = new SignalsScreenModel();
        model.setSamplePageSize(2);
        load("apart.mf4", List.of(
                sample("A", 10, 1),
                sample("A", 30, 3),
                sample("B", 20, 2),
                sample("B", 40, 4)
        ), "A", "B");

        AlignedWorkspaceSnapshot first = awaitLoad();
        assertEquals(4, first.totalAlignedRows());
        assertEquals(10L, first.rows().get(0).timestampNanos());
        assertEquals(1.0, first.rows().get(0).physicalAt(0));
        assertNull(first.rows().get(0).physicalAt(1));
        assertEquals(20L, first.rows().get(1).timestampNanos());
        assertNull(first.rows().get(1).physicalAt(0));

        assertRejected(4, 0, "Valid aligned indexes are 0 to 3.");
        AlignedWorkspaceSnapshot last = awaitJump(3);
        assertEquals(2, last.windowStartIndex());
        assertEquals(40L, last.rows().getLast().timestampNanos());
        assertEquals(4.0, last.rows().getLast().physicalAt(1));
        assertFalse(last.hasNext());
        assertEquals(2, pages(last));
    }

    @Test
    @Timeout(20)
    void partialOverlapRejectsIndexesBetweenTheUnionAndTheSum() throws Exception {
        model = new SignalsScreenModel();
        model.setSamplePageSize(2);
        load("overlap.mf4", List.of(
                sample("A", 10, 1),
                sample("A", 20, 2),
                sample("A", 30, 3),
                sample("B", 20, 4),
                sample("B", 30, 5),
                sample("B", 40, 6)
        ), "A", "B");

        AlignedWorkspaceSnapshot first = awaitLoad();
        assertEquals(4, first.totalAlignedRows());
        assertEquals(20L, first.rows().get(1).timestampNanos());
        assertEquals(2.0, first.rows().get(1).physicalAt(0));
        assertEquals(4.0, first.rows().get(1).physicalAt(1));

        assertRejected(4, 0, "Valid aligned indexes are 0 to 3.");
        assertRejected(5, 0, "Valid aligned indexes are 0 to 3.");
        AlignedWorkspaceSnapshot tail = awaitJump(3);
        assertEquals(40L, tail.rows().get((int) tail.focusRowIndex().orElseThrow()).timestampNanos());
        assertNull(tail.rows().getLast().physicalAt(0));
        assertEquals(6.0, tail.rows().getLast().physicalAt(1));
        assertFalse(tail.hasNext());
    }

    @Test
    @Timeout(20)
    void singleSignalAndEmptySelectionStayInsideTheirOwnCounts() throws Exception {
        model = new SignalsScreenModel();
        model.setSamplePageSize(2);
        AtomicBoolean called = new AtomicBoolean();
        Optional<String> empty = model.jumpToIndexAsync(0, snap -> called.set(true));
        assertEquals("Index out of range", empty.orElseThrow());
        assertFalse(called.get());

        load("one.mf4", List.of(
                sample("A", 10, 1),
                sample("A", 20, 2),
                sample("A", 30, 3)
        ), "A");
        AlignedWorkspaceSnapshot page = awaitLoad();
        assertEquals(3, page.totalAlignedRows());
        assertRejected(3, 0, "Valid aligned indexes are 0 to 2.");
        AlignedWorkspaceSnapshot last = awaitJump(2);
        assertEquals(30L, last.rows().getFirst().timestampNanos());
        assertFalse(last.hasNext());
        assertEquals(2, pages(last));
    }

    @Test
    @Timeout(20)
    void focusDoesNotChangeTheAlignedIndexDomain() throws Exception {
        model = new SignalsScreenModel();
        model.setSamplePageSize(2);
        InMemorySignalCatalogStore store = store("focus.mf4", List.of(
                sample("A", 10, 1),
                sample("A", 20, 2),
                sample("A", 30, 3),
                sample("B", 15, 9)
        ));
        model.replaceCatalog(store);
        SignalCatalogKey a = find("A");
        SignalCatalogKey b = find("B");
        model.addToWorkspace(List.of(a, b));
        model.setFocusedSignalKey(b);
        AlignedWorkspaceSnapshot aligned = awaitLoad();
        assertEquals(4, aligned.totalAlignedRows());
        assertEquals(10L, aligned.rows().getFirst().timestampNanos());
        assertEquals(15L, aligned.rows().get(1).timestampNanos());
        assertEquals(9.0, aligned.rows().get(1).physicalAt(1));
        assertNull(aligned.rows().get(1).physicalAt(0));

        model.setFocusedSignalKey(a);
        AlignedWorkspaceSnapshot stillAligned = model.reusableWorkspaceSnapshot().orElseThrow();
        assertEquals(4, stillAligned.totalAlignedRows());
        assertEquals(0, stillAligned.windowStartIndex());
        assertRejected(4, 0, "Valid aligned indexes are 0 to 3.");
        AlignedWorkspaceSnapshot tail = awaitJump(3);
        assertEquals(2, tail.windowStartIndex());
        assertEquals(30L, tail.rows().getLast().timestampNanos());
        assertEquals(3.0, tail.rows().getLast().physicalAt(0));
    }

    @Test
    @Timeout(20)
    void selectionAndCatalogChangesRecomputeTheCachedUnion() throws Exception {
        model = new SignalsScreenModel();
        model.setSamplePageSize(2);
        load("cache.mf4", List.of(
                sample("A", 10, 1),
                sample("A", 20, 2),
                sample("B", 10, 3),
                sample("B", 20, 4)
        ), "A", "B");
        awaitLoad();
        assertEquals(1, model.alignedUnionComputationsForTest());
        awaitNav(model::navigateNextWindowAsync);
        assertEquals(1, model.alignedUnionComputationsForTest());

        model.removeFromWorkspace(find("B"));
        AlignedWorkspaceSnapshot onlyA = awaitLoad();
        assertEquals(2, onlyA.totalAlignedRows());
        assertEquals(2, model.alignedUnionComputationsForTest());

        InMemorySignalCatalogStore replacement = store("cache.mf4", List.of(
                sample("A", 10, 1),
                sample("A", 20, 2),
                sample("A", 30, 8),
                sample("B", 10, 3),
                sample("B", 20, 4)
        ));
        model.replaceCatalog(replacement, null, null, true);
        model.setSamplePageSize(2);
        AlignedWorkspaceSnapshot grown = awaitLoad();
        assertEquals(3, grown.totalAlignedRows());
        assertEquals(3, model.alignedUnionComputationsForTest());
        assertRejected(3, 0, "Valid aligned indexes are 0 to 2.");
    }

    private void assertRejected(long index, int windowStart, String messagePart) {
        AtomicBoolean called = new AtomicBoolean();
        Optional<String> error = model.jumpToIndexAsync(index, snap -> called.set(true));
        assertTrue(error.orElseThrow().contains(messagePart), error.orElseThrow());
        assertFalse(called.get());
        assertEquals(windowStart, model.alignedWindowStart());
        assertEquals(windowStart, model.reusableWorkspaceSnapshot().orElseThrow().windowStartIndex());
    }

    private AlignedWorkspaceSnapshot awaitJump(long index) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AlignedWorkspaceSnapshot> ref = new AtomicReference<>();
        assertTrue(model.jumpToIndexAsync(index, snap -> {
            ref.set(snap);
            latch.countDown();
        }).isEmpty());
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        return ref.get();
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

    private AlignedWorkspaceSnapshot awaitNav(
            java.util.function.Consumer<java.util.function.Consumer<AlignedWorkspaceSnapshot>> call
    ) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AlignedWorkspaceSnapshot> ref = new AtomicReference<>();
        call.accept(snap -> {
            ref.set(snap);
            latch.countDown();
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        return ref.get();
    }

    private void load(String file, List<CanonicalSignal> samples, String... names) {
        model.replaceCatalog(store(file, samples));
        SignalCatalogKey[] keys = new SignalCatalogKey[names.length];
        for (int i = 0; i < names.length; i++) {
            keys[i] = find(names[i]);
        }
        model.addToWorkspace(List.of(keys));
    }

    private SignalCatalogKey find(String name) {
        return model.catalog().entries().stream()
                .filter(entry -> name.equals(entry.signalName()))
                .findFirst()
                .orElseThrow()
                .key();
    }

    private static InMemorySignalCatalogStore store(String file, List<CanonicalSignal> samples) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(file, "bound.dbc", 1_000);
        store.accept(samples);
        return store;
    }

    private static int pages(AlignedWorkspaceSnapshot snap) {
        int pageSize = Math.max(1, snap.pageSize());
        int total = Math.max(0, snap.totalAlignedRows());
        return total <= 0 ? 1 : (total + pageSize - 1) / pageSize;
    }

    private static CanonicalSignal sample(String name, long timestamp, double physical) {
        return new CanonicalSignal(
                name, "Msg", timestamp, 1, 1, physical, (long) physical, "u",
                null, SourceReference.ofLine(1));
    }
}
