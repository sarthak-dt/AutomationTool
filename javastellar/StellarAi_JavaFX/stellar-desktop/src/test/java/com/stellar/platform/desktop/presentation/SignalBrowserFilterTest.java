package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalBrowserFilterTest {

    @Test
    void filtersByQuerySourceAndFavorites() {
        SignalCatalogEntry dbc = entry("RPM", SignalSourceType.DBC_CAN, "Engine", 0x100, "rpm");
        SignalCatalogEntry nativeSig = entry("GEOID", SignalSourceType.MF4_NATIVE, "MF4_MEASUREMENT", 0L, "");
        List<SignalCatalogEntry> all = List.of(dbc, nativeSig);

        assertEquals(1, SignalBrowserFilter.apply(
                all, "rpm", SignalBrowserFilter.State.none(), Set.of()).size());
        assertEquals(1, SignalBrowserFilter.apply(
                all, "", new SignalBrowserFilter.State(SignalBrowserFilter.SourceFilter.DBC, "", "", "", false, false),
                Set.of()).size());
        assertEquals(1, SignalBrowserFilter.apply(
                all, "", new SignalBrowserFilter.State(SignalBrowserFilter.SourceFilter.ALL, "", "", "", true, false),
                Set.of(nativeSig.key())).size());
        assertEquals("DBC", SignalBrowserFilter.sourceBadge(SignalSourceType.DBC_CAN));
        assertEquals("MF4", SignalBrowserFilter.sourceBadge(SignalSourceType.MF4_NATIVE));

        SignalCatalogEntry derived = entry("CalcSpeed", SignalSourceType.ANALYSIS_DERIVED, "Derived", 0L, "km/h");
        assertEquals(1, SignalBrowserFilter.apply(
                List.of(dbc, nativeSig, derived),
                "",
                new SignalBrowserFilter.State(SignalBrowserFilter.SourceFilter.DERIVED, "", "", "", false, false),
                Set.of()).size());
        assertEquals("Derived", SignalBrowserFilter.SourceFilter.DERIVED.label());
    }

    @Test
    void recentOnlyCombinesWithSearchAndSource() {
        SignalCatalogEntry speed = entry("VehicleSpeed", SignalSourceType.DBC_CAN, "Chassis", 0x200, "km/h");
        SignalCatalogEntry rpm = entry("EngineRPM", SignalSourceType.DBC_CAN, "Engine", 0x100, "rpm");
        SignalCatalogEntry temp = entry("CoolantTemp", SignalSourceType.MF4_NATIVE, "MF4", 0L, "C");
        List<SignalCatalogEntry> all = List.of(speed, rpm, temp);

        SignalBrowserFilter.State recentDbc = new SignalBrowserFilter.State(
                SignalBrowserFilter.SourceFilter.DBC, "", "", "", false, true);
        List<SignalCatalogEntry> filtered = SignalBrowserFilter.apply(
                all, "Speed", recentDbc, Set.of(), Set.of(speed.key(), temp.key()));
        assertEquals(1, filtered.size());
        assertEquals("VehicleSpeed", filtered.getFirst().signalName());
    }

    @Test
    void searchFindsUnknownSignalEvenWhenUngrouped() {
        SignalCatalogEntry unknown = entry("TurboBoostPressure_QX9", SignalSourceType.DBC_CAN, "ECM", 0x220, "kPa");
        List<SignalCatalogEntry> found = SignalBrowserFilter.apply(
                List.of(unknown), "TurboBoost", SignalBrowserFilter.State.none(), Set.of());
        assertEquals(1, found.size());
        assertEquals("TurboBoostPressure_QX9", found.getFirst().signalName());
    }

    @Test
    void structuredFilterCountIgnoresFavoritesAndRecent() {
        SignalBrowserFilter.State state = new SignalBrowserFilter.State(
                SignalBrowserFilter.SourceFilter.DBC, "Engine", "", "100", true, true);
        assertEquals(3, state.structuredFilterCount());
        assertEquals(0, SignalBrowserFilter.State.none().structuredFilterCount());

        SignalBrowserFilter.State withFiles = new SignalBrowserFilter.State(
                SignalBrowserFilter.SourceFilter.ALL, "", "", "", false, false, "run.mf4", "veh.dbc");
        assertEquals(2, withFiles.structuredFilterCount());
        assertEquals(1, withFiles.optionalFilterCount());
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
                3L,
                null,
                null,
                null,
                null,
                "file.mf4",
                ""
        );
    }
}
