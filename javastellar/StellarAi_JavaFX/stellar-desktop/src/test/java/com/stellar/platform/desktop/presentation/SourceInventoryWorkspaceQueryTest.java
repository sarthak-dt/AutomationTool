package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.CanTypeFilter;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceInventoryWorkspaceQueryTest {

    @Test
    void sourceGlobalUnionIsNotPerChannelSum() {
        ProjectSourceId source = ProjectSourceId.generate();
        CanSourceInventory inv = inventory(source,
                channel(1, msg(1, 0xA, false, 10), msg(1, 0xB, false, 10), msg(1, 0xC, false, 10)),
                channel(2, msg(2, 0xB, false, 10), msg(2, 0xC, false, 10), msg(2, 0xD, false, 10))
        );
        assertEquals(4, inv.globalUniqueCanIdentityCount());
        var result = SourceInventoryWorkspaceQuery.queryMessages(
                source, inv, OptionalInt.empty(), "", CanTypeFilter.ALL);
        assertEquals(4, result.uniqueIdentitiesInScope());
        assertEquals(6, result.rows().size());
        assertEquals(6, result.totalRowsBeforeFilter());
    }

    @Test
    void standardAndExtendedSameNumericIdAreDistinct() {
        ProjectSourceId source = ProjectSourceId.generate();
        CanSourceInventory inv = inventory(source,
                channel(1, msg(1, 0x123, false, 5), msg(1, 0x123, true, 7))
        );
        assertEquals(2, inv.globalUniqueCanIdentityCount());
        var result = SourceInventoryWorkspaceQuery.queryMessages(
                source, inv, OptionalInt.empty(), "", CanTypeFilter.ALL);
        assertEquals(2, result.rows().size());
        var std = SourceInventoryWorkspaceQuery.queryMessages(
                source, inv, OptionalInt.empty(), "", CanTypeFilter.STANDARD);
        var ext = SourceInventoryWorkspaceQuery.queryMessages(
                source, inv, OptionalInt.empty(), "", CanTypeFilter.EXTENDED);
        assertEquals(1, std.rows().size());
        assertEquals(1, ext.rows().size());
        assertTrue(!std.rows().getFirst().extended());
        assertTrue(ext.rows().getFirst().extended());
    }

    @Test
    void channelScopeDoesNotLeakOtherSourceChannels() {
        ProjectSourceId a = ProjectSourceId.generate();
        ProjectSourceId b = ProjectSourceId.generate();
        CanSourceInventory invA = inventory(a, channel(1, msg(1, 0xA, false, 1), msg(1, 0xB, false, 1)));
        CanSourceInventory invB = inventory(b, channel(1, msg(1, 0xC, false, 1), msg(1, 0xD, false, 1)));
        var rowsA = SourceInventoryWorkspaceQuery.queryMessages(
                a, invA, OptionalInt.of(1), "", CanTypeFilter.ALL).rows();
        assertEquals(2, rowsA.size());
        assertTrue(rowsA.stream().allMatch(r -> r.sourceId().equals(a)));
        assertTrue(rowsA.stream().noneMatch(r -> r.canId() == 0xC || r.canId() == 0xD));
        assertEquals(2, SourceInventoryWorkspaceQuery.queryMessages(
                b, invB, OptionalInt.of(1), "", CanTypeFilter.ALL).rows().size());
    }

    @Test
    void searchAndClearArePresentationOnly() {
        ProjectSourceId source = ProjectSourceId.generate();
        CanSourceInventory inv = inventory(source,
                channel(1, msg(1, 0x201, false, 3), msg(1, 0x208, false, 4), msg(1, 0x421, false, 5)));
        var hit = SourceInventoryWorkspaceQuery.queryMessages(
                source, inv, OptionalInt.of(1), "0x201", CanTypeFilter.ALL);
        assertEquals(1, hit.rows().size());
        assertEquals(0x201, hit.rows().getFirst().canId());
        var cleared = SourceInventoryWorkspaceQuery.queryMessages(
                source, inv, OptionalInt.of(1), "", CanTypeFilter.ALL);
        assertEquals(3, cleared.rows().size());
    }

    @Test
    void channelFrameCountMatchesMessageSum() {
        CanChannelInventory ch = channel(1,
                msg(1, 0x1, false, 100),
                msg(1, 0x2, false, 200),
                msg(1, 0x3, true, 50));
        assertEquals(350, ch.frameCount());
        assertTrue(SourceInventoryWorkspaceQuery.channelFrameCountMatchesMessageSum(ch));
    }

    @Test
    void canIdSearchParsesHexAndDecimal() {
        assertEquals(0x201, CanIdSearchQuery.parse("0x201").exactId().orElseThrow());
        assertEquals(201, CanIdSearchQuery.parse("201").exactId().orElseThrow());
        assertTrue(CanIdSearchQuery.parse("not-an-id").exactId().isEmpty());
    }

    @Test
    void aliasRegistrySharedAcrossMappingAndInventory() {
        DesktopAppService service = new DesktopAppService(new DesktopSession());
        WorkspaceFile a = service.workspace().addFile(
                new WorkspaceFile(Path.of("a_164651.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 1));
        WorkspaceFile b = service.workspace().addFile(
                new WorkspaceFile(Path.of("b_171438.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 1));
        assertEquals("Source A", service.sourceAliasRegistry().aliasFor(a.projectSourceId()));
        assertEquals("Source B", service.sourceAliasRegistry().aliasFor(b.projectSourceId()));
        SourceInventoryWorkspaceModel model = new SourceInventoryWorkspaceModel();
        var snap = model.rebuild(
                service.workspace().measurementFiles(),
                service.sourceInventoryModel(),
                service.sourceAliasRegistry(),
                id -> com.stellar.platform.desktop.project.ProjectInventoryJobState.READY
        );
        assertEquals("Source A", snap.sources().get(0).alias());
        assertEquals("Source B", snap.sources().get(1).alias());
    }

    @Test
    void removeBDoesNotRenameCAndNewGetsD() {
        SourceAliasRegistry registry = new SourceAliasRegistry();
        ProjectSourceId a = ProjectSourceId.generate();
        ProjectSourceId b = ProjectSourceId.generate();
        ProjectSourceId c = ProjectSourceId.generate();
        assertEquals("Source A", registry.aliasFor(a));
        assertEquals("Source B", registry.aliasFor(b));
        assertEquals("Source C", registry.aliasFor(c));
        registry.retainOnly(java.util.Set.of(a.value(), c.value()));
        assertEquals("Source C", registry.aliasFor(c));
        ProjectSourceId d = ProjectSourceId.generate();
        assertEquals("Source D", registry.aliasFor(d));
    }

    private static CanSourceInventory inventory(ProjectSourceId ignored, CanChannelInventory... channels) {
        long frames = 0;
        for (CanChannelInventory ch : channels) {
            frames += ch.frameCount();
        }
        long unique = CanSourceInventory.countGlobalUniqueCanIdentities(List.of(channels));
        return new CanSourceInventory(
                Path.of("synth.mf4"),
                "synth.mf4",
                MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY,
                channels.length,
                frames,
                unique,
                true,
                List.of(channels),
                List.of()
        );
    }

    private static CanChannelInventory channel(int id, CanMessageInventory... messages) {
        long frames = 0;
        long std = 0;
        long ext = 0;
        for (CanMessageInventory m : messages) {
            frames += m.frameCount();
            if (m.extended()) {
                ext++;
            } else {
                std++;
            }
        }
        return new CanChannelInventory(
                id, "Channel " + id, frames, messages.length, std, ext, 0, List.of(messages));
    }

    private static CanMessageInventory msg(int channel, long canId, boolean ext, long frames) {
        return new CanMessageInventory(new CanMessageKey(channel, canId, ext), frames, 8, 8, false);
    }
}
