package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbcMappingGroupingPresentationTest {

    @Test
    void groupsByProjectSourceIdNotChannelNumber() {
        ProjectSourceId a = ProjectSourceId.generate();
        ProjectSourceId b = ProjectSourceId.generate();
        List<SourceChannelMappingEntry> mappings = List.of(
                entry(a, 1, ChannelMappingStatus.RECOMMENDED, 0.1),
                entry(a, 2, ChannelMappingStatus.RECOMMENDED, 0.2),
                entry(a, 3, ChannelMappingStatus.CONFIRMED, 1.0),
                entry(b, 1, ChannelMappingStatus.RECOMMENDED, 0.3),
                entry(b, 2, ChannelMappingStatus.MANUAL, 0.4)
        );
        Map<ProjectSourceId, List<SourceChannelMappingEntry>> bySource = new LinkedHashMap<>();
        for (SourceChannelMappingEntry m : mappings) {
            bySource.computeIfAbsent(m.sourceChannelKey().sourceId(), k -> new ArrayList<>()).add(m);
        }
        assertEquals(2, bySource.size());
        assertEquals(3, bySource.get(a).size());
        assertEquals(2, bySource.get(b).size());
        assertTrue(bySource.get(a).stream().allMatch(e -> e.sourceChannelKey().sourceId().equals(a)));
        assertTrue(bySource.get(b).stream().allMatch(e -> e.sourceChannelKey().sourceId().equals(b)));
    }

    @Test
    void sourceSummaryCountsAreLocal() {
        ProjectSourceId a = ProjectSourceId.generate();
        List<SourceChannelMappingEntry> channels = List.of(
                entry(a, 1, ChannelMappingStatus.RECOMMENDED, 0.1),
                entry(a, 2, ChannelMappingStatus.RECOMMENDED, 0.2),
                entry(a, 3, ChannelMappingStatus.CONFIRMED, 1.0)
        );
        DbcMappingTablePane.SourceMappingGroup group = new DbcMappingTablePane.SourceMappingGroup(
                a,
                new SourceDisplayNameResolver.DisplayLabel(a, "Source A", "..._a.mf4", "a.mf4", "a.mf4"),
                null,
                channels,
                true,
                channels
        );
        assertEquals(2, group.recommendedCount());
        assertEquals(1, group.confirmedCount());
    }

    @Test
    void globalUniqueUnionUsedForSourceHeaderInventory() {
        CanSourceInventory inventory = multi(
                channel(1, 1, 2, 3),
                channel(2, 2, 3, 4),
                channel(3, 3, 4, 5)
        );
        assertEquals(9, inventory.channels().stream().mapToLong(CanChannelInventory::uniqueMessageCount).sum());
        assertEquals(5, inventory.globalUniqueCanIdentityCount());
    }

    private static SourceChannelMappingEntry entry(
            ProjectSourceId sourceId, int channel, ChannelMappingStatus status, double ratio
    ) {
        DefinitionSourceId def = DefinitionSourceId.generate();
        return new SourceChannelMappingEntry(
                SourceChannelKey.of(sourceId, channel),
                status,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
                def,
                "3.dbc",
                status == ChannelMappingStatus.CONFIRMED || status == ChannelMappingStatus.MANUAL ? def : null,
                status == ChannelMappingStatus.CONFIRMED || status == ChannelMappingStatus.MANUAL ? "3.dbc" : "",
                10,
                Math.round(10 * ratio),
                ratio
        );
    }

    private static CanSourceInventory multi(CanChannelInventory... channels) {
        List<CanChannelInventory> list = List.of(channels);
        return new CanSourceInventory(
                Path.of("u.mf4"), "u.mf4", MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, list.size(),
                list.stream().mapToLong(CanChannelInventory::frameCount).sum(),
                CanSourceInventory.countGlobalUniqueCanIdentities(list),
                true, list, List.of());
    }

    private static CanChannelInventory channel(int id, long... ids) {
        List<CanMessageInventory> messages = new ArrayList<>();
        for (long canId : ids) {
            messages.add(new CanMessageInventory(new CanMessageKey(id, canId, false), 1, 8, 8, false));
        }
        return new CanChannelInventory(id, "Channel " + id, ids.length, ids.length, ids.length, 0, 0, messages);
    }
}
