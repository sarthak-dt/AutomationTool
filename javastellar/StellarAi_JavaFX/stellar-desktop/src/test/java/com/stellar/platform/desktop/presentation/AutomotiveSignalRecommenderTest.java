package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomotiveSignalRecommenderTest {

    @Test
    void recommendsOnlyHighConfidenceVehicleSignals() {
        SignalCatalogEntry speed = entry("VehicleSpeed", "ABS");
        SignalCatalogEntry rpm = entry("EngineRPM", "ECM");
        SignalCatalogEntry checksum = entry("Checksum", "ABS");
        SignalCatalogEntry random = entry("CabinLight", "BCM");
        List<AutomotiveSignalRecommender.Recommendation> recs =
                AutomotiveSignalRecommender.recommend(List.of(speed, rpm, checksum, random));
        Set<String> names = recs.stream().map(r -> r.entry().signalName()).collect(java.util.stream.Collectors.toSet());
        assertTrue(names.contains("VehicleSpeed"));
        assertTrue(names.contains("EngineRPM"));
        assertEquals(false, names.contains("Checksum"));
        assertEquals(false, names.contains("CabinLight"));
    }

    private static SignalCatalogEntry entry(String name, String message) {
        return new SignalCatalogEntry(
                new SignalCatalogKey(name, SignalSourceType.DBC_CAN, message, 1, 1, "run.mf4", "veh.dbc"),
                "", 10, 0L, 1L, 0.0, 1.0, "run.mf4", "veh.dbc");
    }
}
