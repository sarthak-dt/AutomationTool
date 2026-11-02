package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Critical paging regression: page boundaries must be consecutive with no gaps/duplicates.
 */
class SignalSamplePagingBoundaryTest {

    private SignalsScreenModel model;

    @AfterEach
    void tearDown() {
        if (model != null) {
            model.shutdown();
        }
    }

    @Test
    void pageBoundaryPreservesCanonicalOrderWithoutGapsOrDuplicates() throws Exception {
        int pageSize = 10;
        int total = 25; // 3 pages: 0-9, 10-19, 20-24
        model = new SignalsScreenModel();
        model.setSamplePageSize(pageSize);

        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("t.mf4", "t.dbc", 1_000);
        store.accept(syntheticSeries("SIG_PAGE", total));
        model.replaceCatalog(store);

        SignalCatalogKey key = store.snapshot().entries().getFirst().key();

        SignalSamplePageSnapshot page1 = load(model, key, 0);
        assertEquals(pageSize, page1.samples().size());
        assertEquals(0L, page1.firstGlobalIndex());
        assertEquals(9L, page1.lastGlobalIndex());
        assertEquals(1, page1.pageNumber());
        assertEquals(3, page1.pageCount());

        SignalSamplePoint lastPage1 = page1.samples().get(pageSize - 1);
        assertEquals(9L * 1_000_000_000L, lastPage1.timestampNanos());
        assertEquals(9.0, lastPage1.physicalValue(), 0.0);
        assertEquals(9L, lastPage1.rawValue());

        SignalSamplePageSnapshot page2 = load(model, key, page1.nextPageOffset());
        assertEquals(10L, page2.firstGlobalIndex());
        assertEquals(page1.lastGlobalIndex() + 1, page2.firstGlobalIndex());
        SignalSamplePoint firstPage2 = page2.samples().getFirst();
        assertEquals(10L * 1_000_000_000L, firstPage2.timestampNanos());
        assertEquals(10.0, firstPage2.physicalValue(), 0.0);
        assertEquals(10L, firstPage2.rawValue());

        // Walk all pages: no missing/duplicate indexes; timestamps strictly increasing.
        Set<Long> seen = new HashSet<>();
        List<Long> indexes = new ArrayList<>();
        SignalSamplePageSnapshot page = load(model, key, 0);
        while (true) {
            for (int i = 0; i < page.samples().size(); i++) {
                long g = page.globalIndex(i);
                assertTrue(seen.add(g), "duplicate index " + g);
                indexes.add(g);
                assertEquals(g * 1_000_000_000L, page.samples().get(i).timestampNanos());
                assertEquals((double) g, page.samples().get(i).physicalValue(), 0.0);
            }
            if (!page.hasNext()) {
                break;
            }
            page = load(model, key, page.nextPageOffset());
        }
        assertEquals(total, indexes.size());
        for (int i = 0; i < total; i++) {
            assertEquals(i, indexes.get(i).intValue());
        }
        assertEquals(total - 1L, page.lastGlobalIndex());
        assertEquals(total % pageSize, page.samples().size()); // final partial page
    }

    @Test
    void previousNextAndJumpToIndexNavigateDeterministically() throws Exception {
        int pageSize = 7;
        int total = 23;
        model = new SignalsScreenModel();
        model.setSamplePageSize(pageSize);
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("t.mf4", "t.dbc", 1_000);
        store.accept(syntheticSeries("NAV", total));
        model.replaceCatalog(store);
        SignalCatalogKey key = store.snapshot().entries().getFirst().key();

        SignalSamplePageSnapshot first = load(model, key, 0);
        assertFalse(first.hasPrevious());
        assertTrue(first.hasNext());

        SignalSamplePageSnapshot second = load(model, key, first.nextPageOffset());
        assertEquals(7L, second.firstGlobalIndex());
        SignalSamplePageSnapshot back = load(model, key, second.previousPageOffset());
        assertEquals(first.firstGlobalIndex(), back.firstGlobalIndex());
        assertEquals(first.samples().getFirst().timestampNanos(), back.samples().getFirst().timestampNanos());

        SignalSamplePageSnapshot last = load(model, key, first.lastPageOffset());
        assertEquals(21L, last.firstGlobalIndex());
        assertEquals(22L, last.lastGlobalIndex());
        assertFalse(last.hasNext());

        long jump = 15L;
        int offset = SignalSamplePageSnapshot.pageOffsetForGlobalIndex(
                jump, last.windowStartIndex(), last.retainedSamples(), pageSize);
        SignalSamplePageSnapshot jumped = load(model, key, offset);
        assertTrue(jumped.firstGlobalIndex() <= jump && jump <= jumped.lastGlobalIndex());
        int row = (int) (jump - jumped.windowStartIndex() - jumped.pageOffset());
        assertEquals(jump * 1_000_000_000L, jumped.samples().get(row).timestampNanos());
        assertEquals((double) jump, jumped.samples().get(row).physicalValue(), 0.0);
    }

    @Test
    void globalIndexIsZeroBased() {
        assertEquals(0L, SignalsSelectionPresentation.globalIndex(0, 0, 0));
        assertEquals(4999L, SignalsSelectionPresentation.globalIndex(0, 0, 4999));
        assertEquals(5000L, SignalsSelectionPresentation.globalIndex(0, 5000, 0));
        assertEquals(5000L, SignalSamplePageSnapshot.pageOffsetForGlobalIndex(5000, 0, 17972, 5000));
    }

    private static SignalSamplePageSnapshot load(SignalsScreenModel model, SignalCatalogKey key, int offset)
            throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<SignalSamplePageSnapshot> ref = new AtomicReference<>();
        model.loadSamplesPageAsync(List.of(key), offset, page -> {
            ref.set(page);
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        return ref.get();
    }

    private static List<CanonicalSignal> syntheticSeries(String name, int total) {
        List<CanonicalSignal> batch = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            batch.add(new CanonicalSignal(
                    name,
                    "MSG",
                    i * 1_000_000_000L,
                    0x100,
                    0,
                    (double) i,
                    (long) i,
                    "u",
                    null,
                    SourceReference.ofLine(i + 1)
            ));
        }
        return batch;
    }
}
