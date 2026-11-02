package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.plan.ChannelMappingView;
import com.stellar.platform.processing.plan.ProcessingPlanInputs;
import com.stellar.platform.processing.plan.SourcePlanInput;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopAppServicePlanExecutionTest {

    @BeforeAll
    static void initFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException already) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(30)
    void executeReviewedPlanRunsOffFxThread() throws Exception {
        Path temp = Files.createTempDirectory("plan-exec");
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        WorkspaceFile measurement = addReadySource(service, temp, "alpha.mf4", 1, 100L);
        WorkspaceFile definition = addDefinition(service, temp, "map.dbc");
        addConfirmedMapping(service, measurement, 1, definition.definitionSourceId());

        ProcessingPlanInputAssembler assembler = new ProcessingPlanInputAssembler();
        ProcessingPlanInputs inputs = assembler.assemble(service);
        ProcessingSelectionWorkspaceModel model = service.processingSelectionModel();
        model.select(SourceChannelRef.of(measurement.id(), 1));
        model.reviewPlan(inputs);
        assertTrue(service.isReviewedPlanStillExecutable(model.reviewedPlan(), inputs));

        AtomicBoolean ranOffFx = new AtomicBoolean(false);
        CountDownLatch probeHit = new CountDownLatch(1);
        service.planExecutionThreadProbeForTest = () -> {
            ranOffFx.set(!Platform.isFxApplicationThread());
            probeHit.countDown();
            service.cancelProcessing();
        };

        CountDownLatch uiDone = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                service.executeReviewedPlan(Platform::runLater, () -> {
                });
            } catch (Throwable t) {
                error.set(t);
            } finally {
                uiDone.countDown();
            }
        });
        assertTrue(uiDone.await(10, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
        assertTrue(probeHit.await(15, TimeUnit.SECONDS), "background probe not reached");
        assertTrue(ranOffFx.get(), "plan execution must not run on JavaFX thread");
        assertEquals(1, model.selection().size());
    }

    @Test
    void staleCheckRejectsWhenDefinitionChanges() {
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        ProcessingSelectionWorkspaceModel model = service.processingSelectionModel();
        model.select(SourceChannelRef.of("A", 1));
        var reviewed = model.reviewPlan(confirmedInputs());

        ProcessingPlanInputs changed = new ProcessingPlanInputs(
                List.of(new SourcePlanInput("A", 0, inventoryReady("a.mf4", channel(1, 50)), null)),
                List.of(new ChannelMappingView(
                        SourceChannelRef.of("A", 1), MappingLifecycle.RECOMMENDED,
                        "other", "other.dbc", 1, 1, 1.0, true))
        );
        assertFalse(service.isReviewedPlanStillExecutable(reviewed, changed));
    }

    private static WorkspaceFile addReadySource(
            DesktopAppService service, Path temp, String name, int channels, long framesPerChannel
    ) throws Exception {
        Path path = temp.resolve(name);
        Files.writeString(path, "stub");
        WorkspaceFile file = new WorkspaceFile(path, FileKind.MEASUREMENT_MF4, 10);
        service.workspace().addFile(file);
        List<CanChannelInventory> channelList = new java.util.ArrayList<>();
        for (int i = 1; i <= channels; i++) {
            channelList.add(new CanChannelInventory(
                    i, "Channel " + i, framesPerChannel, 1, 1, 0, 0,
                    List.of(new CanMessageInventory(new CanMessageKey(i, 0x100L + i, false), 10, 8, 8, false))));
        }
        long totalFrames = framesPerChannel * channels;
        CanSourceInventory inventory = new CanSourceInventory(
                path, name, MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                channels, totalFrames, channels, true, channelList, List.of());
        service.sourceInventoryModel().put(file.id(), inventory);
        return file;
    }

    private static WorkspaceFile addDefinition(DesktopAppService service, Path temp, String name) throws Exception {
        Path path = temp.resolve(name);
        Files.writeString(path, "VERSION \"\"\n");
        WorkspaceFile file = new WorkspaceFile(path, FileKind.DEFINITION_DBC, 10);
        service.workspace().addFile(file);
        return file;
    }

    private static void addConfirmedMapping(
            DesktopAppService service, WorkspaceFile measurement, int channelId, DefinitionSourceId defId
    ) {
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(measurement.projectSourceId(), channelId),
                ChannelMappingStatus.CONFIRMED,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
                defId,
                "map.dbc",
                defId,
                "map.dbc",
                1, 1, 1.0
        ));
    }

    private static ProcessingPlanInputs confirmedInputs() {
        return new ProcessingPlanInputs(
                List.of(new SourcePlanInput("A", 0, inventoryReady("a.mf4", channel(1, 50)), null)),
                List.of(new ChannelMappingView(
                        SourceChannelRef.of("A", 1), MappingLifecycle.CONFIRMED,
                        "d", "a.dbc", 1, 1, 1.0, true))
        );
    }

    private static CanSourceInventory inventoryReady(String name, CanChannelInventory... channels) {
        long frames = 0;
        long ids = 0;
        for (CanChannelInventory ch : channels) {
            frames += ch.frameCount();
            ids += ch.uniqueMessageCount();
        }
        return new CanSourceInventory(
                Path.of(name), name, MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                channels.length, frames, ids, true, List.of(channels), List.of());
    }

    private static CanChannelInventory channel(int id, long frames) {
        return new CanChannelInventory(
                id, "Channel " + id, frames, 1, 1, 0, 0,
                List.of(new CanMessageInventory(new CanMessageKey(id, 0x100, false), 10, 8, 8, false)));
    }
}
