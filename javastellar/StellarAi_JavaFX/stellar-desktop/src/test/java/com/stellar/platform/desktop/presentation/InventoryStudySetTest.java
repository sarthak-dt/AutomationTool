package com.stellar.platform.desktop.presentation;

import com.stellar.platform.dbc.model.DbcByteOrder;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcValueType;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.StudyMessageKey;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryStudySetTest {

    @Test
    void selectsOnlyMatchedWithSignalsAndIsIdempotent() {
        InventoryStudySet set = new InventoryStudySet();
        ProjectSourceId src = ProjectSourceId.generate();
        CanMessageInventoryRow matched = matchedRow(src, 1, 0x7E8, 3);
        CanMessageInventoryRow unmatched = unmatchedRow(src, 1, 0x316);
        CanMessageInventoryRow noSignals = matchedRow(src, 1, 0x100, 0);

        assertEquals(1, set.addAll(List.of(matched, unmatched, noSignals)));
        assertEquals(1, set.size());
        assertTrue(set.contains(matched));
        assertFalse(set.contains(unmatched));
        assertEquals(0, set.addAll(List.of(matched)));
        assertEquals(1, set.size());
    }

    @Test
    void preservesChannelProvenanceForSameCanId() {
        InventoryStudySet set = new InventoryStudySet();
        ProjectSourceId src = ProjectSourceId.generate();
        CanMessageInventoryRow ch1 = matchedRow(src, 1, 0x453, 6);
        CanMessageInventoryRow ch3 = matchedRow(src, 3, 0x453, 6);
        assertEquals(2, set.addAll(List.of(ch1, ch3)));
        assertTrue(set.contains(StudyMessageKey.of(SourceChannelKey.of(src, 1), 0x453, false)));
        assertTrue(set.contains(StudyMessageKey.of(SourceChannelKey.of(src, 3), 0x453, false)));
    }

    @Test
    void clearEmptiesSet() {
        InventoryStudySet set = new InventoryStudySet();
        ProjectSourceId src = ProjectSourceId.generate();
        set.add(matchedRow(src, 1, 0x7E8, 2));
        set.clear();
        assertTrue(set.isEmpty());
    }

    @Test
    void removeAllClearsOnlySuppliedIdentities() {
        InventoryStudySet set = new InventoryStudySet();
        ProjectSourceId src = ProjectSourceId.generate();
        CanMessageInventoryRow keep = matchedRow(src, 1, 0x7E8, 2);
        CanMessageInventoryRow drop = matchedRow(src, 1, 0x100, 2);
        set.addAll(List.of(keep, drop));
        assertEquals(1, set.removeAll(List.of(drop)));
        assertTrue(set.contains(keep));
        assertFalse(set.contains(drop));
        assertEquals(1, set.size());
    }

    private static CanMessageInventoryRow matchedRow(ProjectSourceId src, int ch, long id, int signals) {
        SourceChannelKey key = SourceChannelKey.of(src, ch);
        List<DbcSignalDefinitionView> defs = new java.util.ArrayList<>();
        for (int i = 0; i < signals; i++) {
            defs.add(new DbcSignalDefinitionView(
                    "S" + i, i, 8, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,
                    1, 0, 0, 255, "", DbcMultiplexRole.NONE, null, List.of()));
        }
        CanMessageDefinitionMatch enrichment = new CanMessageDefinitionMatch(
                src, key, id, false, 10,
                InventoryEnrichmentMatchState.MATCHED,
                DefinitionSourceId.generate(), "test.dbc", null, "MSG", 8, defs);
        CanMessageInventory msg = new CanMessageInventory(new CanMessageKey(ch, id, false), 10, 8, 8, false);
        return CanMessageInventoryRow.enriched(src, msg, enrichment);
    }

    private static CanMessageInventoryRow unmatchedRow(ProjectSourceId src, int ch, long id) {
        SourceChannelKey key = SourceChannelKey.of(src, ch);
        CanMessageDefinitionMatch enrichment = new CanMessageDefinitionMatch(
                src, key, id, false, 10,
                InventoryEnrichmentMatchState.UNMATCHED,
                null, "", null, null, null, List.of());
        CanMessageInventory msg = new CanMessageInventory(new CanMessageKey(ch, id, false), 10, 8, 8, false);
        return CanMessageInventoryRow.enriched(src, msg, enrichment);
    }
}
