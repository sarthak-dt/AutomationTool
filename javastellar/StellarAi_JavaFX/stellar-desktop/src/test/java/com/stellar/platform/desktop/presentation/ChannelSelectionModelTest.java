package com.stellar.platform.desktop.presentation;

import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.ProcessingSelection;
import com.stellar.platform.domain.processing.SelectedChannelRef;
import com.stellar.platform.processing.inventory.CanDefinitionMatchingService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelSelectionModelTest {

    private final CanDefinitionMatchingService matchingService = new CanDefinitionMatchingService();

    @Test
    void noChannelSelectedByDefault() {
        ChannelSelectionModel model = new ChannelSelectionModel();
        CanSourceInventory inventory = inventory(Path.of("a.mf4"), channel(1), channel(2));
        model.bindSource("f1", Path.of("a.mf4"), inventory);
        assertFalse(model.hasSelection());
        assertTrue(model.toProcessingSelection().isEmpty());
    }

    @Test
    void oneAndMultipleChannelSelection() {
        ChannelSelectionModel model = new ChannelSelectionModel();
        CanSourceInventory inventory = inventory(Path.of("a.mf4"), channel(1), channel(2), channel(3));
        model.bindSource("f1", Path.of("a.mf4"), inventory);
        model.select(2, true);
        assertEquals(1, model.selectedCount());
        assertTrue(model.isSelected(2));
        model.select(3, true);
        assertEquals(2, model.selectedCount());
        assertTrue(model.toProcessingSelection().includes(
                ProcessingSelection.sourceIdentity(Path.of("a.mf4")), 2));
        assertTrue(model.toProcessingSelection().includes(
                ProcessingSelection.sourceIdentity(Path.of("a.mf4")), 3));
    }

    @Test
    void selectAllAndClearSelection() {
        ChannelSelectionModel model = new ChannelSelectionModel();
        CanSourceInventory inventory = inventory(Path.of("a.mf4"), channel(1), channel(2));
        model.bindSource("f1", Path.of("a.mf4"), inventory);
        model.selectAll(inventory);
        assertEquals(2, model.selectedCount());
        model.clearSelection();
        assertFalse(model.hasSelection());
    }

    @Test
    void selectedChannelIdentityIncludesSource() {
        String sourceA = ProcessingSelection.sourceIdentity(Path.of("fileA.mf4"));
        String sourceB = ProcessingSelection.sourceIdentity(Path.of("fileB.mf4"));
        SelectedChannelRef a1 = SelectedChannelRef.of(sourceA, 1);
        SelectedChannelRef b1 = SelectedChannelRef.of(sourceB, 1);
        assertNotEquals(a1, b1);
        ProcessingSelection selection = ProcessingSelection.of(List.of(a1));
        assertTrue(selection.includes(sourceA, 1));
        assertFalse(selection.includes(sourceB, 1));
    }

    @Test
    void dbcChangeDoesNotCorruptSelectionState() {
        ChannelSelectionModel model = new ChannelSelectionModel();
        CanSourceInventory inventory = inventory(Path.of("a.mf4"), channel(1), channel(2));
        model.bindSource("f1", Path.of("a.mf4"), inventory);
        model.select(1, true);
        SourceInventoryMatchState match = match(inventory, database(message(0x10, "M")));
        assertTrue(model.isSelected(1));
        model.bindSource("f1", Path.of("a.mf4"), inventory);
        assertTrue(model.isSelected(1));
        assertEquals(1, model.summarize(inventory, match).selectedChannels());
    }

    @Test
    void sourceInventoryUnchangedBySelection() {
        ChannelSelectionModel model = new ChannelSelectionModel();
        CanSourceInventory inventory = inventory(Path.of("a.mf4"), channel(1), channel(2));
        int before = inventory.channels().size();
        model.bindSource("f1", Path.of("a.mf4"), inventory);
        model.selectAll(inventory);
        assertEquals(before, inventory.channels().size());
        assertEquals(2, inventory.channels().getFirst().uniqueMessageCount());
    }

    @Test
    void changingSelectionAfterResultRequiresReprocessing() {
        ChannelSelectionModel model = new ChannelSelectionModel();
        CanSourceInventory inventory = inventory(Path.of("a.mf4"), channel(1), channel(2));
        model.bindSource("f1", Path.of("a.mf4"), inventory);
        model.select(1, true);
        model.markProcessed();
        assertFalse(model.resultStale());
        model.select(2, true);
        assertTrue(model.resultStale());
        model.select(2, false);
        assertFalse(model.resultStale());
    }

    @Test
    void idempotentBindSourceDoesNotNotifyListeners() {
        ChannelSelectionModel model = new ChannelSelectionModel();
        CanSourceInventory inventory = inventory(Path.of("a.mf4"), channel(1), channel(2));
        model.bindSource("f1", Path.of("a.mf4"), inventory);
        model.select(1, true);
        int[] notifications = {0};
        model.addListener(() -> notifications[0]++);
        model.bindSource("f1", Path.of("a.mf4"), inventory);
        model.bindSource("f1", Path.of("a.mf4"), inventory);
        assertEquals(0, notifications[0], "Idempotent sync must not re-notify (FX freeze regression)");
        assertTrue(model.isSelected(1));
    }

    @Test
    void selectNoOpDoesNotNotifyListeners() {
        ChannelSelectionModel model = new ChannelSelectionModel();
        CanSourceInventory inventory = inventory(Path.of("a.mf4"), channel(1));
        model.bindSource("f1", Path.of("a.mf4"), inventory);
        model.select(1, true);
        int[] notifications = {0};
        model.addListener(() -> notifications[0]++);
        model.select(1, true);
        model.select(2, false);
        assertEquals(0, notifications[0]);
    }

    private SourceInventoryMatchState match(CanSourceInventory inventory, DbcDatabase dbc) {
        return SourceInventoryMatchState.ofSummary("x.dbc", matchingService.match(inventory, dbc), dbc);
    }

    private static CanSourceInventory inventory(Path path, CanChannelInventory... channels) {
        List<CanChannelInventory> list = List.of(channels);
        long frames = list.stream().mapToLong(CanChannelInventory::frameCount).sum();
        long unique = list.stream().mapToLong(CanChannelInventory::uniqueMessageCount).sum();
        return new CanSourceInventory(
                path, path.getFileName().toString(), MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, list.size(), frames, unique, true, list, List.of());
    }

    private static CanChannelInventory channel(int id) {
        CanMessageInventory m1 = new CanMessageInventory(new CanMessageKey(id, 0x10, false), 5, 8, 8, false);
        CanMessageInventory m2 = new CanMessageInventory(new CanMessageKey(id, 0x20, false), 5, 8, 8, false);
        return new CanChannelInventory(id, "Channel " + id, 10, 2, 2, 0, 0, List.of(m1, m2));
    }

    private static DbcDatabase database(DbcMessage... messages) {
        return new DbcDatabase("", List.of(), List.of(messages), List.of());
    }

    private static DbcMessage message(long canId, String name) {
        return new DbcMessage(CanIdKey.fromFrame(canId, false), name, 8, "", List.of(), "");
    }
}
