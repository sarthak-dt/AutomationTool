package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalBrowserProjectionTest {

    @Test
    void allInstancesIsLosslessAndUniqueNamesKeepsDeterministicFirstChannel() {
        SignalCatalogEntry ch3 = entry("Speed", 3, 0x300, "src-A", "def-A");
        SignalCatalogEntry ch1 = entry("Speed", 1, 0x200, "src-A", "def-A");
        SignalCatalogEntry ch2 = entry("Speed", 2, 0x100, "src-A", "def-A");
        SignalCatalogEntry other = entry("RPM", 3, 0x400, "src-A", "def-A");
        List<SignalCatalogEntry> input = List.of(ch3, ch1, ch2, other);

        List<SignalCatalogEntry> all = SignalBrowserProjection.apply(input, SignalBrowserViewMode.ALL_INSTANCES);
        assertEquals(List.of(1, 2, 3, 3), all.stream().map(SignalCatalogEntry::channel).toList(),
                "default Signal Browser order must be numeric channel ascending");

        List<SignalCatalogEntry> unique = SignalBrowserProjection.apply(
                input, SignalBrowserViewMode.UNIQUE_SIGNAL_NAMES);
        assertEquals(2, unique.size());
        assertEquals(1, unique.stream().filter(e -> e.signalName().equals("Speed")).findFirst().orElseThrow().channel());
        assertEquals(4, input.size(), "projection cannot mutate the physical list");
    }


    @Test
    void uniqueNamesNeverHidesSameNameFromAnotherMeasurementSource() {
        List<SignalCatalogEntry> input = List.of(
                entry("Speed", 1, 0x100, "source-id-A", "def-A"),
                entry("Speed", 2, 0x100, "source-id-B", "def-A"));

        List<SignalCatalogEntry> unique = SignalBrowserProjection.apply(
                input, SignalBrowserViewMode.UNIQUE_SIGNAL_NAMES);

        assertEquals(2, unique.size(),
                "duplicate-name view is scoped per physical measurement source");
    }

    @Test
    void caseOnlyNameDifferenceIsNotHidden() {
        List<SignalCatalogEntry> input = List.of(
                entry("Speed", 1, 0x100, "src-A", "def-A"),
                entry("SPEED", 2, 0x100, "src-A", "def-A"));
        assertEquals(2, SignalBrowserProjection.apply(input, SignalBrowserViewMode.UNIQUE_SIGNAL_NAMES).size());
    }

    @Test
    void duplicateMarkerUsesSameSourceScopedExactNameSemanticsAsHideDuplicates() {
        SignalCatalogEntry a1 = entry("Speed", 1, 0x100, "src-A", "def-A");
        SignalCatalogEntry a2 = entry("Speed", 2, 0x200, "src-A", "def-B");
        SignalCatalogEntry rpm = entry("RPM", 1, 0x300, "src-A", "def-A");
        SignalCatalogEntry b1 = entry("Speed", 1, 0x100, "src-B", "def-A");

        var duplicateKeys = SignalBrowserProjection.duplicatePhysicalKeys(List.of(a1, a2, rpm, b1));

        assertTrue(duplicateKeys.contains(a1.key()));
        assertTrue(duplicateKeys.contains(a2.key()));
        assertFalse(duplicateKeys.contains(rpm.key()));
        assertFalse(duplicateKeys.contains(b1.key()),
                "same signal name in another MF4 source must not be marked duplicate");
    }


    @Test
    void duplicatePeerChannelsExcludeCurrentOccurrenceAndCycleAcrossRows() {
        SignalCatalogEntry ch1 = entry("Speed", 1, 0x100, "src-A", "def-A");
        SignalCatalogEntry ch2 = entry("Speed", 2, 0x200, "src-A", "def-B");
        SignalCatalogEntry ch3 = entry("Speed", 3, 0x300, "src-A", "def-C");
        SignalCatalogEntry rpm = entry("RPM", 1, 0x400, "src-A", "def-A");

        var peers = SignalBrowserProjection.duplicatePeerChannels(List.of(ch3, rpm, ch1, ch2));

        assertEquals(List.of(2, 3), peers.get(ch1.key()));
        assertEquals(List.of(1, 3), peers.get(ch2.key()));
        assertEquals(List.of(1, 2), peers.get(ch3.key()));
        assertFalse(peers.containsKey(rpm.key()));
    }

    private static SignalCatalogEntry entry(
            String signal,
            int channel,
            long canId,
            String source,
            String definition
    ) {
        SignalCatalogKey key = new SignalCatalogKey(
                signal, SignalSourceType.DBC_CAN, "Msg_" + canId, canId, channel, source, definition);
        return new SignalCatalogEntry(key, "km/h", 10, 1L, 10L, 0.0, 100.0, "run.mf4", "veh.dbc");
    }
}
