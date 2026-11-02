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
import com.stellar.platform.domain.processing.plan.ProjectChannelSelection;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Study Set (CAN-message metadata analysis) must not couple to Processing Selection
 * (source-channel decode jobs).
 */
class ProcessingSelectionStudySetIndependenceTest {

    @Test
    void studySetEntriesDoNotPopulateProcessingSelection() {
        InventoryStudySet study = new InventoryStudySet();
        ProjectChannelSelection processing = new ProjectChannelSelection();
        ProjectSourceId src = ProjectSourceId.generate();

        assertEquals(1, study.addAll(List.of(matchedRow(src, 1, 0x7E8, 2))));
        assertEquals(1, study.size());
        assertTrue(processing.isEmpty());

        processing.select(SourceChannelRef.of(src.value(), 1));
        assertEquals(1, processing.size());
        assertEquals(1, study.size());
        assertTrue(study.contains(StudyMessageKey.of(SourceChannelKey.of(src, 1), 0x7E8, false)));

        processing.clear();
        assertTrue(processing.isEmpty());
        assertEquals(1, study.size());
    }

    private static CanMessageInventoryRow matchedRow(ProjectSourceId src, int ch, long id, int signals) {
        SourceChannelKey key = SourceChannelKey.of(src, ch);
        java.util.ArrayList<DbcSignalDefinitionView> defs = new java.util.ArrayList<>();
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
}

