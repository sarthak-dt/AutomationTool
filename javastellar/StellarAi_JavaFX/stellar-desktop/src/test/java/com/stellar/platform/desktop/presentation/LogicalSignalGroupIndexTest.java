package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogicalSignalGroupIndexTest {

    @Test
    void sameDbcSignalAcrossChannelsAppearsOnceButRetainsBothPhysicalInstances() {
        SignalCatalogEntry ch1 = dbc("VehicleSpeed", "Msg", 0x123, 1, "source-A", "def-A", "km/h");
        SignalCatalogEntry ch2 = dbc("VehicleSpeed", "Msg", 0x123, 2, "source-A", "def-A", "km/h");

        LogicalSignalGroupIndex index = LogicalSignalGroupIndex.build(List.of(ch2, ch1));

        assertEquals(1, index.representatives().size());
        assertEquals(1, index.representatives().getFirst().channel());
        assertEquals(2, index.instanceCount(index.representatives().getFirst().key()));
        assertEquals(List.of(1, 2), index.instances(index.representatives().getFirst().key()).stream()
                .map(SignalCatalogEntry::channel).toList());
    }

    @Test
    void differentDefinitionSourceOrMessageIdentityNeverCollapses() {
        SignalCatalogEntry a = dbc("VehicleSpeed", "MsgA", 0x123, 1, "source-A", "def-A", "km/h");
        SignalCatalogEntry b = dbc("VehicleSpeed", "MsgA", 0x123, 2, "source-A", "def-B", "km/h");
        SignalCatalogEntry c = dbc("VehicleSpeed", "MsgB", 0x124, 3, "source-A", "def-A", "km/h");

        LogicalSignalGroupIndex index = LogicalSignalGroupIndex.build(List.of(a, b, c));

        assertEquals(3, index.representatives().size());
    }

    @Test
    void differentMeasurementSourcesNeverCollapseAcrossTestRuns() {
        SignalCatalogEntry a = dbc("VehicleSpeed", "Msg", 0x123, 1, "source-A", "def-A", "km/h");
        SignalCatalogEntry b = dbc("VehicleSpeed", "Msg", 0x123, 1, "source-B", "def-A", "km/h");

        assertEquals(2, LogicalSignalGroupIndex.build(List.of(a, b)).representatives().size());
    }

    @Test
    void nativeMeasurementChannelsRemainPhysicalRowsEvenWithSameName() {
        SignalCatalogEntry a = nativeEntry("Temperature", 1);
        SignalCatalogEntry b = nativeEntry("Temperature", 2);

        assertEquals(2, LogicalSignalGroupIndex.build(List.of(a, b)).representatives().size());
    }

    private static SignalCatalogEntry dbc(
            String signal, String message, long canId, int channel,
            String sourceId, String definitionId, String unit) {
        SignalCatalogKey key = new SignalCatalogKey(
                signal, SignalSourceType.DBC_CAN, message, canId, channel, sourceId, definitionId);
        return new SignalCatalogEntry(key, unit, 10, 1L, 10L, 0.0, 100.0, "run.mf4", "veh.dbc");
    }

    private static SignalCatalogEntry nativeEntry(String signal, int channel) {
        SignalCatalogKey key = new SignalCatalogKey(
                signal, SignalSourceType.MF4_NATIVE, "MF4_MEASUREMENT", 0, channel, "source-A", "");
        return new SignalCatalogEntry(key, "C", 5, 1L, 5L, 0.0, 1.0, "run.mf4", "");
    }
}
