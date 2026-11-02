package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalInspectorPresentationTest {

    @Test
    void dbcSignalShowsCanSectionWithoutInventedDlc() {
        SignalCatalogEntry entry = new SignalCatalogEntry(
                new SignalCatalogKey("EngineRPM", SignalSourceType.DBC_CAN, "OBD2", 0x7E8L, "run.mf4", "veh.dbc"),
                "rpm",
                17_972L,
                241_250_000L,
                1_799_764_000_000L,
                800.0,
                4500.0,
                "run.mf4",
                "veh.dbc"
        );
        List<SignalInspectorPresentation.Section> sections = SignalInspectorPresentation.sections(entry);
        assertTrue(SignalInspectorPresentation.showsCanMessageSection(entry));
        SignalInspectorPresentation.Section signal = sections.stream()
                .filter(s -> s.title().equals("Signal Information"))
                .findFirst()
                .orElseThrow();
        assertTrue(signal.rows().stream().anyMatch(r -> r.label().equals("Name") && r.value().equals("EngineRPM")));
        assertTrue(signal.rows().stream().anyMatch(r -> r.label().equals("Unit") && r.value().equals("rpm")));
        SignalInspectorPresentation.Section can = sections.stream()
                .filter(s -> s.title().startsWith("DBC"))
                .findFirst()
                .orElseThrow();
        assertTrue(can.rows().stream().anyMatch(r -> r.label().equals("Channel") && r.value().equals("Channel 0")));
        assertTrue(can.rows().stream().anyMatch(r -> r.label().equals("Message") && r.value().equals("OBD2")));
        assertTrue(can.rows().stream().anyMatch(r -> r.label().equals("CAN ID") && r.value().equals("0x7E8")));
        assertFalse(can.rows().stream().anyMatch(r -> r.label().equals("DLC")));

        SignalInspectorPresentation.Section tech = sections.stream()
                .filter(s -> s.title().startsWith("Statistics"))
                .findFirst()
                .orElseThrow();
        assertTrue(tech.rows().stream().anyMatch(r -> r.label().equals("Minimum")));
        assertTrue(tech.rows().stream().anyMatch(r -> r.label().equals("Maximum")));
    }

    @Test
    void mf4NativeHidesCanSection() {
        SignalCatalogEntry entry = new SignalCatalogEntry(
                new SignalCatalogKey("Speed", SignalSourceType.MF4_NATIVE, "MF4_MEASUREMENT", 0L, "run.mf4", ""),
                "km/h",
                100L,
                1_000_000_000L,
                2_000_000_000L,
                null,
                null,
                "run.mf4",
                ""
        );
        assertFalse(SignalInspectorPresentation.showsCanMessageSection(entry));
        List<SignalInspectorPresentation.Section> sections = SignalInspectorPresentation.sections(entry);
        assertTrue(sections.stream().noneMatch(s -> s.title().startsWith("DBC")));
        SignalInspectorPresentation.Section statistics = sections.stream()
                .filter(s -> s.title().equals("Statistics"))
                .findFirst()
                .orElseThrow();
        assertTrue(statistics.rows().stream().anyMatch(r ->
                r.label().equals("Numeric range") && r.value().equals("n/a")));
        SignalInspectorPresentation.Section signal = sections.stream()
                .filter(s -> s.title().equals("Signal Information"))
                .findFirst()
                .orElseThrow();
        assertTrue(signal.rows().stream().anyMatch(r -> r.label().equals("Unit") && r.value().equals("km/h")));
    }

    @Test
    void inspectorShowsMeasurementNotSourceFileForASingleRecording() {
        SignalCatalogEntry entry = new SignalCatalogEntry(
                new SignalCatalogKey("EngineRPM", SignalSourceType.DBC_CAN, "OBD2", 0x7E8L, "run.mf4", "veh.dbc"),
                "rpm",
                10L,
                1_000_000_000L,
                2_000_000_000L,
                800.0,
                4500.0,
                "run.mf4",
                "veh.dbc"
        );
        List<SignalInspectorPresentation.Section> sections = SignalInspectorPresentation.sections(entry, List.of(entry));
        assertTrue(sections.stream().anyMatch(s -> s.title().equals("Measurement")));
        assertTrue(sections.stream().noneMatch(s -> s.title().equals("Source Information")));
        assertTrue(sections.stream().flatMap(s -> s.rows().stream())
                .anyMatch(r -> r.label().equals("Measurement") && r.value().equals("run.mf4")));
        assertTrue(sections.stream().flatMap(s -> s.rows().stream())
                .noneMatch(r -> r.label().equals("Source File")));
    }

    @Test
    void durationFormatsFromAvailableTimestamps() {
        assertEquals("1:00.000", SignalInspectorPresentation.formatDuration(60_000_000_000L));
    }

    @Test
    void physicalDisplayIsCompactWhileExactValueIsPreserved() {
        double noisy = 18.4543730999999;
        String display = SignalInspectorPresentation.formatPhysicalDisplay(noisy);
        assertTrue(display.length() < Double.toString(noisy).length() || display.equals("18.454373"));
        assertFalse(display.contains("..."));

        SignalCatalogEntry entry = new SignalCatalogEntry(
                new SignalCatalogKey("EngineRPM", SignalSourceType.DBC_CAN, "OBD2", 0x7E8L, "run.mf4", "veh.dbc"),
                "rpm",
                10L,
                1_000_000_000L,
                2_000_000_000L,
                noisy,
                4500.0,
                "run.mf4",
                "veh.dbc"
        );
        SignalInspectorPresentation.Row min = SignalInspectorPresentation.sections(entry).stream()
                .flatMap(s -> s.rows().stream())
                .filter(r -> "Minimum".equals(r.label()))
                .findFirst()
                .orElseThrow();
        assertEquals(display, min.value());
        assertEquals(Double.toString(noisy), min.exactValue());
        assertTrue(min.copyable());
    }
}
