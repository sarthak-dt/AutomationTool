package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.drive.speed.SpeedConfidence;
import com.stellar.platform.processing.drive.speed.SpeedSourceKind;
import com.stellar.platform.processing.drive.speed.SpeedSourceMapping;
import com.stellar.platform.storage.metadata.SemanticSignalMappingMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleSpeedMappingCodecTest {

    @Test
    void roundTripPreservesSelectedKeyAndKind() {
        SignalCatalogKey key = new SignalCatalogKey(
                "VehSpd", SignalSourceType.DBC_CAN, "ESC", 0x200, 2, "a.mf4", "veh.dbc");
        SpeedSourceMapping mapping = new SpeedSourceMapping(
                1, "a.mf4", "2:abcd", SpeedSourceKind.AUTHORITATIVE_VEHICLE, key, "km/h",
                SpeedConfidence.HIGH, "physical source");
        SemanticSignalMappingMetadata row = VehicleSpeedMappingCodec.toMetadata("p1", mapping);
        SpeedSourceMapping restored = VehicleSpeedMappingCodec.fromMetadata(row).orElseThrow();
        assertEquals(mapping.sourceId(), restored.sourceId());
        assertEquals(mapping.kind(), restored.kind());
        assertEquals(key.signalName(), restored.key().signalName());
        assertEquals(key.channel(), restored.key().channel());
        assertEquals(key.sourceFile(), restored.key().sourceFile());
    }

    @Test
    void hideDuplicatesRepresentativesAreAPresentationSubset() {
        SignalCatalogEntry ch1 = entry("VehSpd", 1);
        SignalCatalogEntry ch2 = entry("VehSpd", 2);
        LogicalSignalGroupIndex grouped = LogicalSignalGroupIndex.build(List.of(ch1, ch2));
        assertEquals(1, grouped.representatives().size());
        assertEquals(2, grouped.instanceCount(ch1.key()));
        assertTrue(grouped.instances(grouped.representatives().getFirst().key()).size() >= 2);
    }

    private static SignalCatalogEntry entry(String name, int channel) {
        SignalCatalogKey key = new SignalCatalogKey(
                name, SignalSourceType.DBC_CAN, "ESC", 0x200, channel, "a.mf4", "veh.dbc");
        return new SignalCatalogEntry(key, "km/h", 10, 0L, 10L, 0.0, 100.0, "a.mf4", "veh.dbc");
    }
}
