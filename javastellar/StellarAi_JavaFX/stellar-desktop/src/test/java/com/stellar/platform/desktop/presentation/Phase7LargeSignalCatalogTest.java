package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase7LargeSignalCatalogTest {

    @ParameterizedTest
    @ValueSource(ints = {10_000, 25_000, 50_000})
    void metadataCatalogSearchAndFiltersDoNotTouchWaveforms(int size) {
        MetadataOnlyStore store = catalog(size);
        SignalsScreenModel model = new SignalsScreenModel();
        model.replaceCatalog(store);

        assertEquals(size, model.catalog().entries().size());
        model.setSearchQuery("Sig100");
        List<SignalCatalogEntry> found = model.applyBrowserFilterNow();
        assertTrue(found.size() >= 1 && found.size() < size);
        assertEquals(0, store.sampleQueries.get(), "search must stay metadata-only");

        model.setSearchQuery("");
        model.setBrowserFilter(new SignalBrowserFilter.State(
                SignalBrowserFilter.SourceFilter.DBC, "", "", "", false, false, "run-1.mf4", ""));
        List<SignalCatalogEntry> sourced = model.applyBrowserFilterNow();
        assertTrue(sourced.size() < size);
        assertEquals(0, store.sampleQueries.get());

        model.setBrowserFilter(SignalBrowserFilter.State.none());
        model.setBrowserViewMode(SignalBrowserViewMode.UNIQUE_SIGNAL_NAMES);
        List<SignalCatalogEntry> unique = model.applyBrowserFilterNow();
        assertTrue(unique.size() < size, "duplicate-name view must collapse same-source names");
        assertEquals(0, store.sampleQueries.get());

        SignalCatalogKey first = model.catalog().entries().getFirst().key();
        SignalCatalogKey second = model.catalog().entries().get(1).key();
        model.addToWorkspace(List.of(first, second));
        assertTrue(model.workspaceKeys().contains(first));
        assertTrue(model.workspaceKeys().contains(second));
        List<SignalCatalogEntry> inAnalysis = model.catalog().entries().stream()
                .filter(e -> model.workspaceKeys().contains(e.key()))
                .toList();
        assertTrue(inAnalysis.size() >= 2);
        assertEquals(0, store.sampleQueries.get(), "In Analysis filter must not load samples");
        model.shutdown();
    }

    @Test
    void multiSelectionDoesNotLoadSamplesUntilExplicitPageQuery() {
        MetadataOnlyStore store = catalog(1_000);
        SignalsScreenModel model = new SignalsScreenModel();
        model.replaceCatalog(store);
        List<SignalCatalogKey> keys = model.catalog().entries().subList(0, 8).stream()
                .map(SignalCatalogEntry::key)
                .toList();
        model.addToWorkspace(keys);
        assertEquals(8, model.workspaceKeys().size());
        assertEquals(0, store.sampleQueries.get());
        model.shutdown();
    }

    private static MetadataOnlyStore catalog(int size) {
        List<SignalCatalogEntry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int channel = (i % 8) + 1;
            String name = "Sig" + (i % 250);
            SignalCatalogKey key = new SignalCatalogKey(
                    name, SignalSourceType.DBC_CAN, "Msg" + (i % 40), 0x100 + (i % 40),
                    channel, "run-" + (i % 3) + ".mf4", "veh.dbc");
            entries.add(new SignalCatalogEntry(
                    key, "km/h", 1_000_000L, 0L, 1_000_000L, 0.0, 100.0,
                    key.sourceFile(), key.sourceDatabase()));
        }
        return new MetadataOnlyStore(entries);
    }

    private static final class MetadataOnlyStore implements SignalSeriesStore {
        private final SignalCatalog catalog;
        private final AtomicInteger sampleQueries = new AtomicInteger();

        private MetadataOnlyStore(List<SignalCatalogEntry> entries) {
            this.catalog = new SignalCatalog(List.copyOf(entries), "run.mf4", "veh.dbc");
        }

        @Override
        public SignalCatalog snapshot() {
            return catalog;
        }

        @Override
        public List<SignalSamplePoint> querySamples(SignalCatalogKey key, int offset, int limit) {
            sampleQueries.incrementAndGet();
            return List.of();
        }

        @Override
        public int retainedSampleCount(SignalCatalogKey key) {
            return 0;
        }

        @Override
        public long retainedWindowStartIndex(SignalCatalogKey key) {
            return 0;
        }
    }
}
