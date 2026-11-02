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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Study preview describes one channel + DBC configuration. When that configuration changes the
 * preview stops being current, and the engineer must be told rather than shown stale definitions
 * as if they were confirmed.
 */
class InventoryStudySetStalenessTest {

    private final ProjectSourceId source = ProjectSourceId.generate();
    private final DefinitionSourceId chassis = DefinitionSourceId.generate();
    private final DefinitionSourceId powertrain = DefinitionSourceId.generate();

    @Test
    void routineReprojectionOfUnchangedConfigurationIsNotStale() {
        InventoryStudySet set = new InventoryStudySet();
        set.add(matched(chassis, "Chassis.dbc", "BrakeStatus", 3));

        int changed = set.refreshMetadata(List.of(matched(chassis, "Chassis.dbc", "BrakeStatus", 3)));

        assertEquals(0, changed, "an identical re-projection is not a configuration change");
        assertEquals(0, set.staleCount());
    }

    @Test
    void reassigningTheDbcMarksThePreviewStale() {
        InventoryStudySet set = new InventoryStudySet();
        CanMessageInventoryRow original = matched(chassis, "Chassis.dbc", "BrakeStatus", 3);
        set.add(original);

        int changed = set.refreshMetadata(
                List.of(matched(powertrain, "Powertrain.dbc", "EngineStatus", 3)));

        assertEquals(1, changed);
        assertEquals(1, set.staleCount());
        assertTrue(set.stale(keyOf(original)));
    }

    @Test
    void changingTheSignalSetBehindTheSameDbcMarksThePreviewStale() {
        InventoryStudySet set = new InventoryStudySet();
        set.add(matched(chassis, "Chassis.dbc", "BrakeStatus", 3));

        assertEquals(1, set.refreshMetadata(List.of(matched(chassis, "Chassis.dbc", "BrakeStatus", 5))));
        assertEquals(1, set.staleCount());
    }

    @Test
    void refreshedEntryPreviewsTheNewConfigurationNotTheOldOne() {
        InventoryStudySet set = new InventoryStudySet();
        CanMessageInventoryRow original = matched(chassis, "Chassis.dbc", "BrakeStatus", 3);
        set.add(original);

        set.refreshMetadata(List.of(matched(powertrain, "Powertrain.dbc", "EngineStatus", 4)));

        StudyMessageEntry entry = set.asMap().get(keyOf(original));
        assertEquals("Powertrain.dbc", entry.definitionDisplayName());
        assertEquals("EngineStatus", entry.dbcMessageName());
        assertEquals(4, entry.signalCount());
    }

    @Test
    void acknowledgingAfterRestudyClearsTheStaleMarkButKeepsMembership() {
        InventoryStudySet set = new InventoryStudySet();
        CanMessageInventoryRow original = matched(chassis, "Chassis.dbc", "BrakeStatus", 3);
        set.add(original);
        set.refreshMetadata(List.of(matched(powertrain, "Powertrain.dbc", "EngineStatus", 3)));

        set.acknowledgeStale();

        assertEquals(0, set.staleCount());
        assertFalse(set.stale(keyOf(original)));
        assertEquals(1, set.size(), "acknowledging must not drop the Study identity");
    }

    @Test
    void removingAStaleEntryDoesNotLeaveTheStaleMarkBehind() {
        InventoryStudySet set = new InventoryStudySet();
        CanMessageInventoryRow original = matched(chassis, "Chassis.dbc", "BrakeStatus", 3);
        set.add(original);
        set.refreshMetadata(List.of(matched(powertrain, "Powertrain.dbc", "EngineStatus", 3)));

        assertTrue(set.remove(keyOf(original)));

        assertEquals(0, set.staleCount());
        assertTrue(set.isEmpty());
    }

    @Test
    void zeroSignalConfigurationIsAValidStudyOutcomeAndNeverEnrolled() {
        InventoryStudySet set = new InventoryStudySet();

        assertFalse(set.add(matched(chassis, "Chassis.dbc", "BrakeStatus", 0)),
                "a DBC with no matching signal definitions is a valid empty result, not a crash");
        assertTrue(set.isEmpty());
        assertEquals(0, set.staleCount());
    }

    private StudyMessageKey keyOf(CanMessageInventoryRow row) {
        return StudyMessageKey.of(row.channelKey(), row.canId(), row.extended());
    }

    private CanMessageInventoryRow matched(DefinitionSourceId definitionId,
                                           String definitionName,
                                           String messageName,
                                           int signals) {
        SourceChannelKey channelKey = SourceChannelKey.of(source, 1);
        List<DbcSignalDefinitionView> defs = new ArrayList<>();
        for (int i = 0; i < signals; i++) {
            defs.add(new DbcSignalDefinitionView(
                    "S" + i, i, 8, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,
                    1, 0, 0, 255, "", DbcMultiplexRole.NONE, null, List.of()));
        }
        CanMessageDefinitionMatch enrichment = new CanMessageDefinitionMatch(
                source, channelKey, 0x453L, false, 10,
                InventoryEnrichmentMatchState.MATCHED,
                definitionId, definitionName, null, messageName, 8, defs);
        CanMessageInventory message =
                new CanMessageInventory(new CanMessageKey(1, 0x453L, false), 10, 8, 8, false);
        return CanMessageInventoryRow.enriched(source, message, enrichment);
    }
}
