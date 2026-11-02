package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.processing.ParserRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: Project Files refresh / canStart must not mutate match state and re-enter refresh.
 */
class ProjectFilesRefreshRecursionTest {

    @TempDir
    Path tempDir;

    @Test
    void setMatchStateIsIdempotentAndDoesNotNotifyWhenUnchanged() {
        SourceInventoryModel model = new SourceInventoryModel();
        AtomicInteger notifications = new AtomicInteger();
        model.addListener(notifications::incrementAndGet);

        SourceInventoryMatchState waiting = SourceInventoryMatchState.waiting("demo.dbc", 0);
        model.setMatchState(waiting);
        assertEquals(1, notifications.get());

        model.setMatchState(SourceInventoryMatchState.waiting("demo.dbc", 0));
        assertEquals(1, notifications.get(), "identical match state must not notify again");

        model.setMatchState(SourceInventoryMatchState.noDbcSelected());
        assertEquals(2, notifications.get());
    }

    @Test
    void canStartLocalProcessingDoesNotRecurseThroughMatchStateListeners() throws Exception {
        Path mf4 = touch("run.mf4");
        Path dbc = touch("run.dbc");
        DesktopAppService service = serviceWithSyntheticInventory();

        AtomicInteger notifications = new AtomicInteger();
        service.sourceInventoryModel().addListener(() -> {
            notifications.incrementAndGet();
            // Same call graph as ProjectFilesController.refresh -> updateProcessingControls.
            assertDoesNotThrow(service::canStartLocalProcessing);
        });

        service.addMeasurementPaths(List.of(mf4), DesktopAppService.PlatformUi.NOOP);
        service.addDefinitionPaths(List.of(dbc));

        WorkspaceFile measurement = service.workspace().selectedMeasurement().orElseThrow();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline
                && service.sourceInventoryModel().inventoryFor(measurement.id()).isEmpty()) {
            Thread.sleep(20);
        }
        service.refreshDefinitionMatching();

        SourceInventoryMatchState afterAssociation = service.sourceInventoryModel().matchState();
        assertNotNull(afterAssociation);

        int before = notifications.get();
        assertDoesNotThrow(service::canStartLocalProcessing);
        assertDoesNotThrow(service::canStartLocalProcessing);
        assertDoesNotThrow(service::canStartLocalProcessing);
        assertEquals(before, notifications.get(),
                "repeated canStart queries must not mutate observable match state");
        assertEquals(afterAssociation, service.sourceInventoryModel().matchState());
        assertEquals(measurement.id(), service.workspace().selectedMeasurement().orElseThrow().id());
        assertEquals(
                service.workspace().selectedDefinition().orElseThrow().id(),
                measurement.associatedDefinitionId());
        service.shutdown();
    }

    @Test
    void singletonAutoAssociationRunsOnAddNotOnRepeatedCanStart() throws Exception {
        Path mf4 = touch("single.mf4");
        Path dbc = touch("single.dbc");
        DesktopAppService service = serviceWithSyntheticInventory();

        service.addMeasurementPaths(List.of(mf4), DesktopAppService.PlatformUi.NOOP);
        service.addDefinitionPaths(List.of(dbc));

        WorkspaceFile measurement = service.workspace().selectedMeasurement().orElseThrow();
        String definitionId = service.workspace().selectedDefinition().orElseThrow().id();
        assertEquals(definitionId, measurement.associatedDefinitionId());

        // Wait for synthetic inventory + association matching to settle.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline
                && (service.sourceInventoryModel().inventoryFor(measurement.id()).isEmpty()
                || service.inventoryJobState(measurement.projectSourceId())
                != com.stellar.platform.desktop.project.ProjectInventoryJobState.READY)) {
            Thread.sleep(20);
        }
        service.refreshDefinitionMatching();
        SourceInventoryMatchState stable = service.sourceInventoryModel().matchState();
        AtomicInteger notifications = new AtomicInteger();
        service.sourceInventoryModel().addListener(notifications::incrementAndGet);

        int before = notifications.get();
        service.canStartLocalProcessing();
        service.canStartLocalProcessing();
        service.validateCurrentSelection();
        assertEquals(before, notifications.get());
        assertEquals(0, notifications.get());
        assertEquals(stable, service.sourceInventoryModel().matchState());
        assertEquals(definitionId, measurement.associatedDefinitionId());
        service.shutdown();
    }

    @Test
    void multiSourceCanStartDoesNotSilentlyRemapDefinitions() throws Exception {
        Path a = touch("A.mf4");
        Path b = touch("B.mf4");
        Path d1 = touch("D1.dbc");
        Path d2 = touch("D2.dbc");
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));

        service.addMeasurementPaths(List.of(a, b), DesktopAppService.PlatformUi.NOOP);
        service.addDefinitionPaths(List.of(d1, d2));

        List<WorkspaceFile> measurements = service.workspace().measurementFiles();
        assertEquals(2, measurements.size());
        assertEquals(2, service.workspace().definitionSourceCount());

        // Ambiguous multi-source/multi-definition: no automatic association.
        for (WorkspaceFile file : measurements) {
            assertTrue(file.associatedDefinitionId() == null
                    || file.associatedDefinitionId().isBlank());
        }

        String selectedMeasBefore = service.workspace().selectedMeasurement().map(WorkspaceFile::id).orElse(null);
        String selectedDefBefore = service.workspace().selectedDefinition().map(WorkspaceFile::id).orElse(null);
        SourceInventoryMatchState matchBefore = service.sourceInventoryModel().matchState();

        service.canStartLocalProcessing();
        service.validateCurrentSelection();
        service.canStartLocalProcessing();

        assertEquals(selectedMeasBefore, service.workspace().selectedMeasurement().map(WorkspaceFile::id).orElse(null));
        assertEquals(selectedDefBefore, service.workspace().selectedDefinition().map(WorkspaceFile::id).orElse(null));
        assertEquals(matchBefore, service.sourceInventoryModel().matchState());
        assertFalse(service.canStartLocalProcessing());
    }

    private Path touch(String name) throws Exception {
        Path path = tempDir.resolve(name);
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".dbc")) {
            Files.writeString(path, """
                    VERSION ""
                    BO_ 256 Msg: 8 Vector__XXX
                     SG_ Sig : 0|8@1+ (1,0) [0|0] "" Vector__XXX
                    """);
            return path;
        }
        com.stellar.platform.desktop.support.TestMeasurementFixtures.writeStubMeasurement(path);
        return path;
    }

    private static DesktopAppService serviceWithSyntheticInventory() {
        return new DesktopAppService(
                new DesktopSession(ParserRegistry.load()),
                (sourceId, path, ctx, progress) -> {
                    CanSourceInventory inv = syntheticReady(path);
                    progress.onInventoryUpdated(inv);
                    return inv;
                }
        );
    }

    private static CanSourceInventory syntheticReady(Path path) {
        CanMessageInventory message = new CanMessageInventory(
                new CanMessageKey(1, 0x100L, false), 10, 8, 8, false);
        CanChannelInventory channel = new CanChannelInventory(
                1, "Channel 1", 10, 1, 1, 0, 0, List.of(message));
        return new CanSourceInventory(
                path,
                path.getFileName().toString(),
                MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY,
                1,
                10,
                1,
                true,
                List.of(channel),
                List.of()
        );
    }

    private static void installReadyInventory(DesktopAppService service, WorkspaceFile measurement) {
        CanMessageInventory message = new CanMessageInventory(
                new CanMessageKey(1, 0x100L, false), 10, 8, 8, false);
        CanChannelInventory channel = new CanChannelInventory(
                1, "Channel 1", 10, 1, 1, 0, 0, List.of(message));
        CanSourceInventory inventory = new CanSourceInventory(
                measurement.path(),
                measurement.fileName(),
                MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY,
                1,
                10,
                1,
                true,
                List.of(channel),
                List.of()
        );
        service.sourceInventoryModel().put(measurement.id(), inventory);
        service.sourceInventoryModel().focus(measurement.id());
        service.refreshDefinitionMatching();
    }
}
