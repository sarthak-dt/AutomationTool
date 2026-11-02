package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalsSelectionPresentationTest {

    @Test
    void title_noneWhenEmpty() {
        assertEquals(SignalsSelectionPresentation.NONE_TITLE, SignalsSelectionPresentation.selectedTitle(List.of()));
        assertEquals(SignalsSelectionPresentation.NONE_TITLE, SignalsSelectionPresentation.selectedTitle(null));
    }

    @Test
    void title_updatesWithSelectedSignalName() {
        SignalCatalogEntry a = entry("GEOID_SEPARATION", SignalSourceType.MF4_NATIVE, "MF4_MEASUREMENT", 0L, "");
        SignalCatalogEntry b = entry("S01PID0C_EngineRPM", SignalSourceType.DBC_CAN, "OBD2", 0x7E8L, "rpm");
        assertEquals("Selected Signal: GEOID_SEPARATION", SignalsSelectionPresentation.selectedTitle(List.of(a)));
        assertEquals("Selected Signal: S01PID0C_EngineRPM", SignalsSelectionPresentation.selectedTitle(List.of(b)));
    }

    @Test
    void summary_usesCatalogMetadataAndShowingRange() {
        SignalCatalogEntry nativeSig = new SignalCatalogEntry(
                new SignalCatalogKey("GEOID_SEPARATION", SignalSourceType.MF4_NATIVE, "MF4_MEASUREMENT", 0L),
                "",
                17_972L,
                1_000_000_000L,
                2_000_000_000L,
                1.0,
                2.0,
                "OBD2_Data.mf4",
                ""
        );
        List<SignalSamplePoint> samples = List.of(
                new SignalSamplePoint(1L, 1.0, null, null),
                new SignalSamplePoint(2L, 2.0, null, null)
        );
        // page at offset 0 with pageSize 5000 but only 2 samples present in snapshot for this unit test
        SignalSamplePageSnapshot page = new SignalSamplePageSnapshot(
                nativeSig.key(), samples, 0, 5_000, 17_972L, 17_972L, 0L);
        String summary = SignalsSelectionPresentation.selectedSummary(nativeSig, page);
        assertFalse(summary.contains("Signal: GEOID_SEPARATION"));
        assertTrue(summary.contains("Unit: " + SignalsSelectionPresentation.EMPTY_META));
        assertTrue(summary.contains("Source: MF4_NATIVE"));
        assertTrue(summary.contains("CAN ID: " + SignalsSelectionPresentation.EMPTY_META));
        assertTrue(summary.contains("Samples: 17,972"));
        assertTrue(summary.contains("Measurement: OBD2_Data.mf4"));
        assertFalse(summary.contains("0x0"));
        assertEquals("Selected Signal: GEOID_SEPARATION", SignalsSelectionPresentation.selectedTitle(List.of(nativeSig)));
        String status = SignalsSelectionPresentation.pageStatus(page);
        assertTrue(status.contains("Showing 0"));
        assertTrue(status.contains("of 17,972"));
    }

    @Test
    void globalIndex_isZeroBasedAcrossPages() {
        assertEquals(0L, SignalsSelectionPresentation.globalIndex(0, 0, 0));
        assertEquals(4999L, SignalsSelectionPresentation.globalIndex(0, 0, 4999));
        assertEquals(5000L, SignalsSelectionPresentation.globalIndex(0, 5000, 0));
        assertEquals(17971L, SignalsSelectionPresentation.globalIndex(0, 15_000, 2_971));
    }

    @Test
    void summary_emptySelectionIsBlank() {
        assertEquals("", SignalsSelectionPresentation.selectedSummary(List.of()));
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
