package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smart navigation / reprocess orchestration (no parser changes).
 */
class SmartNavigationWorkflowTest {

    private DesktopAppService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void processButtonRequiresSelection_stickySemantics() {
        service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        assertFalse(service.canStartLocalProcessing());
        ChannelSelectionModel selection = service.channelSelectionModel();
        assertFalse(selection.hasSelection());
        assertEquals(0, selection.summarize(null, null).selectedChannels());
    }

    @Test
    void projectFilesAndSignalsShareSameSelectionModel() {
        service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        assertTrue(service.channelSelectionModel() == service.channelSelectionModel());
        ChannelSelectionModel model = service.channelSelectionModel();
        model.bindSource("f1", java.nio.file.Path.of("demo.mf4"), null);
        model.replaceSelection(List.of(1, 3));
        assertEquals(Set.of(1, 3), model.selectedChannelIds());
        assertEquals("Channel 1, Channel 3", model.toProcessingSelection().summaryLabel());
    }

    @Test
    void preserveResultDuringReprocessAndAtomicSwap() throws Exception {
        service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        InMemorySignalCatalogStore oldStore = new InMemorySignalCatalogStore("a.mf4", "a.dbc", 100);
        oldStore.accept(List.of(can("VehicleSpeed", "M", 0x10, 3, 1_000_000_000L, 10)));
        service.signalsModel().replaceCatalog(
                oldStore,
                null,
                SignalsProcessingContext.of("a.mf4", "a.dbc", "Channel 3"),
                false
        );
        SignalCatalogKey oldKey = service.signalsModel().catalog().entries().getFirst().key();
        service.signalsModel().addToWorkspace(oldKey);

        assertEquals("Channel 3", service.signalsModel().processingContext().channelsSummary());
        assertEquals(1, service.signalsModel().workspaceKeys().size());

        // Simulate reprocess preserve path: replace catalog with Ch1+Ch3 while preserving workspace.
        InMemorySignalCatalogStore next = new InMemorySignalCatalogStore("a.mf4", "a.dbc", 100);
        next.accept(List.of(
                can("VehicleSpeed", "M", 0x10, 3, 1_000_000_000L, 10),
                can("EngineRPM", "E", 0x20, 1, 1_000_000_000L, 1000)
        ));
        service.signalsModel().replaceCatalog(
                next,
                null,
                SignalsProcessingContext.of("a.mf4", "a.dbc", "Channel 1, Channel 3"),
                true
        );
        assertEquals("Channel 1, Channel 3", service.signalsModel().processingContext().channelsSummary());
        assertTrue(service.signalsModel().workspaceKeys().contains(oldKey));
        assertEquals(2, service.signalsModel().catalog().size());
    }

    @Test
    void staleGenerationPreventsOverwrite() {
        ProjectProcessingContext ctx = new ProjectProcessingContext();
        long g1 = ctx.beginRequest();
        long g2 = ctx.beginRequest();
        assertNotEquals(g1, g2);
        assertFalse(ctx.isCurrentGeneration(g1));
        assertTrue(ctx.isCurrentGeneration(g2));
    }

    @Test
    void cancelClearsUpdatingBannerAndKeepsFailureOrCancelMessagePath() {
        ProjectProcessingContext ctx = new ProjectProcessingContext();
        ctx.beginRequest();
        ctx.setViewingResultChannels("Channel 3");
        ctx.setUpdatingToChannels("Channel 1, Channel 3");
        ctx.markReprocessFailure("Reprocessing cancelled. Previous Channel 3 result is still displayed.");
        assertTrue(ctx.reprocessFailure());
        assertTrue(ctx.updatingToChannels().isBlank());
        assertTrue(ctx.lastFailureMessage().contains("Channel 3"));
    }

    @Test
    void autoNavigateFlagConsumedOnce() {
        ProjectProcessingContext ctx = new ProjectProcessingContext();
        ctx.markAutoNavigateToSignals();
        assertTrue(ctx.consumeAutoNavigateToSignals());
        assertFalse(ctx.consumeAutoNavigateToSignals());
    }

    @Test
    void primaryWorkflowOptionsDoNotForceProcessingPage() {
        ProcessingStartOptions opts = ProcessingStartOptions.primaryWorkflow();
        assertTrue(opts.navigateToSignalsOnSuccess());
        assertFalse(opts.openProcessingPageOnStart());
        assertTrue(opts.preserveExistingResultUntilSuccess());
    }

    @Test
    void sameNameDifferentChannelNotRemappedOnPreserve() {
        service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        InMemorySignalCatalogStore oldStore = new InMemorySignalCatalogStore("a.mf4", "a.dbc", 100);
        oldStore.accept(List.of(can("VehicleSpeed", "M", 0x10, 3, 1L, 10)));
        service.signalsModel().replaceCatalog(
                oldStore, null, SignalsProcessingContext.of("a.mf4", "a.dbc", "Channel 3"), false);
        SignalCatalogKey ch3 = service.signalsModel().catalog().entries().getFirst().key();
        service.signalsModel().addToWorkspace(ch3);

        InMemorySignalCatalogStore next = new InMemorySignalCatalogStore("a.mf4", "a.dbc", 100);
        next.accept(List.of(can("VehicleSpeed", "M", 0x10, 1, 1L, 99)));
        service.signalsModel().replaceCatalog(
                next, null, SignalsProcessingContext.of("a.mf4", "a.dbc", "Channel 1"), true);
        assertTrue(service.signalsModel().workspaceKeys().isEmpty(),
                "Ch3 VehicleSpeed must not remap to Ch1 by name");
    }

    private static CanonicalSignal can(
            String name, String message, long canId, int channel, long ts, double phys
    ) {
        return new CanonicalSignal(
                name, message, ts, canId, channel, phys, (long) phys, "u", null, SourceReference.ofLine(1));
    }

}
