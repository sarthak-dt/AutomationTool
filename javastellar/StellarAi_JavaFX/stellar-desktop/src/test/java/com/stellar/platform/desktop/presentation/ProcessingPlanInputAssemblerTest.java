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
import com.stellar.platform.domain.processing.plan.ObservedTimingMetadata;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.plan.ChannelMappingView;
import com.stellar.platform.processing.plan.ProcessingPlanInputs;
import com.stellar.platform.processing.plan.SourcePlanInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingPlanInputAssemblerTest {

    @TempDir
    Path temp;

    @Test
    void assembleUsesInventoriesAndMappingsOnlyWithoutScanning() throws Exception {
        AtomicInteger inventoryScans = new AtomicInteger();
        AtomicInteger parserCalls = new AtomicInteger();
        AtomicInteger dbcReparses = new AtomicInteger();

        DesktopAppService app = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        Path mf4 = temp.resolve("run.mf4");
        Files.writeString(mf4, "stub");
        Path dbc = temp.resolve("vehicle.dbc");
        Files.writeString(dbc, "VERSION \"\"\n");
        WorkspaceFile measurement = new WorkspaceFile(mf4, FileKind.MEASUREMENT_MF4, 10);
        WorkspaceFile definition = new WorkspaceFile(dbc, FileKind.DEFINITION_DBC, 10);
        app.workspace().addFile(measurement);
        app.workspace().addFile(definition);

        long frames = 4_945_041L;
        CanSourceInventory inventory = inventoryReady(mf4, frames);
        app.sourceInventoryModel().put(measurement.id(), inventory);

        DefinitionSourceId defId = definition.definitionSourceId();
        SourceChannelKey key = SourceChannelKey.of(measurement.projectSourceId(), 1);
        app.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                key,
                ChannelMappingStatus.CONFIRMED,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
                defId,
                definition.fileName(),
                defId,
                definition.fileName(),
                2, 2, 1.0
        ));

        ProcessingPlanInputAssembler assembler = new ProcessingPlanInputAssembler();
        ObservedTimingMetadata timing = new ObservedTimingMetadata(10L, 20L, "ns", "meta");
        assembler.setObservedTimingBySourceId(Map.of(measurement.id(), timing));

        app.processingSelectionModel().captureDecisionOrigin(DecisionWorkspaceMode.DETAILED_MAPPING);
        ProcessingPlanInputs inputs = assembler.assemble(app);
        assertEquals(0, inventoryScans.get());
        assertEquals(0, parserCalls.get());
        assertEquals(0, dbcReparses.get());

        SourcePlanInput source = inputs.source(measurement.id()).orElseThrow();
        assertEquals(0, source.sourceOrderIndex());
        assertEquals(frames, source.inventory().channels().getFirst().frameCount());
        assertEquals(timing, source.observedTiming().orElseThrow());

        ChannelMappingView view = inputs.mapping(SourceChannelRef.of(measurement.id(), 1)).orElseThrow();
        assertEquals(MappingLifecycle.CONFIRMED, view.lifecycle());
        assertEquals(defId.value(), view.definitionSourceId().orElseThrow());
        assertTrue(view.definitionPresent());

        // Mapping change updates definition projection only.
        Path dbc2 = temp.resolve("other.dbc");
        Files.writeString(dbc2, "VERSION \"\"\n");
        WorkspaceFile definition2 = new WorkspaceFile(dbc2, FileKind.DEFINITION_DBC, 5);
        // Use generated id via new file then put mapping with that id after adding.
        app.workspace().addFile(definition2);
        app.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                key,
                ChannelMappingStatus.MANUAL,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
                defId,
                definition.fileName(),
                definition2.definitionSourceId(),
                definition2.fileName(),
                2, 2, 1.0
        ));
        ChannelMappingView updated = assembler.assemble(app)
                .mapping(SourceChannelRef.of(measurement.id(), 1)).orElseThrow();
        assertEquals(MappingLifecycle.MANUAL, updated.lifecycle());
        assertEquals(definition2.definitionSourceId().value(), updated.definitionSourceId().orElseThrow());
        assertEquals(frames, assembler.assemble(app).source(measurement.id()).orElseThrow()
                .inventory().channels().getFirst().frameCount());
    }

    @Test
    void missingInventoryUsesStubAndDefaultTimingIsAbsent() throws Exception {
        DesktopAppService app = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        Path mf4 = temp.resolve("missing-inv.mf4");
        Files.writeString(mf4, "stub");
        WorkspaceFile measurement = new WorkspaceFile(mf4, FileKind.MEASUREMENT_MF4, 10);
        app.workspace().addFile(measurement);

        ProcessingPlanInputAssembler assembler = new ProcessingPlanInputAssembler();
        ProcessingPlanInputs inputs = assembler.assemble(app);
        SourcePlanInput source = inputs.source(measurement.id()).orElseThrow();
        assertEquals(CanInventoryStatus.FILE_ACCEPTED, source.inventory().status());
        assertTrue(source.observedTiming().isEmpty());
        assertFalse(source.inventory().channels().isEmpty() && source.inventory().status() == CanInventoryStatus.INVENTORY_READY);
    }

    @Test
    void recommendedMappingBecomesFallbackWithoutConfirming() throws Exception {
        DesktopAppService app = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        Path mf4 = temp.resolve("rec.mf4");
        Files.writeString(mf4, "stub");
        Path dbc = temp.resolve("rec.dbc");
        Files.writeString(dbc, "VERSION \"\"\n");
        WorkspaceFile measurement = new WorkspaceFile(mf4, FileKind.MEASUREMENT_MF4, 10);
        WorkspaceFile definition = new WorkspaceFile(dbc, FileKind.DEFINITION_DBC, 10);
        app.workspace().addFile(measurement);
        app.workspace().addFile(definition);
        app.sourceInventoryModel().put(measurement.id(), inventoryReady(mf4, 100));
        SourceChannelKey key = SourceChannelKey.of(measurement.projectSourceId(), 1);
        app.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                key,
                ChannelMappingStatus.RECOMMENDED,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
                definition.definitionSourceId(),
                definition.fileName(),
                null,
                "",
                2, 2, 1.0
        ));
        ChannelMappingView view = new ProcessingPlanInputAssembler().assemble(app)
                .mapping(SourceChannelRef.of(measurement.id(), 1)).orElseThrow();
        assertEquals(MappingLifecycle.RECOMMENDED_FALLBACK, view.lifecycle());
        assertEquals(definition.definitionSourceId().value(), view.definitionSourceId().orElseThrow());
        assertEquals(ChannelMappingStatus.RECOMMENDED, app.channelMapping(key).orElseThrow().status());
    }

    @Test
    void resolveEffectivePrefersManualThenConfirmedThenRecommended() {
        DefinitionSourceId rec = DefinitionSourceId.generate();
        DefinitionSourceId man = DefinitionSourceId.generate();
        SourceChannelKey key = SourceChannelKey.of(
                com.stellar.platform.desktop.project.ProjectSourceId.generate(), 1);
        assertEquals(ProcessingPlanInputAssembler.EffectiveOrigin.MANUAL,
                ProcessingPlanInputAssembler.resolveEffective(new SourceChannelMappingEntry(
                        key, ChannelMappingStatus.MANUAL, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                        rec, "rec.dbc", man, "man.dbc", 10, 8, 0.8)).origin());
        assertEquals(ProcessingPlanInputAssembler.EffectiveOrigin.CONFIRMED,
                ProcessingPlanInputAssembler.resolveEffective(new SourceChannelMappingEntry(
                        key, ChannelMappingStatus.CONFIRMED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                        rec, "rec.dbc", rec, "rec.dbc", 10, 10, 1.0)).origin());
        ProcessingPlanInputAssembler.EffectiveResolution fallback =
                ProcessingPlanInputAssembler.resolveEffective(new SourceChannelMappingEntry(
                        key, ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                        rec, "rec.dbc", null, "", 10, 10, 1.0));
        assertEquals(ProcessingPlanInputAssembler.EffectiveOrigin.RECOMMENDED_FALLBACK, fallback.origin());
        assertEquals(MappingLifecycle.RECOMMENDED_FALLBACK, fallback.planLifecycle());
        assertFalse(ProcessingPlanInputAssembler.resolveEffective(new SourceChannelMappingEntry(
                key, ChannelMappingStatus.AMBIGUOUS, ChannelRecommendationKind.AMBIGUOUS,
                null, "", null, "", 10, 4, 0.4)).resolved());
        assertFalse(ProcessingPlanInputAssembler.resolveEffective(new SourceChannelMappingEntry(
                key, ChannelMappingStatus.NO_MATCH, ChannelRecommendationKind.NO_MATCH,
                null, "", null, "", 10, 0, 0.0)).resolved());
        assertTrue(ProcessingPlanInputAssembler.resolveEffective(new SourceChannelMappingEntry(
                key, ChannelMappingStatus.CONFIRMED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                rec, "rec.dbc", rec, "rec.dbc", 10, 10, 1.0)).acceptedForProcessing());
        assertTrue(ProcessingPlanInputAssembler.resolveEffective(new SourceChannelMappingEntry(
                key, ChannelMappingStatus.MANUAL, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                rec, "rec.dbc", man, "man.dbc", 10, 8, 0.8)).acceptedForProcessing());
        assertFalse(fallback.acceptedForProcessing());
    }

    private static CanSourceInventory inventoryReady(Path path, long frames) {
        CanChannelInventory channel = new CanChannelInventory(
                1, "Channel 1", frames, 2, 2, 0, 0,
                List.of(
                        new CanMessageInventory(new CanMessageKey(1, 0x100, false), 5, 8, 8, false),
                        new CanMessageInventory(new CanMessageKey(1, 0x200, false), 5, 8, 8, false)
                ));
        return new CanSourceInventory(
                path, path.getFileName().toString(), MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, 1, frames, 2, true, List.of(channel), List.of());
    }
}