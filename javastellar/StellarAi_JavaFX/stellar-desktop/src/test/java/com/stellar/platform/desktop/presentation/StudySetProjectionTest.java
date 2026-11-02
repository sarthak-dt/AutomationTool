package com.stellar.platform.desktop.presentation;

import com.stellar.platform.dbc.model.DbcByteOrder;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcValueType;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.StudyMessageKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudySetProjectionTest {

    @Test
    void combinedProjectionIncludesSignalsFromAllSelectedMessages() {
        ProjectSourceId src = ProjectSourceId.generate();
        StudyMessageEntry a = entry(src, 1, 0x36, "MSG_36", List.of(sig("Signal_A", 0), sig("Signal_B", 8)));
        StudyMessageEntry b = entry(src, 1, 0xF6, "DONNEES_BSI_LENTES_F6", List.of(sig("T_EXT", 47), sig("KM_TOTAL", 23)));
        StudyMessageEntry c = entry(src, 1, 0x201, "MSG_201", List.of(sig("Status", 0)));

        List<CanStudySignalDefinitionView> rows = StudySetProjection.buildFlat(List.of(a, b, c));
        StudySetProjection.Summary summary = StudySetProjection.summarize(List.of(a, b, c));

        assertEquals(3, summary.messageCount());
        assertEquals(5, summary.signalCount());
        assertTrue(summary.singleScope());
        assertTrue(summary.compactLine().contains("3 CAN Messages"));
        assertTrue(summary.compactLine().contains("5 Signal Definitions"));
        assertTrue(summary.compactLine().contains("Ch1"));

        assertEquals(5, rows.size()); // flat signal rows only
        assertTrue(rows.stream().anyMatch(r -> r.canIdHex().equals("0xF6") && r.signalName().equals("T_EXT")));
        assertTrue(rows.stream().anyMatch(r -> r.canIdHex().equals("0xF6") && r.signalName().equals("KM_TOTAL")));
        assertTrue(rows.stream().anyMatch(r -> r.canIdHex().equals("0x36") && r.signalName().equals("Signal_A")));
        assertTrue(rows.stream().anyMatch(r -> r.canIdHex().equals("0x201") && r.signalName().equals("Status")));
    }

    @Test
    void preservesDuplicateSignalNamesAcrossMessages() {
        ProjectSourceId src = ProjectSourceId.generate();
        StudyMessageEntry a = entry(src, 1, 0x201, "MSG_201", List.of(sig("Status", 0)));
        StudyMessageEntry b = entry(src, 1, 0x208, "MSG_208", List.of(sig("Status", 0)));
        List<CanStudySignalDefinitionView> rows = StudySetProjection.buildFlat(List.of(a, b));
        long statusCount = rows.stream().filter(r -> "Status".equals(r.signalName())).count();
        assertEquals(2, statusCount);
    }

    @Test
    void preservesChannelProvenanceForSameCanId() {
        ProjectSourceId src = ProjectSourceId.generate();
        StudyMessageEntry ch1 = entry(src, 1, 0x453, "MSG", List.of(sig("A", 0)));
        StudyMessageEntry ch3 = entry(src, 3, 0x453, "MSG", List.of(sig("B", 0)));
        List<CanStudySignalDefinitionView> rows = StudySetProjection.buildFlat(List.of(ch1, ch3));
        StudySetProjection.Summary summary = StudySetProjection.summarize(List.of(ch1, ch3));
        assertFalse(summary.singleScope());
        assertEquals(2, summary.channelCount());
        assertTrue(summary.compactLine().contains("2 Channels"));
        assertTrue(rows.stream().anyMatch(r -> "Ch1".equals(r.channelLabel())
                && r.studyKey().equals(StudyMessageKey.of(SourceChannelKey.of(src, 1), 0x453, false))));
        assertTrue(rows.stream().anyMatch(r -> "Ch3".equals(r.channelLabel())
                && r.studyKey().equals(StudyMessageKey.of(SourceChannelKey.of(src, 3), 0x453, false))));
    }

    @Test
    void localSearchFiltersAcrossCanIdMessageAndSignalName() {
        ProjectSourceId src = ProjectSourceId.generate();
        StudyMessageEntry a = entry(src, 1, 0xF6, "DONNEES_BSI_LENTES_F6", List.of(sig("T_EXT", 47), sig("KM_TOTAL", 23)));
        StudyMessageEntry b = entry(src, 1, 0x201, "MSG_201", List.of(sig("Status", 0)));
        List<CanStudySignalDefinitionView> filtered = StudySetProjection.buildFlat(List.of(a, b), "km_total");
        assertTrue(filtered.stream().anyMatch(r -> r.signalName().equals("KM_TOTAL")));
        assertFalse(filtered.stream().anyMatch(r -> r.signalName().equals("Status")));
    }

    private static StudyMessageEntry entry(
            ProjectSourceId src, int ch, long canId, String msg, List<DbcSignalDefinitionView> signals) {
        return new StudyMessageEntry(
                StudyMessageKey.of(SourceChannelKey.of(src, ch), canId, false),
                "test.dbc",
                msg,
                DefinitionSourceId.generate(),
                signals
        );
    }

    private static DbcSignalDefinitionView sig(String name, int start) {
        return new DbcSignalDefinitionView(
                name, start, 8, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,
                1, 0, 0, 255, name.equals("KM_TOTAL") ? "km" : "",
                DbcMultiplexRole.NONE, null, List.of());
    }
}
