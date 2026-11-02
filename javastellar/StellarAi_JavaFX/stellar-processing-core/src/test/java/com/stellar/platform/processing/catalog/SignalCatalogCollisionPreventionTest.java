package com.stellar.platform.processing.catalog;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalCatalogCollisionPreventionTest {

    @Test
    void distinctSPrefixNamesDoNotCollapse() {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 100);
        store.accept(List.of(
                sig("S", "OBD2", 0x7E8, 1),
                sig("S01PID0C_EngineRPM", "OBD2", 0x7E8, 2),
                sig("S01PID0D_VehicleSpeed", "OBD2", 0x7E8, 3),
                sig("S01PID05_EngineCoolantTemp", "OBD2", 0x7E8, 4)
        ));
        SignalCatalog catalog = store.snapshot();
        assertEquals(4, catalog.size());
        assertEquals(4, catalog.search("S").size());
        assertEquals(1, catalog.search("VehicleSpeed").size());
        assertEquals("S01PID0D_VehicleSpeed", catalog.search("VehicleSpeed").get(0).signalName());

        SignalCatalogKey a = new SignalCatalogKey(
                "S01PID0C_EngineRPM", SignalSourceType.DBC_CAN, "OBD2", 0x7E8, "run.mf4", "veh.dbc");
        SignalCatalogKey b = new SignalCatalogKey(
                "S01PID0D_VehicleSpeed", SignalSourceType.DBC_CAN, "OBD2", 0x7E8, "run.mf4", "veh.dbc");
        assertNotEquals(a, b);
        assertTrue(catalog.find(a).isPresent());
        assertTrue(catalog.find(b).isPresent());
    }

    @Test
    void nativeAndDbcSameNameRemainDistinctBySourceType() {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("a.mf4", "b.dbc", 50);
        store.accept(List.of(
                sig("Speed", "Engine", 0x100, 10),
                new CanonicalSignal(
                        "Speed",
                        InMemorySignalCatalogStore.MF4_MEASUREMENT_MESSAGE,
                        20L, 0L, 1, 11.0, null, "km/h", null, new SourceReference(1, 0)
                )
        ));
        SignalCatalog catalog = store.snapshot();
        assertEquals(2, catalog.size());
        assertEquals(1, catalog.dbcCanSignalCount());
        assertEquals(1, catalog.nativeMf4SignalCount());
    }

    private static CanonicalSignal sig(String name, String message, long canId, long ts) {
        return new CanonicalSignal(
                name, message, ts, canId, 0, 1.0, 1L, "", null, new SourceReference(1, ts)
        );
    }
}
