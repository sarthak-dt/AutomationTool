package com.stellar.platform.processing.catalog;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemorySignalCatalogStoreTest {

    @Test
    void buildsCatalogAndBoundedSeriesFromStream() {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("a.mf4", "b.dbc", 3);
        List<CanonicalSignal> batch = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            batch.add(new CanonicalSignal(
                    "EngineRPM",
                    "EngineData",
                    i * 1000L,
                    0x100,
                    0,
                    1000.0 + i,
                    (long) i,
                    "rpm",
                    null,
                    new SourceReference(i + 1, i)
            ));
        }
        batch.add(new CanonicalSignal(
                "SPEED_OVER_GROUND",
                InMemorySignalCatalogStore.MF4_MEASUREMENT_MESSAGE,
                50L,
                0L,
                1,
                42.5,
                null,
                "km/h",
                null,
                new SourceReference(1, 10)
        ));
        store.accept(batch);

        SignalCatalog catalog = store.snapshot();
        assertEquals(2, catalog.size());
        assertEquals(1, catalog.dbcCanSignalCount());
        assertEquals(1, catalog.nativeMf4SignalCount());
        assertEquals(5, catalog.find(new SignalCatalogKey(
                "EngineRPM", SignalSourceType.DBC_CAN, "EngineData", 0x100, "a.mf4", "b.dbc")).orElseThrow().sampleCount());

        List<SignalSamplePoint> page = store.querySamples(
                new SignalCatalogKey("EngineRPM", SignalSourceType.DBC_CAN, "EngineData", 0x100, "a.mf4", "b.dbc"),
                0,
                10
        );
        assertEquals(3, page.size());
        assertEquals(2000L, page.get(0).timestampNanos());
        assertEquals(1, catalog.search("rpm").size());
        assertEquals(1, catalog.search("speed").size());
        assertTrue(catalog.search("0x100").get(0).signalName().equals("EngineRPM"));
    }
}
