package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.desktop.presentation.AlignedSampleRow;
import com.stellar.platform.desktop.presentation.AlignedWorkspaceSnapshot;
import com.stellar.platform.desktop.presentation.WorkspaceDisplayMode;
import com.stellar.platform.desktop.presentation.WorkspacePrecision;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceClipboardFormatterTest {

    @Test
    void copiesHeadersAndEveryDisplayedRowAsExcelFriendlyTsv() {
        SignalCatalogEntry speed = entry("VehicleSpeed", 1, 0x123, "km/h");
        SignalCatalogEntry rpm = entry("EngineSpeed", 2, 0x456, "rpm");
        AlignedWorkspaceSnapshot snapshot = new AlignedWorkspaceSnapshot(
                List.of(speed, rpm),
                List.of(
                        new AlignedSampleRow(0, 1_000_000_000L,
                                new Double[] {41.25, 1200.0}, new Long[] {41L, 1200L}),
                        new AlignedSampleRow(1, 1_500_000_000L,
                                new Double[] {42.5, null}, new Long[] {42L, null})
                ),
                0, 2, 5000, null);

        String tsv = WorkspaceClipboardFormatter.toTsv(
                snapshot, WorkspaceDisplayMode.PHYSICAL, WorkspacePrecision.DEC_2,
                true, true, true);
        String[] lines = tsv.split("\\r\\n");

        assertEquals(3, lines.length);
        assertEquals(
                "Index\tTimestamp (s)\tΔt (ms)\tVehicleSpeed [Ch1 | 0x123] (km/h)\tEngineSpeed [Ch2 | 0x456] (rpm)",
                lines[0]);
        assertEquals("0\t1\t—\t41.25\t1200.00", lines[1]);
        assertEquals("1\t1.500000\t500\t42.50\t—", lines[2]);
    }


    @Test
    void headersDisambiguateDifferentSourcesAndDbcsWithoutChangingValues() {
        SignalCatalogEntry a = entry(
                "VehicleSpeed", 1, 0x123, "km/h",
                "source-id-A", "def-id-A", "run-a.mf4", "body.dbc");
        SignalCatalogEntry b = entry(
                "VehicleSpeed", 2, 0x456, "km/h",
                "source-id-B", "def-id-B", "run-b.mf4", "powertrain.dbc");
        AlignedWorkspaceSnapshot snapshot = new AlignedWorkspaceSnapshot(
                List.of(a, b),
                List.of(new AlignedSampleRow(0, 10L,
                        new Double[] {12.0, 13.0}, new Long[] {12L, 13L})),
                0, 1, 5000, null);

        String tsv = WorkspaceClipboardFormatter.toTsv(
                snapshot, WorkspaceDisplayMode.PHYSICAL, WorkspacePrecision.AUTO,
                true, true, false);

        assertTrue(tsv.contains(
                "VehicleSpeed [Ch1 | 0x123] [DBC: body.dbc] [Source: run-a.mf4] (km/h)"));
        assertTrue(tsv.contains(
                "VehicleSpeed [Ch2 | 0x456] [DBC: powertrain.dbc] [Source: run-b.mf4] (km/h)"));
        assertTrue(tsv.contains("12"));
        assertTrue(tsv.contains("13"));
    }

    @Test
    void rawModeUsesRawValuesWithoutChangingMissingSemantics() {
        SignalCatalogEntry speed = entry("VehicleSpeed", 1, 0x123, "km/h");
        AlignedWorkspaceSnapshot snapshot = new AlignedWorkspaceSnapshot(
                List.of(speed),
                List.of(new AlignedSampleRow(0, 1L, new Double[] {12.5}, new Long[] {99L})),
                0, 1, 5000, null);

        String tsv = WorkspaceClipboardFormatter.toTsv(
                snapshot, WorkspaceDisplayMode.RAW, WorkspacePrecision.AUTO,
                false, true, false);
        assertTrue(tsv.startsWith("Timestamp (s)\tVehicleSpeed [Ch1 | 0x123] (km/h)\r\n"));
        assertTrue(tsv.contains("\t99\r\n"));
    }

    private static SignalCatalogEntry entry(String name, int channel, long canId, String unit) {
        return entry(name, channel, canId, unit,
                "src-A", "def-A", "run.mf4", "veh.dbc");
    }

    private static SignalCatalogEntry entry(
            String name,
            int channel,
            long canId,
            String unit,
            String sourceIdentity,
            String definitionIdentity,
            String sourceDisplayName,
            String dbcDisplayName
    ) {
        SignalCatalogKey key = new SignalCatalogKey(
                name, SignalSourceType.DBC_CAN, "Msg", canId, channel,
                sourceIdentity, definitionIdentity);
        return new SignalCatalogEntry(
                key, unit, 2, 1L, 2L, null, null, sourceDisplayName, dbcDisplayName);
    }
}
