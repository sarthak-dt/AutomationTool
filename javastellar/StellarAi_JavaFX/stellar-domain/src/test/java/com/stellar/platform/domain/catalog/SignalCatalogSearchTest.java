package com.stellar.platform.domain.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalCatalogSearchTest {

    @Test
    void searchMatchesNameMessageCanIdAndUnit() {
        SignalCatalog catalog = new SignalCatalog(List.of(
                entry("VehicleSpeed", SignalSourceType.DBC_CAN, "OBDResponse", 0x7E8, "km/h"),
                entry("EngineRPM", SignalSourceType.DBC_CAN, "EngineData", 0x100, "rpm"),
                entry("SPEED_OVER_GROUND", SignalSourceType.MF4_NATIVE, "MF4_MEASUREMENT", 0L, "km/h"),
                entry("CoolantTemperature", SignalSourceType.DBC_CAN, "OBDResponse", 0x7E8, "degC")
        ), "run.mf4", "vehicle.dbc").sortedByName();

        assertEquals(4, catalog.size());
        assertEquals(3, catalog.dbcCanSignalCount());
        assertEquals(1, catalog.nativeMf4SignalCount());

        assertEquals(1, catalog.search("vehicle").size());
        assertEquals(2, catalog.search("7e8").size());
        assertEquals(2, catalog.search("", "", "", "km/h").size());
        assertEquals(1, catalog.search("rpm").size());
        assertTrue(catalog.search("ground").get(0).signalName().contains("GROUND"));
    }

    private static SignalCatalogEntry entry(
            String name,
            SignalSourceType type,
            String message,
            long canId,
            String unit
    ) {
        return new SignalCatalogEntry(
                new SignalCatalogKey(name, type, message, canId),
                unit,
                10,
                1L,
                100L,
                0.0,
                100.0,
                "run.mf4",
                "vehicle.dbc"
        );
    }
}
